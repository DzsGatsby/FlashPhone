package com.wangzan101.mika.calls.remote

import android.content.Intent
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Phone-only outbound transport. This is not the old general-purpose MCP/desktop agent. */
class CallDeviceAgent(private val app: MikaApp) {
    data class State(val enrolled: Boolean = false, val enabled: Boolean = false, val connected: Boolean = false,
        val foreground: Boolean = false, val account: String = "", val name: String = "",
        val detail: String = "尚未连接设备账号", val busy: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = CallDeviceStore(File(app.noBackupFilesDir, "phone-remote/agent.enc"), KeystoreKeyCipher("flashphone-device-agent"))
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val socketHttp = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(60, TimeUnit.SECONDS).build()
    private val initialization = CompletableDeferred<Boolean>()
    private val commands = Mutex()
    private val uploads = Mutex()
    private var connection: Job? = null
    private var uploadJob: Job? = null
    private var uploadRequested = false
    private var socket: WebSocket? = null
    private var lastHello: String? = null
    private var service: CallDeviceAgentService? = null
    private var startRequest: String? = null
    private var identity: DeviceIdentity? = null
    private var sessionGeneration = 0L
    private val handles = mutableMapOf<String, RemoteCallHandle>()

    init {
        scope.launch { app.foreground.visible.collect { if (it) restoreAuthorizedConnection() } }
        scope.launch {
            val initialized = runCatching {
                val ledger = store.read()
                identity = ledger.identity
                store.recover()
                mutable.value = mutable.value.copy(enrolled = ledger.identity != null, enabled = ledger.enabled,
                    account = ledger.identity?.account.orEmpty(), name = ledger.identity?.name.orEmpty(),
                    detail = if (ledger.enabled) "远程任务已开启，等待后台连接" else "远程电话任务未开启")
            }.isSuccess
            if (!initialized) mutable.value = State(detail = "设备账号存储不可读，已禁止远程执行")
            initialization.complete(initialized)
        }
    }

    /** Called only from the visible phone UI or an eligible system restore receiver. */
    fun restoreAuthorizedConnection() {
        scope.launch {
            if (initialization.await() && mutable.value.enabled && identity != null && service == null) requestStart()
        }
    }
    suspend fun enroll(code: String, name: String): Boolean {
        if (!initialization.await() || mutable.value.busy) return false
        if (code.trim().length !in 4..128 || name.trim().length !in 1..64) {
            mutable.value = mutable.value.copy(detail = "请输入有效的一次性连接码和设备名称"); return false
        }
        if (mutable.value.enabled || handles.values.any { !it.state.value.terminal }) {
            mutable.value = mutable.value.copy(detail = "请先关闭远程任务并结束已有电话"); return false
        }
        mutable.value = mutable.value.copy(busy = true)
        return try {
            val result = request("/api/device-enroll", buildJsonObject {
                put("code", code.trim()); put("name", name.trim()); put("platform", "android")
                putJsonArray("capabilities") { CallDeviceProtocol.capabilities.forEach { add(it) } }
            })
            val id = result["device_id"]?.jsonPrimitive?.content.orEmpty()
            val token = result["device_token"]?.jsonPrimitive?.content.orEmpty()
            check(CallDeviceProtocol.validId(id) && token.length in 16..4096)
            val enrolled = DeviceIdentity(id, token, result["account_label"]?.jsonPrimitive?.content?.take(100) ?: "Flash phone", name.trim())
            store.enroll(enrolled)
            identity = enrolled
            mutable.value = State(enrolled = true, account = enrolled.account, name = enrolled.name,
                detail = "账号已连接；开启后，此账号的 Grok/Codex 可直接下发电话任务")
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(detail = "连接失败：请核对一次性连接码、网络和控制台地址"); false }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
    suspend fun setEnabled(enabled: Boolean): Boolean {
        if (!initialization.await() || identity == null) return false
        return withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (!enabled) {
                // Revoke live authority before any storage operation that can fail or suspend.
                mutable.value = mutable.value.copy(enabled = false, connected = false, detail = "远程电话任务已关闭")
                sessionGeneration++
                uploadJob?.cancel(); uploadJob = null
                http.dispatcher.cancelAll()
                connection?.cancel(); connection = null
                socket?.close(1000, "disabled"); socket = null
                service?.stopAgent()
            }
            try {
                store.enabled(enabled)
                if (enabled) {
                    mutable.value = mutable.value.copy(enabled = true, detail = "正在启动远程电话任务连接")
                    requestStart()
                }
                true
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(detail = if (enabled) "无法保存远程执行开关，未开启远程任务"
                    else "远程任务已在本机停止，但停用状态保存失败，请稍后核对")
                false
            } finally {
                if (!enabled) app.calls.stopRemoteWork()
            }
        }
    }
    suspend fun disconnectAccount(): Boolean {
        if (!setEnabled(false)) return false
        if (handles.values.any { !it.state.value.terminal }) {
            mutable.value = mutable.value.copy(detail = "AI 已交还系统，请结束该电话后再断开账号"); return false
        }
        return runCatching {
            store.forget(); identity = null; mutable.value = State(detail = "设备账号已断开"); true
        }.getOrDefault(false)
    }
    private fun requestStart() {
        if (service?.leaseAvailable == true || startRequest != null) return
        val id = UUID.randomUUID().toString()
        startRequest = id
        if (runCatching { app.startForegroundService(CallDeviceAgentService.startIntent(app, id)) }.getOrNull() == null) {
            startRequest = null
            mutable.value = mutable.value.copy(detail = "Android 未允许后台连接，请在应用内重新开启远程电话任务", foreground = false)
        }
    }
    internal fun startFailed() {
        startRequest = null
        mutable.value = mutable.value.copy(foreground = false, connected = false,
            detail = "后台连接未获允许，请在手机打开通知权限后恢复连接")
    }
    internal fun expectedStart(id: String?): Boolean = id != null && id == startRequest
    internal suspend fun serviceStarted(owner: CallDeviceAgentService): Boolean {
        if (!initialization.await() || !mutable.value.enabled || identity == null) return false
        service = owner; startRequest = null
        mutable.value = mutable.value.copy(foreground = true, detail = "正在连接设备账号")
        if (connection?.isActive != true) connection = scope.launch { connectionLoop() }
        return true
    }
    internal fun serviceStopped(owner: CallDeviceAgentService) {
        if (service !== owner) return
        service = null; sessionGeneration++
        uploadJob?.cancel(); uploadJob = null
        connection?.cancel(); connection = null
        socket?.cancel(); socket = null
        mutable.value = mutable.value.copy(foreground = false, connected = false,
            detail = if (mutable.value.enabled) "后台连接已停止，打开应用恢复" else "远程电话任务已关闭")
        scope.launch { app.calls.stopRemoteWork() }
    }
    private fun authorized(enrolled: DeviceIdentity): Boolean = mutable.value.enabled && identity === enrolled &&
        service?.leaseAvailable == true

    private suspend fun connectionLoop() {
        var retry = 2L
        while (currentCoroutineContext().isActive && mutable.value.enabled && service?.leaseAvailable == true) {
            val enrolled = identity ?: return
            try {
                app.calls.refresh()
                runSocket(enrolled)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Error details may contain transport credentials: fixed UI text only. */ }
            mutable.value = mutable.value.copy(connected = false, detail = "连接暂不可用，正在有界重连；不会重新拨号")
            if (!authorized(enrolled)) return
            delay(retry * 1_000L + Random.nextLong(0, 501))
            retry = (retry * 2).coerceAtMost(60)
        }
    }

    private sealed interface Incoming {
        data object Open : Incoming
        data object Revoked : Incoming
        data class Text(val value: String) : Incoming
    }
    private suspend fun runSocket(enrolled: DeviceIdentity) = coroutineScope {
        val incoming = Channel<Incoming>(32)
        fun push(message: Incoming) { if (incoming.trySend(message).isFailure) incoming.close(IOException("Device queue full")) }
        val ws = socketHttp.newWebSocket(Request.Builder()
            .url(CallDeviceProtocol.ORIGIN.replace("https://", "wss://") + "/api/device/socket")
            .header("User-Agent", "FlashPhoneAndroid/1.0")
            .header("Authorization", "Bearer ${enrolled.token}").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { push(Incoming.Open) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.toByteArray().size > CallDeviceProtocol.MAX_COMMAND_BYTES) {
                        webSocket.close(1009, "message_too_large"); incoming.close()
                    } else push(Incoming.Text(text))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 4003) push(Incoming.Revoked)
                    webSocket.close(code, null); incoming.close()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code in setOf(401, 403)) push(Incoming.Revoked)
                    response?.close(); incoming.close()
                }
            })
        socket = ws
        lastHello = null
        var helloConfirmed = false
        val recordingChanges = launch {
            availableDeviceRecordings(app.calls.history).collect {
                if (helloConfirmed) scheduleUpload(enrolled)
            }
        }
        val status = launch {
            app.calls.state.collect {
                if (helloConfirmed) { sendHello(ws); sendPending(ws); scheduleUpload(enrolled) }
            }
        }
        try {
            val exchanged = awaitDeviceHandshake {
                while (!helloConfirmed) {
                    when (val message = incoming.receive()) {
                        Incoming.Open -> sendHello(ws)
                        Incoming.Revoked -> { revoke(); return@awaitDeviceHandshake }
                        is Incoming.Text -> {
                            val response = CallDeviceProtocol.json.parseToJsonElement(message.value).jsonObject
                            if (response["type"]?.jsonPrimitive?.content == "hello_ack") {
                                check(response["device_id"]?.jsonPrimitive?.content == enrolled.deviceId &&
                                    response["protocol"]?.jsonPrimitive?.int == 1)
                                helloConfirmed = true
                            }
                        }
                    }
                }
            }
            if (!exchanged || !helloConfirmed) return@coroutineScope
            mutable.value = mutable.value.copy(connected = true, detail = "设备已在线，等待本账号电话任务")
            sendPending(ws)
            scheduleUpload(enrolled)
            for (message in incoming) when (message) {
                Incoming.Revoked -> { revoke(); break }
                Incoming.Open -> Unit
                is Incoming.Text -> {
                    val objectValue = CallDeviceProtocol.json.parseToJsonElement(message.value).jsonObject
                    when (objectValue["type"]?.jsonPrimitive?.content) {
                        "ack" -> store.ack(objectValue["task_id"]!!.jsonPrimitive.content, objectValue["sequence"]!!.jsonPrimitive.long)
                        "command" -> {
                            val command = runCatching { CallDeviceProtocol.command(message.value, enrolled.deviceId, System.currentTimeMillis()) }.getOrNull()
                            if (command != null) execute(command, enrolled)
                            sendPending(ws)
                        }
                    }
                }
            }
        } finally {
            status.cancel()
            recordingChanges.cancel()
            if (socket === ws) socket = null
            ws.cancel(); incoming.close()
        }
    }
    private suspend fun revoke() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        setEnabled(false)
        mutable.value = mutable.value.copy(detail = "此设备已被账号撤销，请重新连接")
    }
    private fun sendHello(ws: WebSocket) {
        val local = app.calls.state.value
        val config = app.calls.settings.state.value
        val ready = mutable.value.enabled && service?.leaseAvailable == true && config.enabled && local.prepared &&
            local.shellConnected && local.audioReady && local.notificationsAvailable && config.credentialsConfigured && local.phase == CallPhase.IDLE
        val message = buildJsonObject {
            put("v", 1); put("type", "hello")
            putJsonArray("capabilities") { CallDeviceProtocol.capabilities.forEach { add(it) } }
            putJsonObject("status") {
                put("ready", ready)
                put("reason", if (ready) "电话已就绪" else "请在手机完成电话配置，或等待当前通话结束")
                putJsonArray("sims") { local.simOptions.forEach { sim -> add(buildJsonObject {
                    put("id", sim.id); put("label", sim.label); put("default", sim.isDefault)
                }) } }
            }
        }.toString()
        if (message != lastHello && ws.send(message)) lastHello = message
    }
    private suspend fun sendPending(ws: WebSocket) {
        for (event in store.read().pending) if (!ws.send(CallDeviceProtocol.json.encodeToString(event))) break
    }
    private suspend fun execute(command: DeviceCommand, enrolled: DeviceIdentity) = commands.withLock {
        if (!authorized(enrolled)) return@withLock
        val fresh = runCatching { store.claim(command, System.currentTimeMillis()) }.getOrElse { return@withLock }
        if (!fresh) return@withLock
        if (command.action != "dial" && (System.currentTimeMillis() >= command.expires_at || !authorized(enrolled))) return@withLock
        if (command.action == "dial") {
            emit(command.task_id, "accepted")
            if (System.currentTimeMillis() >= command.expires_at || !authorized(enrolled)) {
                emit(command.task_id, "failed", result = buildJsonObject { put("reason", "任务过期或远程执行已关闭，未拨号") }); return@withLock
            }
            val payload = CallDeviceProtocol.json.decodeFromJsonElement<DialPayload>(command.payload)
            val generation = sessionGeneration
            val handle = try {
                app.calls.startRemoteDial(command.task_id, payload.number, payload.sim_id,
                    validatedDeviceTask(payload.task), command.expires_at) { authorized(enrolled) && sessionGeneration == generation }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                emit(command.task_id, "failed", result = buildJsonObject { put("reason", "该设备已有任务或无法安全接管，未重新拨号") })
                return@withLock
            }
            handles[command.task_id] = handle
            scope.launch { observe(handle, enrolled) }
        } else {
            // Persisted task identity cannot authorize touching a different local/system call.
            val handle = handles[command.task_id] ?: return@withLock
            val old = handle.state.value
            if (old.callId != command.expected_call_id || old.terminal) return@withLock
            app.calls.controlRemoteCall(command.task_id, command.expected_call_id, command.action == "hangup")
        }
    }
    private suspend fun emit(taskId: String, phase: String, snapshot: RemoteCallSnapshot? = null, result: JsonObject? = null) {
        store.event(taskId, phase, snapshot?.carrierEnded == true, snapshot?.aiActive == true,
            snapshot?.workId, snapshot?.callId, result)
        socket?.let { sendPending(it) }
    }
    private suspend fun observe(handle: RemoteCallHandle, enrolled: DeviceIdentity) {
        var lastPhase: String? = null
        try {
            observeDeviceCall(handle.state,
                onProgress = { snapshot ->
                    if (snapshot.phase != lastPhase) {
                        emit(handle.taskId, snapshot.phase, snapshot, snapshot.reason?.let { buildJsonObject { put("reason", it) } })
                        lastPhase = snapshot.phase
                    }
                },
                onCompleted = { snapshot ->
                    val record = awaitDeviceCallRecord(snapshot.workId, app.calls.history)
                    val transcript = buildJsonArray {
                        var budget = 40_000
                        record?.transcript?.forEach { line ->
                            val text = line.text.take(minOf(8_000, budget.coerceAtLeast(0) / 4))
                            if (text.isNotEmpty()) { add(buildJsonObject { put("role", line.role); put("text", text) }); budget -= text.toByteArray().size }
                        }
                    }
                    emit(handle.taskId, snapshot.phase, snapshot, buildJsonObject {
                        put("reason", snapshot.reason ?: record?.result ?: "任务已结束")
                        put("outcome", "needs_review")
                        put("summary", if (snapshot.carrierEnded) "Android 已确认电话结束；任务办理结果请核对转写" else "AI 任务已结束，运营商通话结束未确认")
                        put("transcript", transcript)
                    })
                    scheduleUpload(enrolled)
                },
                onFailure = {
                    mutable.value = mutable.value.copy(detail = "本地任务结果暂未送达，保留已有状态且不重新拨号")
                },
            )
        } finally {
            if (handle.state.value.terminal) {
                app.calls.releaseRemoteHandle(handle.taskId)
                handles.remove(handle.taskId)
            }
        }
    }

    private fun scheduleUpload(enrolled: DeviceIdentity) {
        if (!authorized(enrolled)) return
        uploadRequested = true
        if (uploadJob?.isActive == true) return
        uploadJob = scope.launch {
            while (uploadRequested && authorized(enrolled)) {
                uploadRequested = false
                uploadAvailable(enrolled)
            }
        }
    }

    private suspend fun uploadAvailable(enrolled: DeviceIdentity) {
        if (!authorized(enrolled) || !uploads.tryLock()) return
        try {
            for (task in store.read().tasks.filter { it.terminal && !it.recordingUploaded && it.workId != null && it.uploadAttempts < 3 }) {
                val workId = task.workId ?: continue
                val record = app.calls.history.value.find { it.id == workId } ?: continue
                if (record.recordingPending || record.recording == null) continue
                val file = app.calls.exportRecordingForRemote(workId) ?: continue
                try {
                    if (!authorized(enrolled) || file.length() !in 1..(40L * 1024 * 1024)) return
                    val digest = withContext(Dispatchers.IO) {
                        val hash = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                        }
                        hash.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (!authorized(enrolled)) return
                    if (app.calls.history.value.none { it.id == workId && it.recording?.id == workId }) continue
                    val response = uploadDeviceRecording({ authorized(enrolled) &&
                        app.calls.history.value.any { it.id == workId && it.recording?.id == workId } }) {
                        check(store.reserveUpload(task.id, digest))
                        request("/api/tasks/${task.id}/recording", body = file.asRequestBody("audio/wav".toMediaType()),
                            enrolled = enrolled, recordingHash = digest)
                    } ?: continue
                    emit(task.id, "recording_ready", result = buildJsonObject {
                        put("recording", response["recording"] ?: response)
                    })
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep local encrypted audio; retry upload only on a later connection/state event. */ }
                finally { app.calls.closeRemoteRecording(file) }
            }
        } finally { uploads.unlock() }
    }
    private suspend fun request(path: String, value: JsonObject? = null, body: RequestBody? = null,
        enrolled: DeviceIdentity? = null, recordingHash: String? = null): JsonObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(CallDeviceProtocol.ORIGIN + path)
            .header("User-Agent", "FlashPhoneAndroid/1.0")
            .post(body ?: checkNotNull(value).toString().toRequestBody("application/json".toMediaType()))
        enrolled?.let { request.header("Authorization", "Bearer ${it.token}") }
        recordingHash?.let { request.header("X-Recording-SHA256", it) }
        val call = http.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("电话控制面连接不可用"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        check(it.isSuccessful)
                        val source = checkNotNull(it.body).source()
                        check(!source.request(32 * 1024L + 1))
                        val data = source.readByteArray()
                        val objectValue = CallDeviceProtocol.json.parseToJsonElement(data.decodeToString()).jsonObject
                        if (continuation.isActive) continuation.resume(objectValue)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("电话控制面请求未成功"))
                    }
                }
            }
        })
    }
}
