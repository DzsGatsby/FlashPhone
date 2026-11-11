package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.ShellCommandRunner
import kotlinx.coroutines.CancellationException

enum class PhoneCallRecoveryFailure {
    HELPER_UNAVAILABLE, SECURE_SETTINGS_REFUSED, BATTERY_REFUSED, BACKGROUND_REFUSED, SAVE_FAILED,
}

data class PhoneCallRecoveryState(
    val enabled: Boolean = true,
    val writeSecureSettings: Boolean = false,
    val batteryExempt: Boolean = false,
    val backgroundAllowed: Boolean = false,
    val applying: Boolean = false,
    val failure: PhoneCallRecoveryFailure? = null,
)

/** Only this package's recovery grants. No root, accessibility or general shell input. */
internal class PhoneRecoveryGrants(
    private val packageName: String,
    private val runner: ShellCommandRunner,
    private val consent: () -> RecoveryConsent,
    private val applied: (RecoveryCapability, Boolean) -> Unit,
    private val canWriteSecure: () -> Boolean,
    private val batteryExempt: () -> Boolean,
    private val original: (String) -> String?,
    private val rememberOriginal: (String, String?) -> Unit,
) {
    init { require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) }

    suspend fun apply(): PhoneCallRecoveryState {
        var failure: PhoneCallRecoveryFailure? = null
        fun fail(value: PhoneCallRecoveryFailure) { if (failure == null) failure = value }
        val secureCaps = listOf(RecoveryCapability.AutoWirelessDebugging, RecoveryCapability.RestoreAfterReboot)
        if (secureCaps.any { consent().wants(it) }) {
            if (!canWriteSecure()) {
                rememberIfAbsent(SECURE, "denied")
                checked(PermissionCommands.grantWriteSecureSettings(packageName))
            }
            val verified = canWriteSecure()
            if (!verified) fail(PhoneCallRecoveryFailure.SECURE_SETTINGS_REFUSED)
            secureCaps.forEach { applied(it, verified && consent().wants(it)) }
        } else {
            if (original(SECURE) == "denied") {
                if (canWriteSecure()) checked(PermissionCommands.revokeWriteSecureSettings(packageName))
                if (!canWriteSecure()) rememberOriginal(SECURE, null)
                else fail(PhoneCallRecoveryFailure.SECURE_SETTINGS_REFUSED)
            }
            secureCaps.forEach { applied(it, false) }
        }

        val battery = RecoveryCapability.BatteryWhitelist
        val wantsBackground = consent().wants(battery)
        if (wantsBackground && !batteryExempt()) {
            rememberIfAbsent(BATTERY, "restricted")
            checked(PermissionCommands.whitelistAdd(packageName))
        } else if (!wantsBackground && original(BATTERY) == "restricted") {
            if (batteryExempt()) checked(PermissionCommands.whitelistRemove(packageName))
            if (!batteryExempt()) rememberOriginal(BATTERY, null)
        }
        if (wantsBackground && !batteryExempt() || !wantsBackground && original(BATTERY) != null && batteryExempt()) {
            fail(PhoneCallRecoveryFailure.BATTERY_REFUSED)
        }
        var allBackgroundAllowed = true
        for (op in BACKGROUND_OPS) {
            var mode = mode(op)
            if (wantsBackground && mode != "allow") {
                if (mode != null) {
                    rememberIfAbsent(op, mode)
                    checked("cmd appops set $packageName $op allow")
                    mode = mode(op)
                }
                if (mode != "allow") fail(PhoneCallRecoveryFailure.BACKGROUND_REFUSED)
            } else if (!wantsBackground) {
                val previous = original(op)
                if (previous != null) {
                    if (previous !in MODES) {
                        fail(PhoneCallRecoveryFailure.BACKGROUND_REFUSED)
                        allBackgroundAllowed = false
                        continue
                    }
                    // A later explicit system choice belongs to the owner; only undo our allow.
                    if (mode == "allow") {
                        checked("cmd appops set $packageName $op $previous")
                        mode = mode(op)
                    }
                    if (mode != null && mode != "allow" || mode == previous) rememberOriginal(op, null)
                    else fail(PhoneCallRecoveryFailure.BACKGROUND_REFUSED)
                }
            }
            allBackgroundAllowed = allBackgroundAllowed && mode == "allow"
        }
        applied(battery, wantsBackground && batteryExempt() && allBackgroundAllowed)
        return PhoneCallRecoveryState(
            enabled = consent().wants(RecoveryCapability.AutoWirelessDebugging),
            writeSecureSettings = canWriteSecure(), batteryExempt = batteryExempt(),
            backgroundAllowed = allBackgroundAllowed, failure = failure,
        )
    }

    suspend fun backgroundAllowed(): Boolean = BACKGROUND_OPS.map { mode(it) }.all { it == "allow" }

    private fun rememberIfAbsent(key: String, value: String) {
        if (original(key) == null) rememberOriginal(key, value)
    }

    private suspend fun checked(command: String): String? = try {
        runner.exec(PermissionCommands.checked(command)).takeIf { PermissionCommands.exitCode(it) == 0 }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private suspend fun mode(op: String): String? = checked("cmd appops get $packageName $op")?.let { parseMode(op, it) }

    companion object {
        private const val SECURE = "secure"
        private const val BATTERY = "battery"
        private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")
        private val BACKGROUND_OPS = listOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")

        internal fun parseMode(op: String, output: String): String? {
            if (op !in BACKGROUND_OPS) return null
            val modes = Regex("(?:^|\\s)" + Regex.escape(op) + ":\\s*(allow|ignore|deny|default|foreground)(?=;|\\s|$)")
                .findAll(output).map { it.groupValues[1] }.toList()
            if (modes.isNotEmpty()) return modes.singleOrNull()
            return if (output.lineSequence().any { it.trim() == "No operations." }) "default" else null
        }
    }
}
