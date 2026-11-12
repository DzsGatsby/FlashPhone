package com.wangzan101.mika.calls.codex

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexSummarySelectionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun absentSelectionDoesNotCreateOwnerState() {
        assertNull(readCodexSummarySelection(tmp.root))
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun readsOnlyConfigurationAndLeavesThreadAndBackupUntouched() {
        val base = File(tmp.root, "calls-codex-selection.json")
        val backup = File(tmp.root, "calls-codex-selection.json.bak")
        base.writeText("unfinished")
        val json = """{"version":1,"providerId":"provider","model":"gpt-test","threadId":"owner-thread","keyFingerprint":"${"a".repeat(64)}"}"""
        backup.writeText(json)
        assertEquals(CodexSummarySelection("provider", "gpt-test"), readCodexSummarySelection(tmp.root))
        assertEquals("unfinished", base.readText())
        assertEquals(json, backup.readText())
    }

    @Test fun invalidOrOversizedConfigFailsClosed() {
        val base = File(tmp.root, "calls-codex-selection.json")
        for (value in listOf("x".repeat(1_025), """{"version":1,"providerId":"x"}""", """{"version":1,"providerId":"x","model":"m","extra":"x"}""")) {
            base.writeText(value)
            try { readCodexSummarySelection(tmp.root); fail("Expected invalid selection") }
            catch (_: Exception) { }
            assertEquals(value, base.readText())
        }
    }
}
