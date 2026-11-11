package com.wangzan101.mika.calls.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteCallHandleTest {
    @Test fun dialAcknowledgementAndHandoffAreNotCarrierCompletion() {
        val handle = RemoteCallHandle("task", 100) { true }
        handle.update(RemoteCallSnapshot(phase = "dialing", workId = "work", callId = "call", issued = true))
        assertFalse(handle.state.value.terminal)
        handle.update(handle.state.value.copy(phase = "handed_off", workFinished = true))
        assertFalse(handle.state.value.terminal)
        assertFalse(handle.state.value.carrierEnded)
        handle.update(handle.state.value.copy(phase = "ended", carrierEnded = true))
        assertTrue(handle.state.value.terminal)
        assertTrue(handle.state.value.carrierEnded)
    }
    @Test fun lateAiCleanupCannotDowngradeAConfirmedCarrierEnd() {
        val handle = RemoteCallHandle("task", 100) { true }
        handle.update(RemoteCallSnapshot(phase = "ended", carrierEnded = true, callId = "old-call"))
        handle.update(RemoteCallSnapshot(phase = "handed_off", workFinished = true))
        assertEquals("ended", handle.state.value.phase)
        assertEquals("old-call", handle.state.value.callId)
        assertTrue(handle.state.value.carrierEnded)
        assertTrue(handle.state.value.workFinished)
    }
    @Test fun strictBindingRejectsAnOldCommandAndNeverTreatsCancelAsImplicitHangup() {
        val preparing = RemoteCallSnapshot()
        assertTrue(preparing.acceptsControl(null, false))
        assertFalse(preparing.acceptsControl(null, true))
        val bound = preparing.copy(phase = "active", callId = "new-call", issued = true)
        assertFalse(bound.acceptsControl(null, false))
        assertFalse(bound.acceptsControl("old-call", true))
        assertTrue(bound.acceptsControl("new-call", true))
        assertTrue(bound.acceptsControl("new-call", false))
        assertFalse(bound.copy(phase = "ended").acceptsControl("new-call", true))
    }
    @Test fun explicitCancelRevokesPendingAuthorizationEvenWhenTheServiceIsStillRunning() {
        val handle = RemoteCallHandle("task", 100) { true }
        assertTrue(handle.allowed())
        handle.cancelRequested = true
        assertFalse(handle.allowed())
    }
}
