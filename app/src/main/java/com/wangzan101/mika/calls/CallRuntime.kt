package com.wangzan101.mika.calls

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class CallActionResult(val ok: Boolean, val message: String)

data class CallRuntimeState(
    val prepared: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val direction: CallDirection? = null,
    val callId: String? = null,
    val number: String? = null,
    val aiActive: Boolean = false,
    val detail: String? = null,
    val pendingAutoAnswer: Boolean = false,
    val simOptions: List<CallSim> = emptyList(),
    val shellConnected: Boolean = false,
    val notificationsAvailable: Boolean = false,
    val audioReady: Boolean = false,
    val audioDetail: String? = null,
    val canAnswer: Boolean = false,
    val canHangUp: Boolean = false,
    val canTakeOver: Boolean = false,
) {
    override fun toString(): String =
        "CallRuntimeState(prepared=$prepared, phase=$phase, direction=$direction, aiActive=$aiActive, pendingAutoAnswer=$pendingAutoAnswer)"
}

/** Owns one AI attempt at a time; the default dialer continues to own all carrier calls. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CallRuntime(private val app: MikaApp) {
    val settings = CallSettings.create(app)
    private val access = CallTelecomAccess(app)
    private val telecom get() = app.getSystemService(TelecomManager::class.java)
    private val telephony get() = app.getSystemService(TelephonyManager::class.java)
    private val notifications = CallNotifications(app)
    private val records = CallHistory(app)
    val history = records.state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accessLock = Mutex()
    private val mutable = MutableStateFlow(CallRuntimeState())
    val state = mutable.asStateFlow()
    private val changes = MutableStateFlow(0L)
    private val tracked = linkedMapOf<Call, Tracked>()
    private var sims: List<CarrierSim> = emptyList()
    private var shell: IMikaShell? = null
    private var armed = false
    private var configuring = false
    private var detail: String? = null
    private var work: Work? = null
    private var audioReady = false
    private var audioDetail: String? = null
    private var probeEpoch = 0
    private var unobservedManagedCall = false


    init {
        scope.launch {
            app.phone.hub.shell.collect { current ->
                if (shell != null && shell !== current) cancelWork("内置助手已断开，电话由系统处理")
                shell = current
                audioReady = false
                audioDetail = null
                publish()
                if (settings.state.value.enabled && current != null) probeAudio()

            }
        }
        scope.launch {
            var previousMode: CallAudioMode? = null
            settings.state.collect { config ->
                val active = work
                if (active != null && (!config.enabled || !config.credentialsConfigured ||
                        config.audioMode != active.config.audioMode ||
                        (active.auto && !active.answerSent && !CallPolicy.callerAllowed(config, active.track?.number)))) {
                    cancelWork("AI 电话设置已变更，电话由系统处理")
                }
                if (config.audioMode != previousMode) {
                    audioReady = false
                    audioDetail = null
                    previousMode = config.audioMode
                    publish()
                    if (config.enabled && shell != null) probeAudio()
                }
                publish()
            }
        }
        scope.launch {
            var previous = settings.credentialsRevision.value
            settings.credentialsRevision.collect { current ->
                if (previous != current) cancelWork("火山凭据已变更，电话由系统处理")
                previous = current
            }
        }
        scope.launch { records.load() }
        scope.launch {
            accessLock.withLock { armed = withContext(Dispatchers.IO) { access.ready() } }
            readSims()
            publish()
        }
    }

    suspend fun refresh(): CallActionResult = onMain {
        accessLock.withLock { armed = withContext(Dispatchers.IO) { access.ready() } }
        readSims()
        if (settings.state.value.enabled && work == null && liveCalls().isEmpty()) probeAudio()
        unobservedManagedCall = settings.state.value.enabled && liveCalls().isEmpty() && managedCallPresent() == true
        publish()
        if (state.value.prepared) ok("系统通话管理权限可用；连接状态以实际电话回调为准")
        else fail("AI 电话尚未准备好")
    }

    suspend fun prepare(): CallActionResult = onMain {
        accessLock.withLock {
            if (!settings.state.value.enabled) return@withLock report("请先开启 AI 电话")
            if (work != null || liveCalls().isNotEmpty()) return@withLock report("请在没有通话时准备 AI 电话")
            if (!access.supported) return@withLock report("电话伴侣模式需要 Android 12 及以上、主用户及运营商通话功能")
            if (!access.hasPhonePermissions()) return@withLock report("请允许 Mika 的电话权限")
            if (managedCallPresent() != false) return@withLock report("Android 正在通话或无法确认空闲，请稍后再准备")
            val helper = shell ?: return@withLock report("请先连接 Mika 内置助手")
            configuring = true
            publish()
            try {
                // The small transaction must run its rollback even when its UI caller leaves.
                val error = withContext(NonCancellable + Dispatchers.IO) { access.prepare(helper) }
                if (!settings.state.value.enabled) {
                    withContext(NonCancellable + Dispatchers.IO) { access.rollback(helper) }
                    armed = false
                    return@withLock report("AI 电话已关闭")
                }
                armed = error == null && access.ready()
                readSims()
                if (error != null) return@withLock report(error)
                probeAudio()
                detail = "电话伴侣已配置；机型的通话音频能力仍需单独验证"
                ok(detail.orEmpty())
            } finally {
                configuring = false
                publish()
            }
        }
    }

    suspend fun stop(): CallActionResult = onMain {
        accessLock.withLock {
            armed = false
            configuring = true
            val active = cancelWork("AI 电话已关闭，电话由系统处理")
            publish()
            val saved: Boolean
            val audioRestored: Boolean
            val restored: Boolean
            try {
                val stopped = withContext(NonCancellable) {
                    val savedSettings = withContext(Dispatchers.IO) {
                        runCatching { settings.save(settings.state.value.copy(enabled = false, autoAnswer = false)) }.isSuccess
                    }
                    val cleanedAudio = awaitCleanup(active)
                    val restoredAccess = withContext(Dispatchers.IO) { access.rollback(shell) }
                    Triple(savedSettings, cleanedAudio, restoredAccess)
                }
                saved = stopped.first
                audioRestored = stopped.second
                restored = stopped.third
            } finally {
                configuring = false
                publish()
            }
            if (!saved || !restored || !audioRestored) {
                report("AI 已停止；系统配置或音频尚未确认还原，请连接内置助手后重试关闭")
            } else {
                detail = "AI 电话已关闭，系统拨号器照常工作"
                publish()
                ok(detail.orEmpty())
            }
        }
    }

    suspend fun dial(number: String, simId: String? = null): CallActionResult = onMain {
        prerequisite()?.let { return@onMain report(it) }
        val normalized = CallPolicy.outgoingNumber(number) ?: return@onMain report("仅支持普通号码，不支持紧急号码、短号或控制代码")
        if (isEmergency(normalized) != false) return@onMain report("紧急号码或无法确认安全的号码不能拨打")
        if (liveCalls().isNotEmpty() || work != null || managedCallPresent() != false) {
            return@onMain report("已有通话或无法确认空闲，未拨号")
        }
        readSims()
        prerequisite()?.let { return@onMain report(it) }
        if (liveCalls().isNotEmpty() || work != null || managedCallPresent() != false) {
            return@onMain report("已有通话或无法确认空闲，未拨号")
        }
        val picked = CallPolicy.chooseSim(sims.map { it.info }, simId)
            ?: return@onMain report(if (sims.isEmpty()) "没有可用于运营商通话的 SIM"
                else "请选择一张有效的 SIM，或明确选择系统默认 SIM")
        val sim = sims.single { it.info.id == picked.id }
        val attempt = Work(CallDirection.OUTGOING, settings.state.value, requestedNumber = normalized, sim = sim)
        startWork(attempt)
        awaitResult(attempt)
    }

    suspend fun answer(expectedCallId: String? = null): CallActionResult = onMain {
        prerequisite()?.let { return@onMain report(it) }
        val call = liveCalls().singleOrNull()
        if (expectedCallId != null && call?.id != expectedCallId) return@onMain report(CALL_CHANGED)
        if (call == null || call.phase != CallPhase.RINGING || call.suppressed || !carrierCall(call.call)) {
            return@onMain report("没有可接听的单路运营商来电")
        }
        work?.let {
            if (it.track === call && it.direction == CallDirection.INCOMING) return@onMain awaitResult(it)
            return@onMain report("已有 AI 通话正在进行")
        }
        val attempt = Work(CallDirection.INCOMING, settings.state.value, track = call, expectedCallId = expectedCallId)
        startWork(attempt)
        awaitResult(attempt)
    }

    suspend fun hangup(expectedCallId: String? = null): CallActionResult = onMain {
        if (!settings.state.value.enabled || !armed) return@onMain report("AI 电话尚未准备好")
        val call = liveCalls().singleOrNull()
        if (expectedCallId != null && call?.id != expectedCallId) return@onMain report(CALL_CHANGED)
        if (call == null || call.phase == CallPhase.DISCONNECTING || !carrierCall(call.call)) {
            return@onMain report("没有可挂断的单路运营商通话")
        }
        call.suppressed = true
        val active = cancelWork("用户挂断")
        if (!isCurrentCall(call, expectedCallId) || call.phase == CallPhase.DISCONNECTING) return@onMain report(CALL_CHANGED)
        if (runCatching {
                if (call.phase == CallPhase.RINGING) call.call.reject(false, null) else call.call.disconnect()
            }.isFailure) return@onMain report("Android 未能发出挂断请求")
        val ended = waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(call) } || confirmedEnded(call)
        awaitCleanup(active)
        if (ended) ok("Android 已确认通话结束") else report("Android 尚未确认通话结束，请查看系统电话界面")
    }

    suspend fun takeover(): CallActionResult = takeover(null)

    internal suspend fun takeover(callId: String?): CallActionResult = onMain {
        val current = work
        if (current == null || (callId != null && callId != current.id)) return@onMain fail("这次 AI 通话已结束")
        current.track?.suppressed = true
        val active = cancelWork(if (current.pendingRing) "已取消 AI 接听，电话继续响铃" else "已停止 AI，通话交给系统拨号器")
        if (awaitCleanup(active)) ok(detail ?: "已停止 AI")
        else report("AI 已停止，通话音频恢复尚未确认，请查看系统电话界面")
    }

    internal fun onCallAdded(call: Call) {
        unobservedManagedCall = false
        tracked.getOrPut(call) { Tracked(call) }
        onCallChanged(call)
    }

    internal fun onCallChanged(call: Call) {
        val t = tracked[call] ?: return
        val active = work
        if (active != null) {
            if (active.track == null && active.placed && matchesOutgoing(active, t)) {
                active.track = t
            }
            val target = active.track
            if (target === t && active.direction == CallDirection.OUTGOING &&
                call.state in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE)) {
                active.result.complete(ok("Android 已确认拨号；是否接通请查看实时状态"))
            }
            when {
                liveCalls().size > 1 -> cancelWork("检测到多路通话，已交还系统拨号器")
                target === t && disconnected(call) -> cancelWork(callEndMessage(t))
                target === t && t.phase == CallPhase.ACTIVE && active.direction == CallDirection.INCOMING && !active.answerSent ->
                    cancelWork("已由系统电话接听，AI 未介入")
                target === t && t.phase != CallPhase.ACTIVE && active.bridging ->
                    cancelWork("通话已切换，AI 音频已停止")
            }
        } else if (t.phase == CallPhase.RINGING && !t.suppressed && !t.autoTried && liveCalls().singleOrNull() === t &&
            prerequisite() == null && carrierCall(t.call) && CallPolicy.callerAllowed(settings.state.value, t.number)) {
            t.autoTried = true
            startWork(Work(CallDirection.INCOMING, settings.state.value, track = t, auto = true))
        }
        publish()
    }

    internal fun onCallRemoved(call: Call) {
        val removed = tracked.remove(call) ?: return
        if (work?.track === removed) cancelWork(
            if (confirmedEnded(removed)) callEndMessage(removed) else "Android 通话状态已断开，电话由系统处理",
        )
        publish()
    }

    private fun startWork(attempt: Work) {
        check(work == null)
        work = attempt
        detail = if (attempt.auto) "等待自动接听，可随时取消" else "正在准备 AI 电话"
        attempt.job = scope.launch(start = CoroutineStart.LAZY) { runWork(attempt) }
        publish()
        attempt.job?.start()
    }

    private suspend fun runWork(w: Work) {
        try {
            if (!notifications.show(w.id, detail.orEmpty(), ringing = w.direction == CallDirection.INCOMING)) {
                throw CallProblem("需要打开 AI 电话通知，才能随时取消或接管")
            }
            if (w.auto) delay(CallPolicy.AUTO_DELAY_MS)
            prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
            validateTarget(w)
            val helper = shell ?: throw CallProblem("Mika 内置助手未连接")
            val credentials = withContext(Dispatchers.IO) { settings.credentials() }
                ?: throw CallProblem("火山凭据不可用，请重新保存")
            if (!probeAudio()) throw CallProblem(audioDetail ?: "此设备的双向通话音频不可用")
            if (shell !== helper || w.config.audioMode != settings.state.value.audioMode) {
                throw CallProblem("通话音频设置已变化，请重试")
            }
            validateTarget(w)
            val session = withTimeoutOrNull(5_000L) {
                VolcRealtimeClient().connect(credentials, VolcRealtimeConfig(
                    systemPrompt = CallPolicy.prompt(w.config.systemPrompt, w.direction), speaker = w.config.speaker,
                ))
            } ?: throw CallProblem("火山实时语音连接超时，未由 AI 接管")
            w.session = session
            if (!session.setMuted(true)) throw CallProblem("火山实时语音未进入就绪状态")
            if (!withContext(Dispatchers.IO) { access.ready() }) {
                armed = false
                throw CallProblem("系统通话管理权限不可用，未由 AI 操作电话")
            }
            prerequisite(ignoreWork = true)?.let { throw CallProblem(it) }
            validateTarget(w)
            if (w.direction == CallDirection.INCOMING) {
                val target = w.track ?: throw CallProblem("来电已结束")
                w.answerSent = true
                w.telecomActionIssued = true
                if (runCatching { target.call.answer(VideoProfile.STATE_AUDIO_ONLY) }.isFailure) {
                    throw CallProblem("Android 未能发出接听请求")
                }
            } else {
                if (liveCalls().isNotEmpty() || managedCallPresent() != false) throw CallProblem("已有通话或无法确认空闲，未拨号")
                val sim = w.sim ?: throw CallProblem("没有可用的 SIM")
                val number = w.requestedNumber ?: throw CallProblem("没有可用的电话号码")
                if (isEmergency(number) != false) throw CallProblem("无法确认此号码可安全拨打")
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(TOKEN, w.id) })
                }
                w.placed = true
                w.telecomActionIssued = true
                if (runCatching { checkNotNull(telecom).placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras) }.isFailure) {
                    throw CallProblem("Android 拒绝拨号")
                }
                if (!waitFor(CallPolicy.DIAL_TIMEOUT_MS) { w.track != null }) {
                    throw CallProblem("未收到该号码的 Android 通话回调，请查看系统拨号器")
                }
            }
            val limit = if (w.direction == CallDirection.INCOMING) CallPolicy.CONTROL_TIMEOUT_MS else 55_000L
            if (!waitFor(limit) { w.track?.phase == CallPhase.ACTIVE }) {
                throw CallProblem("Android 未确认通话接通，电话由系统处理")
            }
            val target = w.track ?: throw CallProblem("通话已结束")
            if (!carrierCall(target.call) || liveCalls().singleOrNull() !== target) {
                throw CallProblem("当前通话无法由 AI 安全接管")
            }
            w.activeAt = System.currentTimeMillis()
            w.activeElapsed = SystemClock.elapsedRealtime()
            w.bridging = true
            detail = "已接通，正在建立双向音频"
            publish()
            runAudio(w, helper, session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CallProblem) {
            w.stopReason = e.message ?: "AI 电话未就绪"
        } catch (e: CallMediaFailure) {
            w.stopReason = e.message ?: "通话音频已断开"
        } catch (e: VolcRealtimeException) {
            w.stopReason = when (e.kind) {
                VolcRealtimeException.Kind.AUTH -> "火山拒绝认证，请检查电话语音凭据和服务开通状态"
                VolcRealtimeException.Kind.RATE_LIMITED -> "火山实时语音达到服务限额，电话由系统处理"
                VolcRealtimeException.Kind.TIMEOUT, VolcRealtimeException.Kind.NETWORK -> "火山实时语音连接不可用，电话由系统处理"
                VolcRealtimeException.Kind.BACKPRESSURE -> "火山实时音频拥塞，电话由系统处理"
                else -> "火山实时语音未能继续，请检查通话配置"
            }
        } catch (_: Exception) {
            w.stopReason = "AI 音频连接失败，电话由系统处理"
        } finally {
            withContext(NonCancellable) {
                val reason = w.stopReason ?: "AI 已退出，电话由系统处理"
                w.session?.close()
                val restored = restoreAudio(w)
                w.track?.let {
                    if (w.telecomActionIssued) {
                        val record = CallRecord(w.id, it.number, w.direction, w.activeAt ?: it.createdAt,
                            System.currentTimeMillis(), reason, w.transcript.toList())
                        scope.launch { records.append(record) }
                    }
                }
                if (work === w) {
                    work = null
                    detail = if (restored) reason else "$reason；音频恢复尚未确认"
                    if (w.telecomActionIssued && w.track?.phase == CallPhase.ACTIVE) notifications.handoff()
                    else notifications.clear()
                    publish()
                }
                w.result.complete(fail(detail ?: reason))
                w.cleaned.complete(restored)
            }
        }
    }

    private suspend fun probeAudio(): Boolean {
        val helper = shell ?: return false
        val mode = settings.state.value.audioMode
        val epoch = ++probeEpoch
        val probe = withContext(Dispatchers.IO) { runCatching { helper.probeCallAudio(mode.wire) }.getOrNull() }
        if (helper !== shell || mode != settings.state.value.audioMode) return false
        val ready = probe?.getBoolean("ready") == true
        if (epoch == probeEpoch) {
            audioReady = ready
            audioDetail = if (ready) "音频路径具备运行条件，仍需双向实机验证"
                else probe?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "内置助手无法检测电话音频"
            publish()
        }
        return ready
    }

    private suspend fun runAudio(w: Work, helper: IMikaShell, session: VolcRealtimeSession) {
        val notification = "AI 已接通，可随时手动接管"
        if (!notifications.show(w.id, "正在连接通话音频", ringing = false)) {
            throw CallProblem("电话通知不可用，已交还系统")
        }
        // Capture is never started while ringing, dialing, held, or in a different call.
        val target = w.track ?: throw CallProblem("通话已结束")
        if (liveCalls().singleOrNull() !== target || target.phase != CallPhase.ACTIVE ||
            target.suppressed || shell !== helper) throw CallProblem("通话状态已变更，AI 未接入音频")
        w.audioHelper = helper
        val channel = withContext(NonCancellable + Dispatchers.IO) {
            val result = helper.openCallAudio(w.config.audioMode.wire, w.audioOwner)
            if (result?.getBoolean("ready") != true) {
                throw CallProblem(result?.getString("reason")?.take(160)?.takeIf { it.isNotBlank() } ?: "此设备未建立双向电话音频")
            }
            val down = result.fd("downlink")
            val up = result.fd("uplink")
            if (down == null || up == null ||
                result.getInt("captureRate") != VolcRealtimeClient.INPUT_SAMPLE_RATE ||
                result.getInt("playRate") != VolcRealtimeClient.OUTPUT_SAMPLE_RATE) {
                runCatching { down?.close() }
                runCatching { up?.close() }
                throw CallProblem("内置助手的电话音频格式不兼容")
            }
            val capture = ParcelFileDescriptor.AutoCloseInputStream(down)
            val playback = ParcelFileDescriptor.AutoCloseOutputStream(up)
            w.capture = capture
            w.playback = playback
            CallPcmBridge(capture, playback) {
                withContext(Dispatchers.IO) { runCatching { helper.flushCallAudio(w.audioOwner) }.getOrDefault(false) }
            }
        }
        if (liveCalls().singleOrNull() !== target || target.phase != CallPhase.ACTIVE || target.suppressed ||
            shell !== helper || !settings.state.value.enabled) throw CallProblem("通话已变更，未发送语音")
        val greeting = w.config.greeting.let {
            if (it.startsWith("您好，我是 Mika 的 AI 电话助手。")) it else "您好，我是 Mika 的 AI 电话助手。$it"
        }
        val remaining = (w.config.maxCallSeconds * 1_000L - (SystemClock.elapsedRealtime() - w.activeElapsed)).coerceAtLeast(1)
        val completed = withTimeoutOrNull(remaining) {
            coroutineScope {
                val watchdog = launch {
                    var lastBytes = -1L
                    var stalled = 0
                    while (true) {
                        delay(if (w.aiActive) 1_500 else 350)
                        val stats = withContext(Dispatchers.IO) {
                            runCatching { helper.callAudioStats(w.audioOwner) }.getOrNull()
                        }
                        if (stats?.getBoolean("active") != true) {
                            throw CallMediaFailure("设备已停止双向音频，电话由系统处理")
                        }
                        val bytes = stats.getLong("downBytes")
                        if (!w.aiActive) {
                            if (bytes > 0 && stats.getLong("upBytes") > 0) {
                                if (!notifications.show(w.id, notification, ringing = false)) {
                                    throw CallProblem("电话通知不可用，已交还系统")
                                }
                                w.aiActive = true
                                detail = notification
                                if (w.direction == CallDirection.INCOMING) {
                                    w.result.complete(ok("Android 已接听，双向音频桥已开始传输"))
                                }
                                publish()
                            } else if (SystemClock.elapsedRealtime() - w.activeElapsed >= 7_000) {
                                throw CallMediaFailure("双向音频未能及时建立，电话由系统处理")
                            }
                        } else {
                            stalled = if (lastBytes == bytes) stalled + 1 else 0
                            if (stalled >= 2) throw CallMediaFailure("运营商音频输入无进展，电话由系统处理")
                        }
                        lastBytes = bytes
                    }
                }
                try {
                    channel.run(session, greeting, onTranscript = { role, text ->
                        val line = text.trim().take(CallHistory.MAX_LINE_LENGTH)
                        if (line.isNotEmpty() && w.transcript.lastOrNull() != CallTranscriptLine(role, line)) {
                            if (w.transcript.size >= CallHistory.MAX_LINES) w.transcript.removeAt(0)
                            w.transcript += CallTranscriptLine(role, line)
                        }
                    })
                } finally {
                    watchdog.cancel()
                }
                true
            }
        }
        if (completed != true) {
            w.stopReason = "已达到 AI 通话时长上限"
            w.session?.close()
            val restored = restoreAudio(w)
            w.aiActive = false
            w.bridging = false
            if (!restored || target.suppressed || liveCalls().singleOrNull() !== target || target.phase != CallPhase.ACTIVE) return
            if (runCatching { target.call.disconnect() }.isSuccess &&
                (waitFor(CallPolicy.CONTROL_TIMEOUT_MS) { confirmedEnded(target) } || confirmedEnded(target))) {
                w.stopReason = "达到时长上限，Android 已挂断"
            } else {
                w.stopReason = "达到时长上限，Android 未确认挂断，已交还系统"
            }
        }
    }

    private suspend fun restoreAudio(w: Work): Boolean = withContext(Dispatchers.IO) {
        val helper = w.audioHelper
        runCatching { w.capture?.close() }
        runCatching { w.playback?.close() }
        w.capture = null
        w.playback = null
        if (helper == null) return@withContext true
        runCatching {
            helper.stopCallAudio(w.audioOwner)
            val stats = helper.callAudioStats(w.audioOwner) ?: return@runCatching false
            !stats.getBoolean("active") && stats.getBoolean("microphoneRestored")
        }.getOrDefault(false).also {
            if (it) w.audioHelper = null
        }
    }

    private val CallAudioMode.wire: Int get() = if (this == CallAudioMode.DIRECT) 1 else 2

    private fun Bundle.fd(key: String): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, ParcelFileDescriptor::class.java)
        else getParcelable(key)

    private fun validateTarget(w: Work) {
        if (w.direction == CallDirection.INCOMING) {
            val t = w.track
            if (t == null || !isCurrentCall(t, w.expectedCallId) || t.phase != CallPhase.RINGING ||
                t.suppressed || !carrierCall(t.call) || (w.auto && !CallPolicy.callerAllowed(settings.state.value, t.number))) {
                throw CallProblem("来电已变化，AI 未接听")
            }
        } else if (liveCalls().isNotEmpty()) throw CallProblem("已有通话，未拨号")
    }

    private fun isCurrentCall(target: Tracked, expectedCallId: String?): Boolean =
        liveCalls().singleOrNull() === target && (expectedCallId == null || target.id == expectedCallId)

    private fun prerequisite(ignoreWork: Boolean = false): String? = when {
        !settings.state.value.enabled -> "AI 电话已关闭"
        configuring -> "AI 电话正在配置"
        !armed || !access.hasPhonePermissions() -> "请先准备 AI 电话"
        shell == null -> "Mika 内置助手未连接"
        !settings.state.value.credentialsConfigured -> "请先配置火山实时语音凭据"
        !notifications.ready() -> "请允许 AI 电话通知，以便取消或接管"
        !ignoreWork && work?.direction == CallDirection.OUTGOING -> "已在处理另一通电话"
        else -> null
    }

    private fun cancelWork(reason: String): Work? {
        val active = work ?: return null
        if (active.stopReason == null) active.stopReason = reason
        active.track?.suppressed = true
        active.job?.cancel()
        publish()
        return active
    }

    private suspend fun awaitCleanup(w: Work?): Boolean =
        w == null || withTimeoutOrNull(CallPolicy.CONTROL_TIMEOUT_MS) { w.cleaned.await() } == true

    private suspend fun awaitResult(w: Work): CallActionResult = try {
        w.result.await()
    } catch (e: CancellationException) {
        if (!w.telecomActionIssued && !w.auto && work === w) cancelWork("操作已取消，电话由系统处理")
        throw e
    }

    private suspend fun waitFor(timeout: Long, test: () -> Boolean): Boolean =
        withTimeoutOrNull(timeout) { changes.first { test() }; true } == true

    private fun publish() {
        val enabled = settings.state.value.enabled
        val calls = if (enabled) liveCalls() else emptyList()
        val current = calls.firstOrNull()
        val active = work.takeIf { enabled }
        mutable.value = CallRuntimeState(
            prepared = enabled && armed && !configuring,
            phase = current?.phase ?: when {
                active?.direction == CallDirection.OUTGOING -> CallPhase.DIALING
                enabled && unobservedManagedCall -> CallPhase.UNKNOWN
                else -> CallPhase.IDLE
            },
            direction = current?.direction ?: active?.direction,
            callId = calls.singleOrNull()?.id,
            number = current?.number ?: active?.requestedNumber,
            aiActive = active?.aiActive == true,
            detail = if (enabled && unobservedManagedCall && current == null) "Android 正在通话，但 Mika 尚未收到该通话的回调" else detail,
            pendingAutoAnswer = active?.auto == true && active.pendingRing,
            simOptions = sims.map { it.info },
            shellConnected = shell != null,
            notificationsAvailable = enabled && notifications.ready(),
            audioReady = enabled && audioReady,
            audioDetail = audioDetail,
            canAnswer = enabled && armed && current?.phase == CallPhase.RINGING && !current.suppressed &&
                calls.size == 1 && carrierCall(current.call),
            canHangUp = enabled && armed && current != null && current.phase != CallPhase.DISCONNECTING &&
                calls.size == 1 && carrierCall(current.call),
            canTakeOver = active != null,
        )
        changes.value++
    }

    private fun report(message: String): CallActionResult {
        detail = message
        publish()
        return fail(message)
    }

    private fun liveCalls() = tracked.values.filter { it.call.state != Call.STATE_DISCONNECTED }

    private fun confirmedEnded(t: Tracked): Boolean = t.call.state == Call.STATE_DISCONNECTED ||
        (t.call !in tracked && managedCallPresent() == false)

    private fun managedCallPresent(): Boolean? = runCatching { telecom?.isInManagedCall }.getOrNull()

    private fun isEmergency(number: String): Boolean? = runCatching { telephony?.isEmergencyNumber(number) }.getOrNull()

    private fun carrierCall(call: Call): Boolean {
        val d = call.details ?: return false
        if (d.videoState != VideoProfile.STATE_AUDIO_ONLY ||
            d.callProperties and (Call.Details.PROPERTY_SELF_MANAGED or Call.Details.PROPERTY_IS_EXTERNAL_CALL or
                Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL or Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE or
                Call.Details.PROPERTY_CONFERENCE or Call.Details.PROPERTY_RTT) != 0 ||
            call.parent != null || call.children.isNotEmpty()) return false
        if (d.handle?.scheme?.let { it != PhoneAccount.SCHEME_TEL } == true) return false
        val handle = d.accountHandle ?: return false
        if (subscription(handle) == null) return false
        val number = numberOf(call)
        return number == null || isEmergency(number) == false
    }

    private fun matchesOutgoing(w: Work, tracked: Tracked): Boolean =
        tracked.direction == CallDirection.OUTGOING && tracked.number == w.requestedNumber &&
            tracked.call.details?.accountHandle == w.sim?.handle &&
            runCatching { tracked.call.details?.intentExtras?.getString(TOKEN) == w.id }.getOrDefault(false)

    private suspend fun readSims() {
        sims = if (!access.hasPhonePermissions()) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                val service = telecom ?: return@runCatching emptyList()
                val preferred = service.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
                val subscriptionService = app.getSystemService(SubscriptionManager::class.java)
                service.callCapablePhoneAccounts.mapNotNull { handle ->
                    val id = subscription(handle) ?: return@mapNotNull null
                    val sim = subscriptionService?.getActiveSubscriptionInfo(id) ?: return@mapNotNull null
                    CarrierSim(CallSim("sim-$id", sim.displayName?.toString()?.take(80)
                        ?.ifBlank { null } ?: "SIM ${sim.simSlotIndex + 1}", handle == preferred), handle)
                }.distinctBy { it.info.id }
            }.getOrDefault(emptyList())
        }
    }

    private fun subscription(handle: PhoneAccountHandle): Int? = runCatching {
        telephony?.createForPhoneAccountHandle(handle)?.subscriptionId?.takeIf { it >= 0 }
    }.getOrNull()

    private fun callEndMessage(call: Tracked): String = when (call.call.details?.disconnectCause?.code) {
        android.telecom.DisconnectCause.BUSY -> "对方占线，通话已结束"
        android.telecom.DisconnectCause.ERROR, android.telecom.DisconnectCause.RESTRICTED -> "Android 报告通话失败"
        android.telecom.DisconnectCause.MISSED -> "来电未接"
        else -> "通话已结束"
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private fun ok(message: String) = CallActionResult(true, message)
    private fun fail(message: String) = CallActionResult(false, message)

    private class CarrierSim(val info: CallSim, val handle: PhoneAccountHandle)
    private class Tracked(val call: Call) {
        val id = UUID.randomUUID().toString()
        val number get() = numberOf(call)
        val direction get() = when (call.details?.callDirection) {
            Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
            Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
            else -> null
        }
        val phase get() = when (call.state) {
            Call.STATE_DIALING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW -> CallPhase.DIALING
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_CONNECTING -> CallPhase.CONNECTING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> CallPhase.DISCONNECTING
            else -> CallPhase.UNKNOWN
        }
        val createdAt = System.currentTimeMillis()
        var autoTried = false
        var suppressed = false
    }
    private class Work(
        val direction: CallDirection,
        val config: CallConfig,
        var track: Tracked? = null,
        val auto: Boolean = false,
        val requestedNumber: String? = null,
        val sim: CarrierSim? = null,
        val expectedCallId: String? = null,
    ) {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<CallActionResult>()
        val cleaned = CompletableDeferred<Boolean>()
        val transcript = mutableListOf<CallTranscriptLine>()
        val pendingRing get() = direction == CallDirection.INCOMING && !answerSent
        var job: Job? = null
        var placed = false
        var answerSent = false
        var telecomActionIssued = false
        var bridging = false
        var aiActive = false
        var activeAt: Long? = null
        var activeElapsed: Long = 0L
        var stopReason: String? = null
        var session: VolcRealtimeSession? = null
        val audioOwner = Binder()
        var audioHelper: IMikaShell? = null
        var capture: ParcelFileDescriptor.AutoCloseInputStream? = null
        var playback: ParcelFileDescriptor.AutoCloseOutputStream? = null
    }

    private class CallProblem(message: String) : Exception(message)

    private companion object {
        const val CALL_CHANGED = "指定通话已变化或结束，请重新读取电话状态"
        const val TOKEN = "com.wangzan101.mika.CALL_REQUEST_TOKEN"
        fun disconnected(call: Call): Boolean =
            call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING

        fun numberOf(call: Call): String? = call.details?.handle?.takeIf { it.scheme == PhoneAccount.SCHEME_TEL }
            ?.schemeSpecificPart?.let(CallPolicy::incomingNumber)
    }
}
