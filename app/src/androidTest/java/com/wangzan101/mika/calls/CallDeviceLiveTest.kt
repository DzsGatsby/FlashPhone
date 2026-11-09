package com.wangzan101.mika.calls

import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Opt-in only: the Dev app-private request is the authority for exactly one outgoing test. */
class CallDeviceLiveTest {
    @Test fun runExplicitlyRequestedCarrierCall() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue("Only the parallel Mika Dev package", app.packageName.endsWith(".dev"))
        assumeTrue("Explicit liveCalls=true is required", InstrumentationRegistry.getArguments().getString("liveCalls") == "true")

        val report = Report(app.filesDir)
        var status = "request_rejected"
        try {
            val request = consumeRequest(app.filesDir)
            report.put("nonce_consumed", true)
            report.put("mode", request.mode.name)
            report.put("max_seconds", request.maxSeconds)
            report.write("preflight")
            val helper = withTimeoutOrNull(PREPARE_MS) { app.phone.hub.shell.first { it != null } }
                ?: throw Rejected("helper_unavailable")
            val runtime = withContext(Dispatchers.Main.immediate) { app.calls }
            val simId = withTimeoutOrNull(PREPARE_MS) { prepare(runtime, request) }
                ?: throw Rejected("prepare_timeout")
            report.put("preflight_ready", true)
            status = runLive(runtime, helper, request, simId, report)
        } catch (e: Rejected) {
            status = e.code
        } catch (e: CancellationException) {
            status = "cancelled"
            throw e
        } catch (_: Exception) {
            // Never attach exception messages: the request contains a private telephone number.
            status = "test_error"
        } finally {
            report.write(status)
        }
        if (status != "local_checks_passed") {
            throw AssertionError("Live call test: $status; see app-private call-live-result.json")
        }
    }

    private suspend fun prepare(runtime: CallRuntime, request: Request): String {
        runtime.refresh()
        val before = runtime.state.value
        if (before.phase != CallPhase.IDLE || before.aiActive || before.pendingAutoAnswer || before.canTakeOver) {
            throw Rejected("not_idle")
        }
        val settings = runtime.settings.state.value
        if (!settings.credentialsConfigured) throw Rejected("credentials_unavailable")
        withContext(Dispatchers.IO) {
            runtime.settings.save(settings.copy(
                enabled = true, autoAnswer = false, audioVerified = false,
                audioMode = request.mode, maxCallSeconds = request.maxSeconds,
            ))
        }
        if (!runtime.prepare().ok) throw Rejected("telecom_prepare_failed")
        if (!runtime.refresh().ok) throw Rejected("telecom_prepare_not_confirmed")
        val state = runtime.state.value
        val config = runtime.settings.state.value
        if (!state.prepared || !state.shellConnected) throw Rejected("not_prepared")
        if (!state.audioReady) throw Rejected("audio_preflight_failed")
        if (!state.notificationsAvailable) throw Rejected("notifications_unavailable")
        if (!config.credentialsConfigured) throw Rejected("credentials_unavailable")
        if (config.autoAnswer || config.audioVerified || config.audioMode != request.mode || config.maxCallSeconds != request.maxSeconds) {
            throw Rejected("test_configuration_mismatch")
        }
        if (state.phase != CallPhase.IDLE || state.aiActive || state.pendingAutoAnswer || state.canTakeOver || state.callId != null) {
            throw Rejected("not_idle")
        }
        return (state.simOptions.singleOrNull() ?: state.simOptions.singleOrNull { it.isDefault })?.id
            ?: throw Rejected("unambiguous_sim_unavailable")
    }

    private suspend fun runLive(
        runtime: CallRuntime, helper: IMikaShell, request: Request, simId: String, report: Report,
    ): String = coroutineScope {
        val started = now()
        val initialHistory = runtime.history.value.mapTo(mutableSetOf()) { it.id }
        var bound: BoundCall? = null
        var hangupConfirmed = false
        var dialReceipt: CallActionResult? = null
        var takeoverOk = false
        var activeAfterTakeover = false
        var flowProven = false
        var micRestored = false
        var status = "live_timeout"
        report.write("dial_started")
        val dial = async { runtime.dial(request.number, simId) }
        try {
            status = withTimeoutOrNull(LIVE_MS - CLEANUP_RESERVE_MS) {
                var activeAt: Long? = null
                var alertStarted: Long? = null
                var lastAudioRead = 0L
                while (activeAt == null) {
                    if (dialReceipt == null && dial.isCompleted) {
                        dialReceipt = dial.await()
                        val confirmed = dialReceipt?.ok == true
                        report.put("dial_confirmed", confirmed)
                        if (!confirmed) throw Rejected("dial_not_confirmed")
                    }
                    val observed = dialReceipt?.let { captureBound(runtime, request.number, it, bound) }
                    if (observed != null) {
                        bound = observed.call
                        if (alertStarted == null) {
                            alertStarted = now()
                            report.put("call_id_bound", true)
                            report.write("telecom_observed")
                        }
                        if (observed.phase == CallPhase.ACTIVE) activeAt = now()
                    } else if (bound != null && !stillMatches(runtime.state.value, request.number, bound)) {
                        throw Rejected("call_ended_or_changed_before_active")
                    }
                    val elapsed = now() - started
                    if (activeAt == null && (elapsed >= CONNECT_MS || alertStarted?.let { now() - it >= ALERT_MS } == true)) {
                        throw Rejected("call_not_active_before_deadline")
                    }
                    if (activeAt == null) delay(POLL_MS)
                }

                val call = bound ?: throw Rejected("call_identity_unavailable")
                val connected = activeAt
                report.put("telecom_active", true)
                report.put("active_after_ms", connected - started)
                report.write("telecom_active")
                val activeLimit = request.maxSeconds * 1_000L
                val hangupAt = minOf(connected + minOf(28_000L, activeLimit - 500L), started + LIVE_MS - 11_000L)
                val takeoverAt = minOf(connected + minOf(23_000L, activeLimit - 4_000L), hangupAt - 3_000L)
                while (now() < takeoverAt) {
                    val state = runtime.state.value
                    if (!stillMatches(state, request.number, call) || state.phase != CallPhase.ACTIVE) {
                        throw Rejected("call_changed_before_takeover")
                    }
                    if (state.aiActive) report.put("ai_active_observed", true)
                    if (now() - lastAudioRead >= 500) {
                        lastAudioRead = now()
                        readAudio(helper, call.owner)?.let { stats ->
                            flowProven = flowProven || stats.downBytes > 0 && stats.upBytes > 0
                            report.putAudio(stats)
                        }
                    }
                    delay(POLL_MS)
                }
                report.put("local_duplex_pcm_flow_observed", flowProven)
                report.write("takeover_requested")
                takeoverOk = withContext(Dispatchers.Main.immediate) {
                    val state = runtime.state.value
                    // Keep state check and invocation on Telecom's main dispatcher, with no wait in between.
                    if (!stillMatches(state, request.number, call) || state.phase != CallPhase.ACTIVE ||
                        reflectionWork(runtime) !== call.work) false else runtime.takeover().ok
                }
                val after = runtime.state.value
                activeAfterTakeover = stillMatches(after, request.number, call) && after.phase == CallPhase.ACTIVE && !after.aiActive
                readAudio(helper, call.owner)?.let { stats ->
                    micRestored = !stats.active && stats.microphoneRestored
                    flowProven = flowProven || stats.downBytes > 0 && stats.upBytes > 0
                    report.putAudio(stats)
                }
                report.put("local_duplex_pcm_flow_observed", flowProven)
                report.put("takeover_ok", takeoverOk)
                report.put("active_after_takeover", activeAfterTakeover)
                report.put("microphone_restored_readback", micRestored)
                report.write("takeover_observed")
                while (now() < hangupAt && stillMatches(runtime.state.value, request.number, call)) delay(POLL_MS)
                hangupConfirmed = hangupBound(runtime, request.number, call)
                report.put("hangup_confirmed", hangupConfirmed)
                when {
                    !hangupConfirmed -> "hangup_not_confirmed"
                    !takeoverOk || !activeAfterTakeover || !micRestored -> "takeover_not_verified"
                    !flowProven -> "duplex_pcm_not_observed"
                    else -> "local_checks_passed"
                }
            } ?: "live_timeout"
        } catch (e: Rejected) {
            status = e.code
        } finally {
            // Always leave enough of the live window to attempt cleanup. Do not touch another call,
            // even if the same number is dialed again from the native dialer.
            withContext(NonCancellable) {
                val remaining = (started + LIVE_MS - now()).coerceAtLeast(1L)
                withTimeoutOrNull(remaining) {
                    if (!hangupConfirmed) {
                        if (bound == null) {
                            bound = withTimeoutOrNull(1_500) {
                                val receipt = dialReceipt ?: dial.await()
                                if (!receipt.ok) return@withTimeoutOrNull null
                                dialReceipt = receipt
                                var observed: ObservedCall?
                                do {
                                    observed = captureBound(runtime, request.number, receipt, null)
                                    if (observed == null) delay(POLL_MS)
                                } while (observed == null)
                                observed.call
                            }
                        }
                        if (!dial.isCompleted) dial.cancel()
                        val call = bound
                        if (call != null && stillMatches(runtime.state.value, request.number, call)) {
                            report.put("cleanup_hangup_attempted", true)
                            hangupConfirmed = hangupBound(runtime, request.number, call)
                            report.put("hangup_confirmed", hangupConfirmed)
                        }
                    }
                    if (!dial.isCompleted) dial.cancel()
                    val history = withTimeoutOrNull(500) {
                        runtime.history.first { records -> records.any { it.id == bound?.workId && it.id !in initialHistory } }
                    }.orEmpty().filter { it.id == bound?.workId && it.id !in initialHistory }
                    report.put("history_record_count", history.size)
                    report.put("history_line_count", history.sumOf { it.transcript.size })
                    report.put("expected_phrase_recognized", history.any { record ->
                        record.transcript.any { it.role == "caller" && it.text.replace(Regex("[\\s，。！,.!]"), "").contains("蓝色苹果七号") }
                    })
                }
            }
        }
        status
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun captureBound(
        runtime: CallRuntime, number: String, receipt: CallActionResult, prior: BoundCall?,
    ): ObservedCall? = withContext(Dispatchers.Main.immediate) {
            val state = runtime.state.value
            val id = state.callId ?: return@withContext null
            if (state.direction != CallDirection.OUTGOING || state.number != number ||
                (prior != null && prior.id != id)) return@withContext null
            val work = reflectionWork(runtime) ?: return@withContext null
            if (prior != null && prior.work !== work) return@withContext null
            // The exact acknowledgement instance ties the current Work to this harness invocation,
            // even when another caller tries to dial the same number concurrently.
            val acknowledged = field(work, "result") as? Deferred<*> ?: return@withContext null
            if (!acknowledged.isCompleted || runCatching { acknowledged.getCompleted() }.getOrNull() !== receipt) {
                return@withContext null
            }
            // Work.track is assigned by production only after Telecom's outgoing token and SIM match.
            if (field(work, "placed") != true || field(work, "requestedNumber") != number) return@withContext null
            val track = field(work, "track") ?: return@withContext null
            if (field(track, "id") != id) return@withContext null
            val owner = field(work, "audioOwner") as? IBinder ?: return@withContext null
            val workId = field(work, "id") as? String ?: return@withContext null
            ObservedCall(prior ?: BoundCall(id, workId, owner, work), state.phase)
        }

    private suspend fun hangupBound(runtime: CallRuntime, number: String, call: BoundCall): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val state = runtime.state.value
            if (!stillMatches(state, number, call) || state.phase == CallPhase.DISCONNECTING) false
            else runtime.hangup(expectedCallId = call.id).ok
        }

    private fun stillMatches(state: CallRuntimeState, number: String, call: BoundCall?): Boolean =
        call != null && state.callId == call.id && state.direction == CallDirection.OUTGOING && state.number == number

    private fun reflectionWork(runtime: CallRuntime): Any? = field(runtime, "work")
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private suspend fun readAudio(helper: IMikaShell, owner: IBinder): AudioReadback? = withContext(Dispatchers.IO) {
        val stats = runCatching { helper.callAudioStats(owner) }.getOrNull()
            ?.takeIf { it.containsKey("microphoneRestored") } ?: return@withContext null
        AudioReadback(stats.getBoolean("active"), stats.getLong("downBytes"), stats.getLong("upBytes"),
            stats.getInt("downPeak"), stats.getInt("rxDevice", -1), stats.getInt("txDevice", -1),
            stats.getBoolean("microphoneRestored"),
            stats.getString("reason").orEmpty().replace(Regex("[+0-9][0-9 ()-]{5,}"), "[redacted]").take(160))
    }

    private fun consumeRequest(dir: File): Request {
        val requestFile = File(dir, "call-live-request.json")
        val claimed = File(dir, "call-live-request.claim-${UUID.randomUUID()}")
        val payload = try {
            Files.move(requestFile.toPath(), claimed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                if (claimed.length() !in 1..4_096) throw Rejected("invalid_request")
                claimed.readText()
            } finally {
                if (!claimed.delete()) throw Rejected("request_delete_failed")
            }
        } catch (e: Rejected) {
            throw e
        } catch (_: Exception) {
            throw Rejected("request_missing_or_unreadable")
        }
        val json = runCatching { Json.parseToJsonElement(payload) as JsonObject }.getOrNull()
            ?: throw Rejected("invalid_request")
        val rawNumber = (json["number"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val number = rawNumber.takeIf { Regex("[+0-9 ().-]{7,35}").matches(it) }
            ?.replace(Regex("[ ().-]"), "")?.takeIf { Regex("\\+?[0-9]{7,15}").matches(it) }
            ?: throw Rejected("invalid_test_number")
        val nonce = (json["nonce"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { Regex("[A-Za-z0-9_-]{8,128}").matches(it) } ?: throw Rejected("invalid_nonce")
        val mode = runCatching { CallAudioMode.valueOf((json["mode"] as JsonPrimitive).content) }.getOrNull()
            ?: throw Rejected("invalid_audio_mode")
        val seconds = (json["maxSeconds"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 15..30 }
            ?: throw Rejected("invalid_duration")
        val spent = File(dir, "call-live-consumed")
        if (!spent.isDirectory && !spent.mkdir()) throw Rejected("nonce_store_unavailable")
        val digest = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        val marker = File(spent, digest)
        try {
            // Exclusive persistent marker, written before preparing or placing a call. No retry of a consumed nonce.
            Files.createFile(marker.toPath())
            RandomAccessFile(marker, "rw").use { file -> file.writeByte(1); file.fd.sync() }
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            throw Rejected("nonce_already_consumed")
        } catch (_: Exception) {
            throw Rejected("nonce_store_unavailable")
        }
        return Request(number, mode, seconds)
    }

    private class Request(val number: String, val mode: CallAudioMode, val maxSeconds: Int)
    private class BoundCall(val id: String, val workId: String, val owner: IBinder, val work: Any)
    private class ObservedCall(val call: BoundCall, val phase: CallPhase)
    private class Rejected(val code: String) : Exception(code)
    private class AudioReadback(
        val active: Boolean, val downBytes: Long, val upBytes: Long, val downPeak: Int, val rxDevice: Int,
        val txDevice: Int, val microphoneRestored: Boolean, val reason: String,
    )

    private class Report(dir: File) {
        private val file = AtomicFile(File(dir, "call-live-result.json"))
        private val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "peer_audibility_verified" to JsonPrimitive(false),
            "peer_audibility_evidence" to JsonPrimitive("not_observable_from_instrumentation"),
        )
        fun put(key: String, value: Boolean) { fields[key] = JsonPrimitive(value) }
        fun put(key: String, value: Number) { fields[key] = JsonPrimitive(value) }
        fun put(key: String, value: String) { fields[key] = JsonPrimitive(value) }

        fun putAudio(stats: AudioReadback) {
            fields["audio_stats"] = buildJsonObject {
                put("downBytes", stats.downBytes); put("upBytes", stats.upBytes); put("downPeak", stats.downPeak)
                put("rxDevice", stats.rxDevice); put("txDevice", stats.txDevice)
                put("microphoneRestored", stats.microphoneRestored); put("reason", stats.reason)
            }
        }

        fun write(status: String) {
            fields["status"] = JsonPrimitive(status)
            val stream = file.startWrite()
            try {
                stream.write(JsonObject(fields).toString().toByteArray())
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }
    }

    private companion object {
        const val PREPARE_MS = 15_000L
        const val CONNECT_MS = 30_000L
        const val ALERT_MS = 25_000L
        const val LIVE_MS = 60_000L
        const val CLEANUP_RESERVE_MS = 7_000L
        const val POLL_MS = 100L
        fun now(): Long = SystemClock.elapsedRealtime()
    }
}
