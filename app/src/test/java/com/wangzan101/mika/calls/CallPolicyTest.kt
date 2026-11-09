package com.wangzan101.mika.calls

import org.junit.Assert.*
import org.junit.Test

class CallPolicyTest {
    @Test fun blocksControlCodesAndNeverStripsDialingSyntax() {
        listOf("112", "911", "*#06#", "**21*123456789#", "123456789,22", "123456789;22", "tel:123456789",
            "123456789p1", "%2a123456789", "+1+5551234567").forEach {
            assertNull(it, CallPolicy.outgoingNumber(it))
        }
        assertEquals("+15551234567", CallPolicy.outgoingNumber(" +1 (555) 123-4567 "))
    }

    @Test fun allowlistFailsClosedAndOnlyExplicitAllAllowsHiddenCallers() {
        val config = CallConfig(enabled = true, autoAnswer = true, credentialsConfigured = true, audioVerified = true)
        assertFalse(CallPolicy.callerAllowed(config, "15551234567"))
        assertFalse(CallPolicy.callerAllowed(config, null))
        val restricted = config.copy(allowedIncomingNumbers = setOf("+15551234567"))
        assertTrue(CallPolicy.callerAllowed(restricted, "+1 (555) 123-4567"))
        assertFalse(CallPolicy.callerAllowed(restricted, "5551234567"))
        val all = config.copy(allowAllIncoming = true)
        assertTrue(CallPolicy.callerAllowed(all, null))
        assertFalse(CallPolicy.callerAllowed(all.copy(autoAnswer = false), null))
        assertFalse(CallPolicy.callerAllowed(all.copy(credentialsConfigured = false), null))
        assertFalse(CallPolicy.callerAllowed(all.copy(audioVerified = false), null))
    }

    @Test fun requiresExplicitSelectionOnMultiSimDevices() {
        val sim1 = CallSim("1", "SIM 1", true)
        val sim2 = CallSim("2", "SIM 2", false)
        assertEquals(sim1, CallPolicy.chooseSim(listOf(sim1), null))
        assertNull(CallPolicy.chooseSim(listOf(sim1, sim2), null))
        assertEquals(sim1, CallPolicy.chooseSim(listOf(sim1, sim2), "default"))
        assertEquals(sim2, CallPolicy.chooseSim(listOf(sim1, sim2), "2"))
        assertNull(CallPolicy.chooseSim(listOf(sim1.copy(isDefault = false), sim2), "default"))
        assertNull(CallPolicy.chooseSim(listOf(sim1), "missing"))
    }

    @Test fun capturesAppOpBaselineWithoutGuessingOnUnknownOutput() {
        assertEquals("default", CallPolicy.previousAppOpMode("No operations.\n"))
        assertEquals("default", CallPolicy.previousAppOpMode("No operations.\nDefault mode: default\n"))
        assertNull(CallPolicy.previousAppOpMode("No operations.\nDefault mode: allow"))
        assertNull(CallPolicy.previousAppOpMode("No operations.\nPermission denial"))
        assertEquals("allow", CallPolicy.previousAppOpMode("MANAGE_ONGOING_CALLS: allow; time=1s"))
        assertEquals("ignore", CallPolicy.previousAppOpMode("Uid mode: MANAGE_ONGOING_CALLS: ignore"))
        assertNull(CallPolicy.previousAppOpMode("Permission denial"))
        assertNull(CallPolicy.previousAppOpMode("Uid mode: MANAGE_ONGOING_CALLS: deny\nMANAGE_ONGOING_CALLS: allow"))
    }
}
