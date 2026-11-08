package com.wangzan101.mika.calls

import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
}
