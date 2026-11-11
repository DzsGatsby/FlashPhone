package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.ShellCommandRunner
import com.wangzan101.mika.settings.RecoverySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Phone-only recovery, deliberately separate from general assistant/accessibility recovery. */
class PhoneCallRecovery(
    context: Context,
    private val hub: HelperHub,
    private val manager: BuiltinShellManager,
    private val settings: RecoverySettings,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val owned = app.getSharedPreferences("phone_call_recovery", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(observed())
    val state: StateFlow<PhoneCallRecoveryState> = _state.asStateFlow()
    private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        // ACTION_USER_UNLOCKED is delivered only to dynamically registered receivers.
        val receiver = PhoneCallRestoreReceiver()
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        scope.launch {
            // Our own verified/applied writes must not cause a command/retry loop.
            combine(hub.shell, settings.consent.map { it.declined }.distinctUntilChanged(), ::Pair)
                .collect { lock.withLock { applyLocked() } }
        }
    }

    suspend fun setEnabled(on: Boolean): PermissionResult = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                settings.choosePhoneRecovery(on)
                applyLocked()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _state.value = observed().copy(failure = PhoneCallRecoveryFailure.SAVE_FAILED)
                return@withLock PermissionResult.Failed("Phone recovery settings could not be saved.")
            }
            when (_state.value.failure) {
                null -> PermissionResult.Done
                PhoneCallRecoveryFailure.HELPER_UNAVAILABLE -> PermissionResult.NoRunner
                else -> PermissionResult.Failed("Android did not confirm the phone recovery grants.")
            }
        }
    }

    /** Explicit user retry only: resetting the engine may allow a system Wi-Fi trust prompt. */
    fun retry() {
        scope.launch {
            lock.withLock { applyLocked() }
            if (settings.consent.value.wants(RecoveryCapability.AutoWirelessDebugging)) manager.engine.retry()
        }
    }

    /** Returning from system settings only rereads state; never resets a trust refusal or writes. */
    fun refresh() {
        scope.launch {
            lock.withLock {
                val previousFailure = _state.value.failure
                val shell = hub.shell.value
                val background = if (shell != null) grants(ShellCommandRunner { command ->
                    shell.runCommand(command)?.getString("output").orEmpty()
                }).backgroundAllowed() else false
                val observation = observed()
                val failure = when {
                    shell == null -> PhoneCallRecoveryFailure.HELPER_UNAVAILABLE
                    previousFailure == PhoneCallRecoveryFailure.SECURE_SETTINGS_REFUSED && observation.writeSecureSettings -> null
                    previousFailure == PhoneCallRecoveryFailure.BATTERY_REFUSED && observation.batteryExempt -> null
                    previousFailure == PhoneCallRecoveryFailure.BACKGROUND_REFUSED && background -> null
                    else -> previousFailure
                }
                _state.value = observation.copy(backgroundAllowed = background, failure = failure)
            }
        }
    }

    private suspend fun applyLocked() {
        val shell = hub.shell.value
        if (shell == null) {
            _state.value = observed().copy(failure = PhoneCallRecoveryFailure.HELPER_UNAVAILABLE)
            // Existing bounded auto-on can act with the persisted grant even while shell is gone.
            if (settings.consent.value.wants(RecoveryCapability.AutoWirelessDebugging)) manager.need()
            return
        }
        _state.value = observed().copy(applying = true)
        try {
            _state.value = grants(ShellCommandRunner { command ->
                check(hub.shell.value === shell) { "Phone helper changed." }
                shell.runCommand(command)?.getString("output").orEmpty()
            }).apply()
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            _state.value = observed().copy(failure = PhoneCallRecoveryFailure.SAVE_FAILED)
        }
    }

    private fun grants(runner: ShellCommandRunner) = PhoneRecoveryGrants(app.packageName, runner,
        consent = { settings.consent.value },
        applied = settings::recordVerifiedPhoneRecovery,
        canWriteSecure = ::canWriteSecure, batteryExempt = ::batteryExempt,
        original = { key -> owned.getString(key, null) },
        rememberOriginal = { key, value ->
            check(owned.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()) {
                "Phone recovery ownership could not be saved."
            }
        })

    private fun observed() = PhoneCallRecoveryState(
        enabled = settings.consent.value.wants(RecoveryCapability.AutoWirelessDebugging),
        writeSecureSettings = canWriteSecure(), batteryExempt = batteryExempt(),
    )

    private fun canWriteSecure(): Boolean =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt(): Boolean = runCatching {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
    }.getOrDefault(false)
}
