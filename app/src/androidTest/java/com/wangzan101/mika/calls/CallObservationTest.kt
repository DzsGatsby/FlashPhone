package com.wangzan101.mika.calls

import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in observation only: no settings edits, dial, answer, takeover, hangup or provider requests. */
class CallObservationTest {
    @Test fun observeOwnerInitiatedIncomingCall() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(inst.targetContext.packageName == "com.wangzan101.mika.dev")
        assumeTrue(InstrumentationRegistry.getArguments().getString("observeCalls") == "true")
        val app = inst.targetContext.applicationContext as MikaApp
        val report = linkedMapOf<String, JsonPrimitive>()
        val file = AtomicFile(File(app.filesDir, "call-observation.json"))
        suspend fun save(status: String) = withContext(Dispatchers.IO) {
            report["status"] = JsonPrimitive(status)
            val stream = file.startWrite()
            try { stream.write(JsonObject(report).toString().toByteArray()); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
        withContext(Dispatchers.Main.immediate) {
            val runtime = app.calls
            val ready = withTimeoutOrNull(15_000L) {
                runtime.state.first { it.phase == CallPhase.IDLE && it.prepared && it.shellConnected && it.audioReady }
            }
            if (ready == null) { save("not_ready"); return@withContext }
            val config = runtime.settings.state.value
            report["max_seconds"] = JsonPrimitive(config.maxCallSeconds)
            report["greeting_is_hello"] = JsonPrimitive(config.greeting == "你好")
            report["effective_greeting_matches_setting"] = JsonPrimitive(CallPolicy.spokenGreeting(config.greeting, hasTask = false) == config.greeting.trim())
            save("armed")
            val ringing = withTimeoutOrNull(180_000L) {
                runtime.state.first { it.phase == CallPhase.RINGING && it.direction == CallDirection.INCOMING && it.callId != null }
            }
            if (ringing == null) { save("no_incoming_call"); return@withContext }
            val callId = ringing.callId
            var observedWork: Any? = null
            var activeAt = 0L
            var maxAiMs = 0L
            var autoAnswer = false
            var assistantFinal = false
            var callerFinal = false
            var stoppedWhileConnected = false
            val endAt = SystemClock.elapsedRealtime() + 120_000L
            save("ringing")
            while (SystemClock.elapsedRealtime() < endAt) {
                val state = runtime.state.value
                if (state.callId != callId) break
                val work = field(runtime, "work")
                if (work != null && field(work, "track")?.let { field(it, "id") } == callId) observedWork = work
                if (state.phase == CallPhase.ACTIVE) {
                    if (activeAt == 0L) activeAt = SystemClock.elapsedRealtime()
                    observedWork?.let {
                        autoAnswer = autoAnswer || (field(it, "auto") == true && field(it, "manualAnswerRequested") == false && field(it, "answerSent") == true)
                    }
                    if (state.aiActive) maxAiMs = SystemClock.elapsedRealtime() - activeAt
                    if (maxAiMs > 0 && !state.aiActive && field(observedWork, "stopReason")?.let { (it as? CallStopReason)?.value } != null) stoppedWhileConnected = true
                }
                callerFinal = callerFinal || state.liveTranscript.any { it.role == "caller" }
                assistantFinal = assistantFinal || state.liveTranscript.any { it.role == "assistant" }
                delay(100)
            }
            // Cleanup and history run asynchronously; observing their completion does not alter the call.
            delay(1_000)
            report["automatic_answer_observed"] = JsonPrimitive(autoAnswer)
            report["ai_active_through_ms"] = JsonPrimitive(maxAiMs)
            report["caller_final_observed"] = JsonPrimitive(callerFinal)
            report["assistant_final_observed"] = JsonPrimitive(assistantFinal)
            report["ai_stopped_while_call_connected"] = JsonPrimitive(stoppedWhileConnected)
            report["call_ended"] = JsonPrimitive(runtime.state.value.callId != callId)
            (field(observedWork, "mediaCounters") as? CallMediaCounters)?.snapshot()?.forEach { (k, v) -> report[k] = JsonPrimitive(v) }
            runtime.history.value.firstOrNull()?.let {
                report["latest_result"] = JsonPrimitive(redactCallDiagnostic(it.result))
                report["latest_duration_ms"] = JsonPrimitive(it.endedAtMs - it.startedAtMs)
            }
            save("observation_complete")
        }
    }
    private fun field(target: Any?, name: String): Any? = target?.let {
        runCatching { it.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(it) }.getOrNull()
    }
}
