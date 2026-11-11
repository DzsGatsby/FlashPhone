package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.ShellCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneRecoveryGrantsTest {
    private class Phone {
        var consent = RecoveryConsent()
        var secure = false
        var battery = false
        var ignoreWrites = false
        val modes = mutableMapOf("RUN_IN_BACKGROUND" to "default", "RUN_ANY_IN_BACKGROUND" to "foreground")
        val original = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val grants = PhoneRecoveryGrants("com.example.phone", ShellCommandRunner { checked ->
            val command = checked.substringBefore(" 2>&1;")
            commands += command
            val fields = command.split(' ')
            val output = when {
                command.startsWith("pm grant ") -> { if (!ignoreWrites) secure = true; "" }
                command.startsWith("pm revoke ") -> { if (!ignoreWrites) secure = false; "" }
                command == "cmd deviceidle whitelist +com.example.phone" -> { if (!ignoreWrites) battery = true; "" }
                command == "cmd deviceidle whitelist -com.example.phone" -> { if (!ignoreWrites) battery = false; "" }
                command.startsWith("cmd appops set ") -> { if (!ignoreWrites) modes[fields[4]] = fields[5]; "" }
                command.startsWith("cmd appops get ") -> "${fields[4]}: ${modes[fields[4]]}; time=none"
                else -> error("Unexpected recovery command: $command")
            }
            "$output\n${PermissionCommands.EXIT_MARK}0"
        }, { consent }, { capability, verified ->
            consent = consent.withApplied(capability, verified && consent.wants(capability))
        }, { secure }, { battery }, { original[it] }, { key, value ->
            if (value == null) original.remove(key) else original[key] = value
        })
    }

    @Test fun untouchedPhoneAppliesOnlyOwnRecoveryGrantsAndVerifiesThem() = runBlocking {
        val phone = Phone()
        val state = phone.grants.apply()
        assertNull(state.failure)
        assertTrue(state.writeSecureSettings && state.batteryExempt && state.backgroundAllowed)
        assertEquals(setOf(RecoveryCapability.AutoWirelessDebugging, RecoveryCapability.RestoreAfterReboot,
            RecoveryCapability.BatteryWhitelist), phone.consent.applied)
        assertTrue(phone.commands.all { "com.example.phone" in it })
        assertTrue(phone.commands.none { "accessibility" in it || "su " in it || "settings put" in it })
        phone.commands.clear()
        phone.grants.apply()
        assertTrue(phone.commands.all { it.startsWith("cmd appops get ") })
    }

    @Test fun successfulExitWithoutSystemReadbackNeverBecomesApplied() = runBlocking {
        val phone = Phone().apply { ignoreWrites = true }
        val state = phone.grants.apply()
        assertEquals(PhoneCallRecoveryFailure.SECURE_SETTINGS_REFUSED, state.failure)
        assertFalse(state.writeSecureSettings || state.batteryExempt || state.backgroundAllowed)
        assertTrue(phone.consent.applied.isEmpty())
    }

    @Test fun explicitDeclineRestoresOnlyChangesMadeByThisBootstrap() = runBlocking {
        val phone = Phone()
        phone.grants.apply()
        phone.consent = RecoveryConsent(declined = setOf(RecoveryCapability.AutoWirelessDebugging,
            RecoveryCapability.RestoreAfterReboot, RecoveryCapability.BatteryWhitelist))
        val state = phone.grants.apply()
        assertNull(state.failure)
        assertFalse(state.enabled || state.writeSecureSettings || state.batteryExempt || state.backgroundAllowed)
        assertEquals("default", phone.modes["RUN_IN_BACKGROUND"])
        assertEquals("foreground", phone.modes["RUN_ANY_IN_BACKGROUND"])
        assertTrue(phone.original.isEmpty())
        assertTrue(phone.consent.applied.isEmpty())
    }

    @Test fun existingOwnerGrantsSurviveDisableAndOneSharedSecureChoiceCanRemainOn() = runBlocking {
        val phone = Phone().apply {
            secure = true; battery = true
            modes.replaceAll { _, _ -> "allow" }
        }
        phone.grants.apply()
        phone.consent = phone.consent.chose(RecoveryCapability.AutoWirelessDebugging, false)
            .chose(RecoveryCapability.BatteryWhitelist, false)
        phone.commands.clear()
        val state = phone.grants.apply()
        assertTrue(state.writeSecureSettings && state.batteryExempt && state.backgroundAllowed)
        assertFalse(state.enabled)
        assertTrue(phone.commands.all { it.startsWith("cmd appops get ") })
        assertTrue(phone.consent.isApplied(RecoveryCapability.RestoreAfterReboot))
        assertFalse(phone.consent.isApplied(RecoveryCapability.AutoWirelessDebugging))
    }

    @Test fun unknownAppOpsOutputIsNotAnAllowAndSavedModesCannotInjectACommand() = runBlocking {
        assertEquals("default", PhoneRecoveryGrants.parseMode("RUN_IN_BACKGROUND", "No operations.\n__mika_exit=0"))
        assertEquals("ignore", PhoneRecoveryGrants.parseMode("RUN_ANY_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND: ignore; time=2s"))
        assertNull(PhoneRecoveryGrants.parseMode("RUN_IN_BACKGROUND", "Unknown operation"))
        assertNull(PhoneRecoveryGrants.parseMode("CAMERA", "CAMERA: allow"))
        assertNull(PhoneRecoveryGrants.parseMode("RUN_IN_BACKGROUND", "RUN_IN_BACKGROUND: allow\nRUN_IN_BACKGROUND: ignore"))
        val phone = Phone().apply {
            consent = RecoveryConsent(declined = RecoveryCapability.entries.toSet())
            modes["RUN_IN_BACKGROUND"] = "allow"
            original["RUN_IN_BACKGROUND"] = "allow; unexpected"
        }
        assertEquals(PhoneCallRecoveryFailure.BACKGROUND_REFUSED, phone.grants.apply().failure)
        assertTrue(phone.commands.all { it.startsWith("cmd appops get ") })
    }

    @Test fun onlySystemRecoveryEventsAfterFirstUnlockCanRestoreIncomingStandby() {
        val actions = listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.USER_UNLOCKED",
            "android.intent.action.MY_PACKAGE_REPLACED")
        actions.forEach {
            assertFalse(PhoneCallRestorePolicy.accepts(it, false))
            assertTrue(PhoneCallRestorePolicy.accepts(it, true))
        }
        assertFalse(PhoneCallRestorePolicy.accepts("android.intent.action.LOCKED_BOOT_COMPLETED", true))
        assertFalse(PhoneCallRestorePolicy.accepts("android.intent.action.PHONE_STATE", true))
        assertFalse(PhoneCallRestorePolicy.accepts(null, true))
    }
}
