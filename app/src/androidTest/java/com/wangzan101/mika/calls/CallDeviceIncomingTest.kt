package com.wangzan101.mika.calls

import android.annotation.SuppressLint
import android.os.SystemClock
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Observes one authorized real incoming call; never places, answers, or rejects a call. */
@Suppress("DEPRECATION")
class CallDeviceIncomingTest {
    @Test fun observeExplicitlyRequestedIncomingCall() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit incomingCalls=true is required", InstrumentationRegistry.getArguments().getString("incomingCalls") == "true")
        assumeTrue("Only the parallel Mika Dev package", instrumentation.targetContext.packageName == DEV_PACKAGE)
        val app = instrumentation.targetContext.applicationContext as MikaApp
        val report = Report(app.filesDir)
        var status = "request_rejected"
        try {
            val request = withContext(Dispatchers.IO) { consumeRequest(app.filesDir) }
            report.put("nonce_consumed", true)
            report.put("max_seconds", request.maxSeconds)
            status = withContext(Dispatchers.Main.immediate) {
                val runtime = app.calls
                preflight(app, runtime, request)
                report.write("armed")
                observe(runtime, request, report)
            }
        } catch (e: Rejected) {
            status = e.code
        } catch (e: CancellationException) {
            status = "cancelled"
            throw e
        } catch (_: Exception) {
            status = "test_error"
        } finally {
            withContext(NonCancellable) { report.write(status) }
        }
        if (status != "local_checks_passed") {
            throw AssertionError("Incoming call test: $status; see app-private call-incoming-result.json")
        }
    }

    @SuppressLint("MissingPermission") // A denied or inconclusive read rejects the request.
    private suspend fun preflight(app: MikaApp, runtime: CallRuntime, request: Request) {
        requirePolicy(runtime.settings.state.value, request)
        val ready = withTimeoutOrNull(PREPARE_MS) {
            runtime.state.first { state ->
                if (!isIdle(state)) throw Rejected("not_idle")
                requirePolicy(runtime.settings.state.value, request)
                state.prepared && state.shellConnected && state.audioReady && state.notificationsAvailable &&
                    state.autoAnswerStage == CallAutoAnswerStage.STANDBY
            }
        } ?: throw Rejected("auto_answer_not_ready")
        if (!isIdle(ready) || !isIdle(runtime.state.value) || runCatching {
                app.getSystemService(TelecomManager::class.java)?.isInCall
            }.getOrNull() != false) throw Rejected("not_idle")
        requirePolicy(runtime.settings.state.value, request)
    }

    private fun requirePolicy(config: CallConfig, request: Request) {
        val onlyNumber = config.allowedIncomingNumbers.singleOrNull()
        if (!config.enabled || !config.autoAnswer || !config.audioVerified || !config.credentialsConfigured ||
            config.maxCallSeconds != request.maxSeconds || config.allowAllIncoming ||
            CallPolicy.incomingMatchKey(onlyNumber) != request.matchKey ||
            !CallPolicy.callerAllowed(config, onlyNumber)) throw Rejected("test_configuration_mismatch")
    }

    private suspend fun observe(runtime: CallRuntime, request: Request, report: Report): String {
        val ringing = withTimeoutOrNull(RING_WAIT_MS) {
            runtime.state.first { !isIdle(it) }
        } ?: throw Rejected("target_did_not_ring")
        val id = ringing.callId?.takeIf {
            ringing.phase == CallPhase.RINGING && matches(ringing, request, it)
        } ?: throw Rejected("unexpected_call_untouched")
        requirePolicy(runtime.settings.state.value, request)
        report.put("target_ringing_observed", true)
        var live: Bound? = null
        var hangupAttempted = false
        try {
            val bound = withTimeoutOrNull(CONNECT_WAIT_MS) { waitUntilAutoAnswered(runtime, request, id) }
                ?: throw Rejected("auto_answer_not_confirmed")
            live = bound
            report.put("automatic_answer_observed", true)
            report.put("telecom_active", true)
            report.write("telecom_active")

            val takeoverAt = bound.activeAt + TAKEOVER_MS
            val hangupAt = bound.activeAt + HANGUP_MS
            var aiObserved = false
            var callerFinal = false
            var assistantFinal = false
            var maxFinalLines = 0
            do {
                val state = runtime.state.value
                if (!sameActive(runtime, request, bound)) throw Rejected("call_ended_or_changed_before_takeover")
                aiObserved = aiObserved || (state.aiActive && field(runtime, "work") === bound.work)
                maxFinalLines = maxOf(maxFinalLines, state.liveTranscript.size)
                callerFinal = callerFinal || state.liveTranscript.any { it.role == "caller" && it.text.isNotBlank() }
                assistantFinal = assistantFinal || state.liveTranscript.any { it.role == "assistant" && it.text.isNotBlank() }
                report.put("ai_active_observed", aiObserved)
                report.put("caller_final_observed", callerFinal)
                report.put("assistant_final_observed", assistantFinal)
                report.put("max_final_line_count", maxFinalLines)
                pauseUntil(takeoverAt)
            } while (now() < takeoverAt)

            val takeoverBudget = hangupAt - now()
            val takeoverOk = takeoverBudget > 0 && withTimeoutOrNull(takeoverBudget) {
                // Call ID identifies the Telecom connection; the takeover API takes its exact Work ID.
                sameActive(runtime, request, bound) && field(runtime, "work") === bound.work &&
                    runtime.takeover(bound.workId).ok
            } == true
            val activeAfterTakeover = sameActive(runtime, request, bound) && !runtime.state.value.aiActive
            report.put("takeover_confirmed", takeoverOk)
            report.put("active_after_takeover", activeAfterTakeover)
            while (now() < hangupAt) {
                if (!sameActive(runtime, request, bound)) throw Rejected("call_ended_or_changed_before_hangup")
                pauseUntil(hangupAt)
            }

            val remaining = bound.activeAt + request.maxSeconds * 1_000L - now()
            val hangupConfirmed = remaining > 0 && withTimeoutOrNull(remaining) {
                if (!sameActive(runtime, request, bound)) return@withTimeoutOrNull false
                hangupAttempted = true
                runtime.hangup(expectedCallId = bound.id).ok && bound.call.state == Call.STATE_DISCONNECTED
            } == true
            report.put("hangup_confirmed", hangupConfirmed)
            return when {
                !hangupConfirmed -> "hangup_not_confirmed"
                !takeoverOk || !activeAfterTakeover -> "takeover_not_verified"
                !aiObserved -> "ai_not_observed"
                !callerFinal || !assistantFinal -> "final_transcript_not_observed"
                else -> "local_checks_passed"
            }
        } finally {
            val bound = live
            if (bound != null && !hangupAttempted && sameActive(runtime, request, bound)) {
                val remaining = bound.activeAt + request.maxSeconds * 1_000L - now()
                if (remaining > 0L) withContext(NonCancellable) {
                    val ended = withTimeoutOrNull(remaining) {
                        if (!sameActive(runtime, request, bound)) return@withTimeoutOrNull false
                        report.put("cleanup_hangup_attempted", true)
                        runtime.hangup(expectedCallId = bound.id).ok && bound.call.state == Call.STATE_DISCONNECTED
                    } == true
                    report.put("cleanup_hangup_confirmed", ended)
                }
            }
        }
    }

    private suspend fun waitUntilAutoAnswered(runtime: CallRuntime, request: Request, id: String): Bound {
        var observedAutoWork: Any? = null
        while (true) {
            val state = runtime.state.value
            if (!matches(state, request, id)) throw Rejected("call_ended_or_changed_before_active")
            requirePolicy(runtime.settings.state.value, request)
            val work = field(runtime, "work")
            val track = work?.let { field(it, "track") }
            if (work != null && track != null && field(track, "id") == id) {
                if (field(work, "auto") != true || field(work, "manualAnswerRequested") != false) {
                    throw Rejected("manual_answer_untouched")
                }
                observedAutoWork = work
            }
            if (state.phase == CallPhase.ACTIVE) {
                val automatic = observedAutoWork?.takeIf { field(it, "auto") == true &&
                    field(it, "manualAnswerRequested") == false && field(it, "answerSent") == true }
                    ?: throw Rejected("unverified_answer_untouched")
                val original = field(automatic, "track")?.takeIf { field(it, "id") == id }
                    ?: throw Rejected("call_identity_unavailable")
                val call = (field(original, "call") as? Call)?.takeIf { it.state == Call.STATE_ACTIVE }
                    ?: throw Rejected("call_not_active")
                val workId = field(automatic, "id") as? String ?: throw Rejected("work_identity_unavailable")
                return Bound(id, workId, call, automatic, now())
            }
            if (state.phase !in setOf(CallPhase.RINGING, CallPhase.CONNECTING)) {
                throw Rejected("call_ended_or_changed_before_active")
            }
            delay(POLL_MS)
        }
    }

    private fun sameActive(runtime: CallRuntime, request: Request, bound: Bound): Boolean {
        val state = runtime.state.value
        return state.phase == CallPhase.ACTIVE && bound.call.state == Call.STATE_ACTIVE && matches(state, request, bound.id)
    }

    private fun matches(state: CallRuntimeState, request: Request, id: String): Boolean =
        state.callId == id && state.direction == CallDirection.INCOMING &&
            CallPolicy.incomingMatchKey(state.number) == request.matchKey

    private fun isIdle(state: CallRuntimeState): Boolean = state.phase == CallPhase.IDLE && state.callId == null &&
        !state.aiActive && !state.pendingAutoAnswer && !state.canTakeOver

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private suspend fun pauseUntil(deadline: Long) {
        val remaining = deadline - now()
        if (remaining > 0L) delay(minOf(POLL_MS, remaining))
    }

    private fun consumeRequest(dir: File): Request {
        val source = File(dir, "call-incoming-request.json")
        val claimed = File(dir, "call-incoming-request.claim-${UUID.randomUUID()}")
        val payload = try {
            Files.move(source.toPath(), claimed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                if (claimed.length() !in 1..4096) throw Rejected("invalid_request")
                claimed.readText()
            } finally {
                if (!claimed.delete()) throw Rejected("request_delete_failed")
            }
        } catch (e: Rejected) {
            throw e
        } catch (_: Exception) {
            throw Rejected("request_missing_or_unreadable")
        }
        val json = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?.takeIf { it.keys == setOf("number", "nonce", "maxSeconds") } ?: throw Rejected("invalid_request")
        val number = (json["number"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(CallPolicy::outgoingNumber) ?: throw Rejected("invalid_test_number")
        val matchKey = CallPolicy.incomingMatchKey(number) ?: throw Rejected("invalid_test_number")
        val nonce = (json["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { Regex("[A-Za-z0-9_-]{8,128}").matches(it) } ?: throw Rejected("invalid_nonce")
        val seconds = (json["maxSeconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it == 30 } ?: throw Rejected("invalid_duration")
        val spent = File(dir, "call-incoming-consumed")
        if (!spent.isDirectory && !spent.mkdir() && !spent.isDirectory) throw Rejected("nonce_store_unavailable")
        val digest = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        try {
            val marker = File(spent, digest)
            Files.createFile(marker.toPath())
            RandomAccessFile(marker, "rw").use { it.writeByte(1); it.fd.sync() }
        } catch (_: FileAlreadyExistsException) {
            throw Rejected("nonce_already_consumed")
        } catch (_: Exception) {
            throw Rejected("nonce_store_unavailable")
        }
        return Request(matchKey, seconds)
    }

    private class Request(val matchKey: String, val maxSeconds: Int)
    private class Bound(val id: String, val workId: String, val call: Call, val work: Any, val activeAt: Long)
    private class Rejected(val code: String) : Exception(code)

    private class Report(dir: File) {
        private val file = AtomicFile(File(dir, "call-incoming-result.json"))
        private val fields = linkedMapOf("peer_audibility_verified" to JsonPrimitive(false))
        fun put(key: String, value: Boolean) { fields[key] = JsonPrimitive(value) }
        fun put(key: String, value: Int) { fields[key] = JsonPrimitive(value) }
        suspend fun write(status: String) = withContext(Dispatchers.IO) {
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
        const val DEV_PACKAGE = "com.wangzan101.mika.dev"
        const val PREPARE_MS = 15_000L
        const val RING_WAIT_MS = 60_000L
        const val CONNECT_WAIT_MS = 30_000L
        const val TAKEOVER_MS = 23_000L
        const val HANGUP_MS = 28_000L
        const val POLL_MS = 50L
        fun now(): Long = SystemClock.elapsedRealtime()
    }
}
