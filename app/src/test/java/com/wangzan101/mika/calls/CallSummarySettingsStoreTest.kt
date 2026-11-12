package com.wangzan101.mika.calls

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallSummarySettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun selectionAndAutomaticPreferenceSurviveRecreationIndependently() {
        val store = CallSummarySettingsStore(tmp.root)
        assertTrue(store.state.value.autoGenerate)
        assertNull(store.state.value.providerId)
        assertTrue(store.select("provider-one", "vendor/model-one"))
        assertTrue(store.setAutoGenerate(false))
        val reloaded = CallSummarySettingsStore(tmp.root)
        assertEquals("provider-one", reloaded.state.value.providerId)
        assertEquals("vendor/model-one", reloaded.state.value.model)
        assertFalse(reloaded.state.value.autoGenerate)
        assertTrue(reloaded.select("provider-two", "model-two"))
        assertTrue(reloaded.clearSelection())
        assertNull(CallSummarySettingsStore(tmp.root).state.value.providerId)
        assertFalse(CallSummarySettingsStore(tmp.root).state.value.autoGenerate)
        assertEquals(setOf("call-summary-settings.json"), tmp.root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun malformedSettingsFailClosedWithoutReplacingTheFile() {
        val file = File(tmp.root, "call-summary-settings.json")
        val broken = """{"providerId":"one","autoGenerate":true}"""
        file.writeText(broken)
        val store = CallSummarySettingsStore(tmp.root)
        assertTrue(store.error.value)
        assertFalse(store.setAutoGenerate(false))
        assertTrue(store.error.value)
        assertEquals(broken, file.readText())
        assertTrue(store.select("replacement", "valid-model"))
        assertFalse(store.error.value)
        assertEquals("replacement", CallSummarySettingsStore(tmp.root).state.value.providerId)
    }
}
