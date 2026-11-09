package com.wangzan101.mika.calls

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly invoked dev-device setup. No phone is dialed and no cloud request is sent. */
class CallDevicePreparationTest {
    @Test fun prepareExplicitlyRequestedDevelopmentDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MikaApp
        assumeTrue("Only the parallel development package", app.packageName.endsWith(".dev"))
        assumeTrue("Opt-in test only", InstrumentationRegistry.getArguments().getString("prepareCalls") == "true")
        withTimeout(15_000) { app.phone.hub.shell.first { it != null } }
        val runtime = withContext(Dispatchers.Main) { app.calls }
        // A developer may stream an existing key into this app-private, temporary file using run-as.
        // Never use instrumentation arguments, logcat or shared storage for the key.
        val keyFile = File(app.filesDir, "call-test-key.txt")
        if (keyFile.exists()) {
            val key = keyFile.readText().trim()
            try { runtime.settings.saveCredentials("", "", key) }
            finally { check(keyFile.delete()) { "Could not remove temporary credential file" } }
            assertTrue(runtime.settings.state.value.credentialsConfigured)
            assertFalse(app.getSharedPreferences("mika_call_settings", 0).all.toString().contains(key))
        }
        runtime.settings.save(runtime.settings.state.value.copy(enabled = true, autoAnswer = false, maxCallSeconds = 30))
        val result = runtime.prepare()
        assertTrue(result.message, result.ok)
        runtime.refresh()
        assertTrue("Call companion is prepared", runtime.state.value.prepared)
        assertTrue("Audio preflight is ready", runtime.state.value.audioReady)
        assertFalse("Preflight never proves bidirectional audio", runtime.settings.state.value.audioVerified)
        assertFalse("Automatic answer stays off", runtime.settings.state.value.autoAnswer)
        assertEquals(CallPhase.IDLE, runtime.state.value.phase)
        assertFalse("No AI session is started", runtime.state.value.aiActive)
    }

    /** Read existing state and history only. Does not prepare, dial or connect to the provider. */
    @Test fun diagnoseExplicitlyRequestedDevelopmentDevice() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue("Only the parallel development package", app.packageName.endsWith(".dev"))
        assumeTrue("Explicit diagnoseCalls=true is required",
            InstrumentationRegistry.getArguments().getString("diagnoseCalls") == "true")

        val runtime = withContext(Dispatchers.Main.immediate) { app.calls }
        val refreshed = withTimeoutOrNull(2_000) { runtime.refresh(); true } == true
        val history = withTimeoutOrNull(2_000) { runtime.history.first { it.isNotEmpty() } }
            ?: runtime.history.value
        val state = runtime.state.value
        val shell = app.phone.hub.shell.value
        val result = buildJsonObject {
            put("runtime_phase", state.phase.name)
            put("runtime_state_refreshed", refreshed)
            put("ai_active", state.aiActive)
            put("prepared", state.prepared)
            put("shell_connected", shell != null)
            put("shell_binder_alive", shell?.asBinder()?.isBinderAlive == true)
            put("history_record_count", history.size)
            history.firstOrNull()?.let { record ->
                put("history_latest_result", redactCallDiagnostic(record.result))
                put("history_latest_duration_ms", (record.endedAtMs - record.startedAtMs).coerceAtLeast(0L))
                put("history_latest_ended_ago_ms", (System.currentTimeMillis() - record.endedAtMs).coerceAtLeast(0L))
                put("history_latest_transcript_count", record.transcript.size)
            }
        }
        withContext(Dispatchers.IO) {
            val file = AtomicFile(File(app.filesDir, "call-diagnostics.json"))
            val stream = file.startWrite()
            try {
                stream.write(result.toString().toByteArray())
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }
    }
}

/** Only runtime status/result messages use this helper. Never pass transcripts or credentials. */
internal fun redactCallDiagnostic(message: String): String = message
    .replace(Regex("""\p{Nd}+"""), "#")
    .replace(Regex("""[\p{Cntrl}]+"""), " ")
    .take(240)
