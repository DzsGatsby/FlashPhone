package com.wangzan101.mika.about

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** GitHub hosts the release and APK. No tokens, telemetry, polling server or exact alarm. */
object UpdateDelivery {
    const val JOB_ID = 0x4d5501
    const val NOTIFICATION_ID = 0x4d5502
    const val INTERVAL_MS = 15 * 60 * 1000L
    private const val CHANNEL = "app-updates"

    fun schedule(context: Context) {
        if (FeatureFlags.get(context).quitRequested()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Do not restart the interval on every Activity resume.
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS, 5 * 60 * 1000L)
            .setPersisted(true)
            .build())
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun notify(context: Context, release: ReleaseInfo?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (release == null) { manager.cancel(NOTIFICATION_ID); return }
        if (release.apkUrl == null || FeatureFlags.get(context).quitRequested()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.about_check), NotificationManager.IMPORTANCE_LOW))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val prefs = context.getSharedPreferences("flash-phone-update-notification", Context.MODE_PRIVATE)
        if (prefs.getString("version", null) == release.version) return
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(tr(R.string.about_update_title, release.version))
            .setContentText(tr(R.string.about_check_sub_new))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
        prefs.edit().putString("version", release.version).apply()
    }
}

/** Android may defer periodic jobs in Doze; this is discovery, not guaranteed realtime push. */
class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (FeatureFlags.get(this).quitRequested()) return false
        work = scope.launch {
            try { (application as MikaApp).updates.autoCheck() }
            finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        // A bounded later periodic attempt; no immediate retry loop.
        return false
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
