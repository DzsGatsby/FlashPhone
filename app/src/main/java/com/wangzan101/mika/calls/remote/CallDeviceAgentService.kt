package com.wangzan101.mika.calls.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Independently opted-in foreground connection. Restart restores transport, never a phone action. */
class CallDeviceAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var promoted = false
    private var stopping = false
    internal val leaseAvailable get() = promoted && !stopping
    private var observing = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val agent = (application as MikaApp).callDeviceAgent
        val explicit = intent?.action == ACTION_START && agent.expectedStart(intent.getStringExtra(EXTRA_REQUEST))
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "远程电话任务", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        // Always acknowledge a delivered startForegroundService before rejecting a stale request.
        if (!promoted) promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification("正在检查设备账号"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification("正在检查设备账号"))
        }.isSuccess
        if (!promoted || stopping || intent != null && !explicit || manager?.areNotificationsEnabled() != true ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            agent.startFailed(); stopAgent(); return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                if (!agent.serviceStarted(this@CallDeviceAgentService)) { stopAgent(); return@launch }
                agent.state.collect { state ->
                    if (!state.enabled) stopAgent()
                    else if (runCatching { manager.notify(ID, notification(if (state.connected) "设备账号已在线，可接收已授权的电话任务" else state.detail)) }.isFailure) stopAgent()
                }
            }
        }
        return START_STICKY
    }
    internal fun stopAgent() { stopping = true; stopSelf() }
    override fun onDestroy() {
        stopping = true
        (application as MikaApp).callDeviceAgent.serviceStopped(this)
        scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disable = PendingIntent.getBroadcast(this, ID, Intent(this, CallDeviceAgentReceiver::class.java)
            .setAction(ACTION_DISABLE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.app_name)).setContentText(detail).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "关闭远程任务", disable).build()).build()
    }
    companion object {
        private const val CHANNEL = "flashphone_remote_tasks"
        private const val ID = 90_119
        private const val ACTION_START = "com.wangzan101.mika.calls.remote.START"
        internal const val ACTION_DISABLE = "com.wangzan101.mika.calls.remote.DISABLE"
        private const val EXTRA_REQUEST = "request_id"
        internal fun startIntent(context: Context, id: String) = Intent(context, CallDeviceAgentService::class.java)
            .setAction(ACTION_START).putExtra(EXTRA_REQUEST, id)
    }
}

class CallDeviceAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val agent = (context.applicationContext as? MikaApp)?.callDeviceAgent ?: return
        if (intent.action == CallDeviceAgentService.ACTION_DISABLE) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Main.immediate).launch { try { agent.setEnabled(false) } finally { pending.finish() } }
        } else if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            agent.restoreAuthorizedConnection()
        }
    }
}
