package com.wangzan101.mika.calls

import android.app.ActivityManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.settings.FeatureFlags
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's real job and foreground service in a disposable emulator without an Activity or a call. */
@RunWith(AndroidJUnit4::class)
class CallStandbyRecoveryEmulatorTest {
    @Test fun periodicJobStartsRestoresAndStopsWithSavedStandby() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("standbyRecoveryEmulator") == "true")
        assumeTrue(instrumentation.targetContext.packageName == "com.wangzan101.mika.dev")
        assumeTrue("This test must run on the isolated emulator", shell(instrumentation, "getprop ro.kernel.qemu") == "1")

        val app = instrumentation.targetContext.applicationContext as MikaApp
        assertTrue("The emulator user must be unlocked", app.getSystemService(UserManager::class.java)!!.isUserUnlocked)
        assertFalse("The fixture must not open an Activity", app.foreground.visible.value)
        assertFalse("Use an emulator without a prior explicit exit", FeatureFlags.get(app).quitRequested())
        val notifications = app.getSystemService(NotificationManager::class.java)!!
        assertTrue("Allow notifications for the emulator app before running this test", notifications.areNotificationsEnabled())
        val hadChannel = notifications.getNotificationChannel(CallStandbyService.CHANNEL) != null
        val power = app.getSystemService(PowerManager::class.java)!!
        val originallyExempt = power.isIgnoringBatteryOptimizations(app.packageName)
        val scheduler = app.getSystemService(JobScheduler::class.java)!!
        val jobComponent = ComponentName(app, CallStandbyRecoveryJob::class.java)
        val standbyComponent = ComponentName(app, CallStandbyService::class.java)
        val activity = app.getSystemService(ActivityManager::class.java)!!
        val runtime = withContext(Dispatchers.Main.immediate) { app.calls }
        val original = runtime.settings.state.value
        assertFalse("Do not replace an existing phone credential", original.credentialsConfigured)
        assertFalse("Do not change a device with automatic answering enabled", original.autoAnswer)
        assertFalse("Do not run with an enrolled calling device", app.callDeviceAgent.state.value.enrolled)
        assertFalse("Do not run with an audio helper configured", app.phone.shellControl.paired)
        assertFalse("Do not run with an active audio helper", runtime.state.value.shellConnected)
        assertNull("Use an emulator without an existing standby job", findJob(scheduler, jobComponent))
        assertNull("Use an emulator without an existing standby service", runningService(activity, standbyComponent))

        val callPrefs = PreferenceSnapshot(app.getSharedPreferences("mika_call_settings", Context.MODE_PRIVATE))
        val recoveryPrefs = PreferenceSnapshot(app.getSharedPreferences("call_standby_recovery", Context.MODE_PRIVATE))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "mika-volc-realtime-calls"
        val hadKey = keyStore.containsAlias(alias)
        try {
            if (!originallyExempt) shell(instrumentation, "cmd deviceidle whitelist +${app.packageName}")
            await("Android did not grant the emulator battery exemption") {
                Unit.takeIf { CallStandbyRecoveryJob.allowsScheduledForegroundStart(app) }
            }
            withContext(Dispatchers.Main.immediate) {
                CallStandbyRecoveryJob.acknowledgeUserOpen(app)
                runtime.settings.saveCredentials("", "", "synthetic-standby-emulator")
                runtime.settings.save(original.copy(
                    enabled = true,
                    autoAnswer = true,
                    audioVerified = true,
                    allowAllIncoming = false,
                    allowedIncomingNumbers = setOf("+12025550123"),
                    // Waiting must never answer an incidental emulator call or connect to a model.
                    autoAnswerPausedUntilMs = System.currentTimeMillis() + 180_000L,
                ))
                assertTrue(runtime.hasAuthorizedIncomingStandby())
            }
            val job = await("The app did not register the periodic job") { findJob(scheduler, jobComponent) }
            assertTrue(job.isPeriodic)
            assertTrue(job.isPersisted)
            assertEquals(15 * 60_000L, job.intervalMillis)
            // Android can dispatch the first periodic run immediately after registration.

            runJob(instrumentation, app, job)
            val first = await("The first scheduled job did not start foreground standby") {
                runningService(activity, standbyComponent)
                    ?.takeIf { it.foreground && it.started && runtime.state.value.backgroundStandbyRunning }
            }
            assertFalse("The job must start standby without an Activity", app.foreground.visible.value)

            runJob(instrumentation, app, job)
            val sameService = checkNotNull(runningService(activity, standbyComponent))
            assertEquals(first.activeSince, sameService.activeSince)
            assertEquals("An already-running service must not get another start request", first.lastActivityTime, sameService.lastActivityTime)
            assertEquals(job.id, findJob(scheduler, jobComponent)?.id)

            withContext(Dispatchers.Main.immediate) {
                assertTrue(app.stopService(Intent(app, CallStandbyService::class.java)))
            }
            await("The test service did not stop") {
                Unit.takeIf { runningService(activity, standbyComponent) == null && !runtime.state.value.backgroundStandbyRunning }
            }
            runJob(instrumentation, app, job)
            val restored = await("The scheduled job did not restore standby after the service stopped") {
                runningService(activity, standbyComponent)
                    ?.takeIf { it.foreground && it.started && runtime.state.value.backgroundStandbyRunning }
            }
            assertTrue(restored.activeSince > first.activeSince)
            assertFalse(app.foreground.visible.value)

            withContext(Dispatchers.Main.immediate) {
                runtime.settings.save(runtime.settings.state.value.copy(autoAnswer = false))
            }
            await("Revocation did not cancel both the scheduled job and the foreground service") {
                Unit.takeIf { findJob(scheduler, jobComponent) == null &&
                    runningService(activity, standbyComponent) == null && !runtime.state.value.backgroundStandbyRunning }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        runtime.settings.save(original)
                        runtime.settings.saveCredentials("", "", "")
                    }
                } finally {
                    app.stopService(Intent(app, CallStandbyService::class.java))
                    CallStandbyRecoveryJob.reconcile(app, authorized = false)
                    try {
                        callPrefs.restore()
                        recoveryPrefs.restore()
                        if (!hadChannel) notifications.deleteNotificationChannel(CallStandbyService.CHANNEL)
                        if (!hadKey && keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                    } finally {
                        if (!originallyExempt) shell(instrumentation, "cmd deviceidle whitelist -${app.packageName}")
                    }
                }
                await("The emulator battery exemption was not restored") {
                    Unit.takeIf { power.isIgnoringBatteryOptimizations(app.packageName) == originallyExempt }
                }
                assertNull(findJob(scheduler, jobComponent))
            }
        }
    }

    private fun findJob(scheduler: JobScheduler, component: ComponentName): JobInfo? =
        scheduler.allPendingJobs.filter { it.service == component }.let { jobs ->
            check(jobs.size <= 1) { "Android has more than one standby recovery job" }
            jobs.singleOrNull()
        }

    @Suppress("DEPRECATION")
    private fun runningService(activity: ActivityManager, component: ComponentName): ActivityManager.RunningServiceInfo? =
        activity.getRunningServices(Int.MAX_VALUE).singleOrNull { it.service == component }

    private suspend fun runJob(instrumentation: Instrumentation, app: MikaApp, job: JobInfo) {
        // The emulator's command response only acknowledges dispatch, so wait for its own
        // job history to show that the JobService completed the callback.
        val component = job.service.flattenToShortString()
        fun completedRuns(): Int = shell(instrumentation, "dumpsys jobscheduler ${app.packageName}")
            .lineSequence().count { "STOP-P:" in it && "/${job.id} " in it && component in it && "onStartJob returned false" in it }

        val before = completedRuns()
        val user = shell(instrumentation, "am get-current-user").toInt()
        val output = shell(instrumentation, "cmd jobscheduler run -f -u $user ${app.packageName} ${job.id}")
        assertTrue("Android did not dispatch the requested standby job: $output", "Running job" in output)
        await("Android did not record completion of the standby job") { Unit.takeIf { completedRuns() > before } }
    }

    private fun shell(instrumentation: Instrumentation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private suspend fun <T : Any> await(message: String, read: () -> T?): T = withTimeoutOrNull(7_000) {
        var result = read()
        while (result == null) {
            delay(100)
            result = read()
        }
        result
    } ?: throw AssertionError(message)

    private class PreferenceSnapshot(private val preferences: SharedPreferences) {
        private val values = preferences.all

        @Suppress("UNCHECKED_CAST")
        fun restore() {
            val editor = preferences.edit().clear()
            for ((key, value) in values) when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                is String -> editor.putString(key, value)
                else -> error("Unsupported preference type")
            }
            check(editor.commit() && preferences.all == values) { "Emulator preferences could not be restored" }
        }
    }
}
