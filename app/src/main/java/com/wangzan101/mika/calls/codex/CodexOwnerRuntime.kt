package com.wangzan101.mika.calls.codex

import android.content.Context
import android.util.AtomicFile
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CodexOwnerPhase { UNAVAILABLE, NEEDS_PROVIDER, IDLE, STARTING, RESTORING, RESPONDING, CANCELLING, FAILED }
enum class CodexOwnerRole { USER, ASSISTANT }

data class CodexOwnerState(
    val phase: CodexOwnerPhase,
    val providerId: String? = null,
    val model: String? = null,
    val threadId: String? = null,
    val error: String? = null,
    val automaticMemoryEnabled: Boolean = false,
)

data class CodexOwnerMessage(val id: String, val role: CodexOwnerRole, val text: String, val complete: Boolean) {
    override fun toString() = "CodexOwnerMessage(role=$role, complete=$complete)"
}

/** The owner-facing text conversation. No path from Telecom, caller transcripts, or agent tool routing. */
class CodexOwnerRuntime(
    context: Context,
    private val providers: ProviderStore,
    private val ownerMemory: () -> String = { "" },
) {
    private val launcher = CodexProcessLauncher(context)
    private val store = OwnerSelectionStore(context.applicationContext.noBackupFilesDir)
    private var selection = OwnerSelection()
    private var loaded = false
    @Volatile private var active: Operation? = null
    private val disposed = AtomicBoolean()
    private val liveProcess = AtomicReference<Process?>(null)
    private val exclusive = Mutex()
    private val mutable = MutableStateFlow(CodexOwnerState(
        if (launcher.available) CodexOwnerPhase.NEEDS_PROVIDER else CodexOwnerPhase.UNAVAILABLE,
    ))
    val state = mutable.asStateFlow()
    private val content = MutableStateFlow<List<CodexOwnerMessage>>(emptyList())
    val messages = content.asStateFlow()

    suspend fun configureProvider(providerId: String, model: String): Boolean = exclusive {
        if (active != null || !loadSelection()) return@exclusive false
        val normalized = model.trim()
        val proposed = selection.copy(providerId = providerId, model = normalized)
        if (!CodexWire.validId(providerId) || !modelName.matches(normalized)) {
            return@exclusive fail(CodexIssue.PROVIDER)
        }
        val key = keyFor(proposed) ?: return@exclusive fail(CodexIssue.PROVIDER)
        // Do not send an existing conversation to a different credential without explicit newChat().
        if (selection.threadId != null && (selection.providerId != providerId ||
                !codexKeyMatchesThread(selection.threadId, selection.keyFingerprint, codexKeyFingerprint(key)))) {
            return@exclusive fail(CodexIssue.ACCOUNT_CHANGED)
        }
        if (!persist(proposed)) return@exclusive false
        ready()
        true
    }

    /** Restore the selected owner's recent persisted messages from the engine itself. */
    suspend fun resume(): Boolean = exclusive {
        if (active != null || !loadSelection()) return@exclusive false
        if (selection.threadId == null) {
            ready()
            return@exclusive true
        }
        operate(restoreOnly = true, input = null)
    }

    /** Detach the selected thread. The engine's prior local thread remains on disk. */
    suspend fun newChat(): Boolean = exclusive {
        if (active != null || !loadSelection()) return@exclusive false
        if (!persist(selection.copy(threadId = null, keyFingerprint = null))) return@exclusive false
        content.value = emptyList()
        ready()
        true
    }

    suspend fun send(text: String): Boolean = exclusive {
        if (active != null || !loadSelection()) return@exclusive false
        val input = text.trim()
        if (input.isEmpty() || input.length > MAX_INPUT_CHARS) {
            publish(CodexOwnerPhase.FAILED, "请输入 1 到 ${MAX_INPUT_CHARS} 个字符")
            return@exclusive false
        }
        operate(restoreOnly = false, input = input)
    }

    /** True means the engine ended (or its dedicated process was stopped), not merely that Stop was tapped. */
    suspend fun cancel(): Boolean = onMain {
        val op = active ?: return@onMain false
        op.stop.complete(Unit)
        publish(CodexOwnerPhase.CANCELLING)
        withTimeoutOrNull(12_000L) { op.finished.await(); true } ?: false
    }

    /** Processes are scoped to operations. Closing does not clear the selected thread. */
    suspend fun close() = onMain {
        cancel()
        Unit
    }

    /** Use when disposing a screen scope that may already be cancelled. This instance is closed. */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        active?.stop?.complete(Unit)
        liveProcess.get()?.let { process ->
            // Android process I/O teardown can briefly block; do not do it on the Compose thread.
            thread(isDaemon = true, name = "mika-codex-dispose") { runCatching { process.destroyForcibly() } }
        }
    }

    private suspend fun operate(restoreOnly: Boolean, input: String?): Boolean {
        if (!launcher.available) return fail(CodexIssue.ENGINE_UNAVAILABLE)
        val config = selection
        if (config.providerId == null || config.model == null) return fail(CodexIssue.PROVIDER)
        val op = Operation()
        active = op
        publish(if (restoreOnly) CodexOwnerPhase.RESTORING else CodexOwnerPhase.STARTING)
        var process: Process? = null
        var client: CodexJsonlClient? = null
        var turn: ActiveTurn? = null
        var failure: CodexIssue? = null
        var succeeded = false
        try {
            succeeded = withTimeoutOrNull(MAX_TURN_MS) {
                checkStopped(op)
                val key = keyFor(config) ?: throw CodexProblem(CodexIssue.PROVIDER)
                val keyFingerprint = codexKeyFingerprint(key)
                if (!codexKeyMatchesThread(config.threadId, config.keyFingerprint, keyFingerprint)) {
                    throw CodexProblem(CodexIssue.ACCOUNT_CHANGED)
                }
                val savedInstructions = readCodexOwnerSnapshot(ownerMemory)
                checkStopped(op)
                // Attach before leaving the non-cancellable section; a UI cancellation cannot orphan a process.
                withContext(Dispatchers.IO + NonCancellable) {
                    process = launcher.launch(key)
                    liveProcess.set(process)
                    if (disposed.get()) runCatching { process?.destroyForcibly() }
                }
                val launched = checkNotNull(process)
                val rpc = CodexJsonlClient(launched.inputStream, launched.outputStream, launched.errorStream)
                client = rpc
                checkStopped(op)
                rpc.initialize()
                checkStopped(op)
                val existing = config.threadId
                val threadId = openThread(rpc, config.model, existing, savedInstructions)
                if (existing == null && !persist(config.copy(threadId = threadId, keyFingerprint = keyFingerprint), publishFailure = false)) {
                    throw CodexProblem(CodexIssue.STORAGE)
                }
                checkStopped(op)
                if (existing != null) content.value = history(rpc, threadId)
                checkStopped(op)
                if (input != null) {
                    publish(CodexOwnerPhase.RESPONDING)
                    val before = content.value
                    val sent = rpc.request("turn/start", CodexWire.turn(threadId, input))
                    val turnId = sent.obj("turn")?.str("id")?.takeIf(CodexWire::validId)
                        ?: throw CodexProblem(CodexIssue.PROTOCOL)
                    turn = ActiveTurn(threadId, turnId, before, input)
                    content.value = checkNotNull(turn).messages()
                    stream(rpc, checkNotNull(turn), op)
                }
                true
            } ?: run {
                failure = CodexIssue.TIMEOUT
                false
            }
        } catch (problem: CodexProblem) {
            failure = problem.issue
        } catch (e: CancellationException) {
            failure = CodexIssue.INTERRUPTED
            throw e
        } catch (_: Exception) {
            failure = CodexIssue.PROTOCOL
        } finally {
            withContext(NonCancellable) {
                if (!succeeded && turn?.terminal == false && client != null) {
                    val t = checkNotNull(turn)
                    // A timeout or user stop cannot wait indefinitely on an unhealthy child.
                    withTimeoutOrNull(1_500L) {
                        try {
                            val rpc = checkNotNull(client)
                            rpc.request("turn/interrupt", CodexWire.interrupt(t.threadId, t.id))
                            while (true) {
                                val frame = rpc.notifications.receiveCatching().getOrNull() ?: break
                                if (codexTurnEvent(frame, t.threadId, t.id) is CodexTurnEvent.End) break
                            }
                        } catch (_: CodexProblem) {
                            // The dedicated process is still stopped below.
                        }
                    }
                }
                client?.close()
                withContext(Dispatchers.IO) {
                    process?.let { CodexProcessLauncher.stop(it, graceful = succeeded && !disposed.get()) }
                    liveProcess.compareAndSet(process, null)
                }
                active = null
                if (succeeded) ready()
                else if (failure == CodexIssue.INTERRUPTED || op.stop.isCompleted && failure != CodexIssue.TOOL) {
                    publish(CodexOwnerPhase.IDLE, CodexIssue.INTERRUPTED.userMessage)
                } else fail(failure ?: CodexIssue.PROTOCOL)
                op.finished.complete(Unit)
            }
        }
        return succeeded
    }

    private suspend fun openThread(rpc: CodexJsonlClient, model: String, existing: String?, savedInstructions: String): String {
        val result = try {
            rpc.request(if (existing == null) "thread/start" else "thread/resume",
                CodexWire.thread(launcher.workspace.absolutePath, model, existing, savedInstructions))
        } catch (e: CodexProblem) {
            if (existing != null && e.issue == CodexIssue.REQUEST) throw CodexProblem(CodexIssue.RESUME)
            throw e
        }
        val thread = result.obj("thread")
        val id = thread?.str("id")?.takeIf(CodexWire::validId) ?: throw CodexProblem(CodexIssue.PROTOCOL)
        if (existing != null && id != existing) throw CodexProblem(CodexIssue.RESUME)
        return id
    }

    private suspend fun history(rpc: CodexJsonlClient, threadId: String): List<CodexOwnerMessage> {
        val result = rpc.request("thread/turns/list", CodexWire.history(threadId))
        return codexHistory(result)
    }

    private suspend fun stream(rpc: CodexJsonlClient, turn: ActiveTurn, op: Operation) {
        while (true) {
            checkStopped(op)
            val frame = select<JsonObject?> {
                op.stop.onAwait { null }
                rpc.notifications.onReceiveCatching {
                    it.getOrNull() ?: throw CodexProblem(CodexIssue.PROTOCOL)
                }
            } ?: throw CodexProblem(CodexIssue.INTERRUPTED)
            when (val event = codexTurnEvent(frame, turn.threadId, turn.id)) {
                CodexTurnEvent.Ignore -> Unit
                CodexTurnEvent.UnsupportedTool -> throw CodexProblem(CodexIssue.TOOL)
                is CodexTurnEvent.Delta -> {
                    turn.update(event.itemId, event.text, append = true)
                    content.value = turn.messages()
                }
                is CodexTurnEvent.Message -> {
                    turn.update(event.itemId, event.text, append = false)
                    content.value = turn.messages()
                }
                is CodexTurnEvent.End -> {
                    turn.terminal = true
                    if (event.status == "interrupted") throw CodexProblem(CodexIssue.INTERRUPTED)
                    if (event.status != "completed") throw CodexProblem(event.issue ?: CodexIssue.REQUEST)
                    content.value = turn.messages(complete = true)
                    return
                }
            }
        }
    }

    private suspend fun keyFor(config: OwnerSelection): String? = withContext(Dispatchers.IO) {
        val providerId = config.providerId ?: return@withContext null
        if (providers.provider(providerId)?.let(::isOfficialProvider) != true) return@withContext null
        providers.providerKey(providerId)?.takeIf { key -> key.length in 1..2_048 && key.all { it.code in 33..126 } }
    }

    private suspend fun loadSelection(): Boolean {
        if (loaded) return true
        return try {
            selection = withContext(Dispatchers.IO) { store.read() }
            loaded = true
            ready()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(CodexIssue.STORAGE)
        }
    }

    private suspend fun persist(config: OwnerSelection, publishFailure: Boolean = true): Boolean {
        return try {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.write(config) }
                selection = config
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (publishFailure) fail(CodexIssue.STORAGE) else false
        }
    }

    private fun checkStopped(op: Operation) {
        if (disposed.get() || op.stop.isCompleted) throw CodexProblem(CodexIssue.INTERRUPTED)
    }

    private fun ready() = publish(when {
        !launcher.available -> CodexOwnerPhase.UNAVAILABLE
        selection.providerId == null || selection.model == null -> CodexOwnerPhase.NEEDS_PROVIDER
        else -> CodexOwnerPhase.IDLE
    })

    private fun fail(issue: CodexIssue): Boolean {
        publish(if (issue == CodexIssue.ENGINE_UNAVAILABLE) CodexOwnerPhase.UNAVAILABLE else CodexOwnerPhase.FAILED,
            issue.userMessage)
        return false
    }

    private fun publish(phase: CodexOwnerPhase, error: String? = null) {
        mutable.value = CodexOwnerState(phase, selection.providerId, selection.model, selection.threadId, error)
    }

    private suspend fun exclusive(block: suspend () -> Boolean): Boolean = onMain {
        if (disposed.get() || !exclusive.tryLock()) return@onMain false
        try { block() } finally { exclusive.unlock() }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private class Operation {
        val stop = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }

    companion object {
        const val MAX_INPUT_CHARS = 2_000
        internal const val MAX_OUTPUT_CHARS = 8_000
        private const val MAX_TURN_MS = 90_000L
        internal val modelName = Regex("[A-Za-z0-9_.:-]{1,128}")

        /** No custom hosts, proxy URLs, OAuth providers, userinfo, or query overrides. */
        fun isOfficialProvider(provider: Provider): Boolean = provider.kind == ProviderConfig.Kind.OpenAiResponses &&
            provider.signIn == null && isOfficialEndpoint(provider.baseUrl)

        internal fun isOfficialEndpoint(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", true) && uri.host.equals("api.openai.com", true) &&
                uri.port in listOf(-1, 443) && uri.rawPath in listOf("/v1", "/v1/") &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }
}

/** Never fall back to a stale or partially read saved rule. This is not Codex automatic memory. */
internal suspend fun readCodexOwnerSnapshot(source: () -> String): String = withContext(Dispatchers.IO) {
    val snapshot = try {
        source()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw CodexProblem(CodexIssue.OWNER_INSTRUCTIONS)
    }
    if (snapshot.length > 8_000) throw CodexProblem(CodexIssue.OWNER_INSTRUCTIONS)
    snapshot.trim()
}

internal fun codexKeyFingerprint(key: String): String {
    val input = key.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
    } finally {
        input.fill(0)
    }
}

internal fun codexKeyMatchesThread(threadId: String?, storedFingerprint: String?, currentFingerprint: String): Boolean =
    threadId == null || storedFingerprint != null && storedFingerprint == currentFingerprint

private data class OwnerSelection(
    val providerId: String? = null,
    val model: String? = null,
    val threadId: String? = null,
    val keyFingerprint: String? = null,
)

private class OwnerSelectionStore(directory: File) {
    private val file = AtomicFile(File(directory, "calls-codex-selection.json"))

    fun read(): OwnerSelection {
        val bytes = try { file.openRead().use { it.readNBytesBounded() } }
        catch (e: FileNotFoundException) { if (file.baseFile.exists()) throw e else return OwnerSelection() }
        val parsed = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw CodexProblem(CodexIssue.STORAGE)
        if (parsed.num("version") != 1L || parsed.keys.any {
                it !in setOf("version", "providerId", "model", "threadId", "keyFingerprint") ||
                    it != "version" && parsed.str(it) == null
            }) throw CodexProblem(CodexIssue.STORAGE)
        val providerId = parsed.str("providerId")
        val model = parsed.str("model")
        val threadId = parsed.str("threadId")
        val keyFingerprint = parsed.str("keyFingerprint")
        if (providerId != null && !CodexWire.validId(providerId) ||
            model != null && !CodexOwnerRuntime.modelName.matches(model) ||
            (providerId == null) != (model == null) ||
            threadId != null && (providerId == null || !CodexWire.validId(threadId)) ||
            keyFingerprint != null && (threadId == null || !Regex("[0-9a-f]{64}").matches(keyFingerprint))) {
            throw CodexProblem(CodexIssue.STORAGE)
        }
        return OwnerSelection(providerId, model, threadId, keyFingerprint)
    }

    private fun java.io.InputStream.readNBytesBounded(): ByteArray {
        val bytes = ByteArray(1_024)
        var length = 0
        while (length < bytes.size) {
            val count = read(bytes, length, bytes.size - length)
            if (count == -1) return bytes.copyOf(length)
            length += count
        }
        throw CodexProblem(CodexIssue.STORAGE)
    }

    fun write(value: OwnerSelection) {
        val bytes = buildJsonObject {
            put("version", 1)
            value.providerId?.let { put("providerId", it) }
            value.model?.let { put("model", it) }
            value.threadId?.let { put("threadId", it) }
            value.keyFingerprint?.let { put("keyFingerprint", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        // AtomicFile does not throw when the final rename fails.
        if (read() != value) throw CodexProblem(CodexIssue.STORAGE)
    }
}

internal fun codexHistory(response: JsonObject): List<CodexOwnerMessage> {
    val turns = response.arr("data") ?: throw CodexProblem(CodexIssue.PROTOCOL)
    return turns.take(12).asReversed().flatMap { node ->
        val turn = node as? JsonObject ?: return@flatMap emptyList()
        val complete = turn.str("status") == "completed"
        turn.arr("items").orEmpty().take(32).mapNotNull { itemNode ->
            val item = itemNode as? JsonObject ?: return@mapNotNull null
            val id = item.str("id")?.takeIf(CodexWire::validId) ?: return@mapNotNull null
            when (item.str("type")) {
                "userMessage" -> CodexOwnerMessage(id, CodexOwnerRole.USER,
                    item.arr("content").orEmpty().asSequence().mapNotNull {
                        (it as? JsonObject)?.takeIf { part -> part.str("type") == "text" }?.str("text")
                    }.take(8).joinToString("\n").take(CodexOwnerRuntime.MAX_INPUT_CHARS), true)
                "agentMessage" -> CodexOwnerMessage(id, CodexOwnerRole.ASSISTANT,
                    item.str("text").orEmpty().take(CodexOwnerRuntime.MAX_OUTPUT_CHARS), complete)
                else -> null
            }?.takeIf { it.text.isNotBlank() }
        }
    }.takeLast(48)
}

private class ActiveTurn(
    val threadId: String,
    val id: String,
    private val before: List<CodexOwnerMessage>,
    input: String,
) {
    private val owner = CodexOwnerMessage("owner-${UUID.randomUUID()}", CodexOwnerRole.USER, input, true)
    private val parts = linkedMapOf<String, String>()
    private var streamedChars = 0
    var terminal = false

    fun update(itemId: String, text: String, append: Boolean) {
        if (!CodexWire.validId(itemId) || parts.size >= 16 && itemId !in parts) throw CodexProblem(CodexIssue.OUTPUT)
        if (append) {
            streamedChars += text.length
            if (streamedChars > CodexOwnerRuntime.MAX_OUTPUT_CHARS) throw CodexProblem(CodexIssue.OUTPUT)
        }
        val old = parts[itemId].orEmpty()
        val next = if (append) old + text else text
        if (parts.values.sumOf(String::length) - old.length + next.length > CodexOwnerRuntime.MAX_OUTPUT_CHARS) {
            throw CodexProblem(CodexIssue.OUTPUT)
        }
        parts[itemId] = next
    }

    fun messages(complete: Boolean = false) = (before + owner + parts.map { (key, value) ->
        CodexOwnerMessage(key, CodexOwnerRole.ASSISTANT, value, complete)
    }).takeLast(48)
}
