package com.wangzan101.mika.calls

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.wangzan101.mika.MikaApp

/** Periodic backup for devices that drop the normal sticky foreground-service restart. */
class CallStandbyRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // JobService and the runtime share the main thread. Keep this callback alive until
        // Android has been asked to start the service or the runtime has declined the request.
        (application as MikaApp).calls.restoreAuthorizedStandbyFromSystem(requireBatteryExemption = true)
        return false
    }

    // The callback queues no job-owned work, and later checks use the normal periodic schedule.
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 0x43414C52
        private const val INTERVAL_MS = 15 * 60 * 1_000L
        private const val PREFS = "call_standby_recovery"
        private const val LAST_OPENED = "last_opened"
        private const val USER_STOPPED = "user_stopped"

        /** Keep the existing interval across app/service restarts. The OS can defer periodic work. */
        internal fun reconcile(context: Context, authorized: Boolean) {
            runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                if (!authorized || !allowsScheduledForegroundStart(context) || prefs(context).getBoolean(USER_STOPPED, false)) {
                    scheduler.cancel(JOB_ID)
                } else if (scheduler.getPendingJob(JOB_ID) == null) {
                    val info = JobInfo.Builder(JOB_ID, ComponentName(context, CallStandbyRecoveryJob::class.java))
                        .setPeriodic(INTERVAL_MS)
                        .setPersisted(true)
                        .build()
                    check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS)
                }
            }.onFailure { Log.w("CallStandby", "Could not configure the Android standby recovery job", it) }
        }

        internal fun acknowledgeUserOpen(context: Context) {
            prefs(context).edit().putLong(LAST_OPENED, System.currentTimeMillis()).remove(USER_STOPPED).apply()
        }

        internal fun allowsScheduledForegroundStart(context: Context): Boolean = runCatching {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == false &&
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

        internal fun allowsBackgroundRecovery(context: Context): Boolean {
            val state = prefs(context)
            if (state.getBoolean(USER_STOPPED, false)) return false
            val lastOpened = state.getLong(LAST_OPENED, 0L)
            val userStopped = runCatching {
                val manager = checkNotNull(context.getSystemService(ActivityManager::class.java))
                // Before the first recorded opening, only the newest exit is reliable for
                // deciding whether the existing install was stopped by the owner.
                val maxRecords = if (lastOpened > 0L) 0 else 1
                manager.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords).any {
                    explicitStopSinceOpen(it.reason, it.timestamp, lastOpened)
                }
            }.getOrElse { return false }
            if (!userStopped) return true
            // Task Manager stops the app but can leave periodic jobs scheduled. Remember the
            // stop so it continues to count even after Android rotates the exit history.
            state.edit().putBoolean(USER_STOPPED, true).apply()
            reconcile(context, authorized = false)
            return false
        }

        internal fun explicitStopSinceOpen(reason: Int, exitedAt: Long, lastOpened: Long): Boolean =
            reason == ApplicationExitInfo.REASON_USER_REQUESTED && exitedAt >= lastOpened

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
