package com.wangzan101.mika.calls

import android.os.Process as AndroidProcess
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.calls.codex.CodexJsonlClient
import com.wangzan101.mika.calls.codex.CodexProcessLauncher
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires the dev APK with the pinned asset and instrumentation arg codexHandshake=true. */
class CallCodexDeviceTest {
    @Test fun initializeBundledCodexFromApplicationUid() = runBlocking {
        assumeTrue("Opt-in local engine test only",
            InstrumentationRegistry.getArguments().getString("codexHandshake") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assumeTrue("Only the parallel development package", app.packageName.endsWith(".dev"))
        assertEquals(app.applicationInfo.uid, AndroidProcess.myUid())
        assertTrue("Not root or ADB shell", AndroidProcess.myUid() >= 10_000)

        val launcher = CodexProcessLauncher(app)
        assertTrue("Bundled ARM64 Codex binary is executable", launcher.available)
        val profile = Files.createTempDirectory(app.cacheDir.toPath(), "codex-handshake-").toFile()
        var process: Process? = null
        var client: CodexJsonlClient? = null
        try {
            val child = launcher.launchHandshakeProbe(profile)
            process = child
            val rpc = CodexJsonlClient(child.inputStream, child.outputStream, child.errorStream)
            client = rpc
            // initialize verifies the pinned real engine version. No thread/start or model request.
            withTimeout(10_000L) { rpc.initialize() }
            child.outputStream.close()
            assertTrue("Codex exited after stdin closed", child.waitFor(4, TimeUnit.SECONDS))
            assertEquals("Codex exited cleanly", 0, child.exitValue())
        } finally {
            client?.close()
            process?.let { CodexProcessLauncher.stop(it, graceful = false) }
            assertTrue("Temporary Codex profile was removed", profile.deleteRecursively())
        }
    }
}
