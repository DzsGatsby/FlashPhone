package com.wangzan101.mika.calls

import android.content.Context
import com.wangzan101.mika.calls.codex.CodexOwnerRuntime
import com.wangzan101.mika.calls.codex.CodexSummarySelection
import com.wangzan101.mika.calls.codex.readCodexSummarySelection
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SummaryModelResponse(val text: String, val provider: String, val model: String) {
    override fun toString() = "SummaryModelResponse(model=$model)"
}

enum class SummaryModelFailure { NOT_CONFIGURED, CONFIGURATION, UNSUPPORTED, INPUT_LIMIT, OUTPUT_LIMIT, TIMEOUT, REQUEST }

/** No raw provider response, transcript, key, or cause is exposed to UI/logging. */
class SummaryModelException(val kind: SummaryModelFailure) : Exception("Call summary: ${kind.name.lowercase()}")

/** A single detached text request. Never opens or modifies the owner's Codex conversation. */
class CallSummaryModel internal constructor(
    private val providers: ProviderStore,
    private val summarySettings: CallSummarySettingsStore? = null,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val selectedCodex: () -> CodexSummarySelection?,
) {
    init { require(timeoutMs in 1..TIMEOUT_MS) }
    constructor(context: Context, providers: ProviderStore = ProviderStore.get(context),
        settings: CallSummarySettingsStore = CallSummarySettingsStore.get(context)) : this(
        providers, settings, TIMEOUT_MS, { readCodexSummarySelection(context.applicationContext.noBackupFilesDir) },
    )

    /** Local metadata only; a true result does not claim that credentials or model access were tested. */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        try { selection(); true } catch (_: SummaryModelException) { false }
    }

    suspend fun summarize(system: String, input: String): SummaryModelResponse {
        if (system.isBlank() || system.length > MAX_SYSTEM_CHARS || input.isBlank() || input.length > MAX_INPUT_CHARS) {
            throw SummaryModelException(SummaryModelFailure.INPUT_LIMIT)
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val chosen = selection()
                    val model = providers.summaryModel(chosen.providerId, chosen.model, MAX_OUTPUT_TOKENS, 25)
                        ?: throw SummaryModelException(SummaryModelFailure.UNSUPPORTED)
                    val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.Mika))
                    val reply = if (model is StreamingChatModel) {
                        model.completeStreaming(system, messages, emptyList()) {
                            if (it.length > MAX_OUTPUT_CHARS) throw SummaryModelException(SummaryModelFailure.OUTPUT_LIMIT)
                        }
                    } else model.complete(system, messages, emptyList())
                    if (reply.stopReason != StopReason.EndTurn || reply.parts.any { it is Part.ToolCall }) {
                        throw SummaryModelException(SummaryModelFailure.OUTPUT_LIMIT)
                    }
                    val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
                    if (text.isEmpty() || text.length > MAX_OUTPUT_CHARS) throw SummaryModelException(SummaryModelFailure.OUTPUT_LIMIT)
                    SummaryModelResponse(text, chosen.providerId, chosen.model)
                }
            } ?: throw SummaryModelException(SummaryModelFailure.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SummaryModelException) {
            throw e
        } catch (_: Exception) {
            throw SummaryModelException(SummaryModelFailure.REQUEST)
        }
    }

    private fun selection(): CodexSummarySelection {
        if (summarySettings?.error?.value == true) throw SummaryModelException(SummaryModelFailure.CONFIGURATION)
        val explicit = summarySettings?.state?.value?.let { settings ->
            settings.providerId?.let { CodexSummarySelection(it, checkNotNull(settings.model)) }
        }
        // Production owns a dedicated selection. An empty selection must match the visible settings UI.
        if (summarySettings != null && explicit == null) throw SummaryModelException(SummaryModelFailure.NOT_CONFIGURED)
        val codex = if (explicit != null) null else try { selectedCodex() } catch (_: Exception) {
            throw SummaryModelException(SummaryModelFailure.CONFIGURATION)
        }
        val selected = explicit ?: codex ?: providers.configFor(ModelRole.Light)?.let { CodexSummarySelection(it.providerId, it.model) }
            ?: throw SummaryModelException(SummaryModelFailure.NOT_CONFIGURED)
        val p = providers.provider(selected.providerId) ?: throw SummaryModelException(SummaryModelFailure.CONFIGURATION)
        if (codex != null && (!CodexOwnerRuntime.isOfficialProvider(p) || !providers.hasKey(p.id))) {
            throw SummaryModelException(SummaryModelFailure.CONFIGURATION)
        }
        if (!supportedProvider(p)) throw SummaryModelException(SummaryModelFailure.UNSUPPORTED)
        return selected
    }

    companion object {
        /** Plan endpoints reject the required token cap. API-key providers remain selectable. */
        fun supportedProvider(provider: Provider): Boolean = provider.kind != ProviderConfig.Kind.ChatGpt
        const val MAX_SYSTEM_CHARS = 4_000
        // Includes JSON escaping of the bounded saved transcript and task, not extra history.
        const val MAX_INPUT_CHARS = 64_000
        const val MAX_OUTPUT_CHARS = 6_000
        const val MAX_OUTPUT_TOKENS = 1_536
        const val TIMEOUT_MS = 30_000L
    }
}
