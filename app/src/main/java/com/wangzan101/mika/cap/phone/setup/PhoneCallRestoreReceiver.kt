package com.wangzan101.mika.cap.phone.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.wangzan101.mika.MikaApp

/** Restores only a previously authorized incoming standby; never places or answers a call. */
class PhoneCallRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val unlocked = runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked == true }
            .getOrDefault(false)
        if (!PhoneCallRestorePolicy.accepts(intent.action, unlocked)) return
        val app = context.applicationContext as? MikaApp ?: return
        app.calls.restoreAuthorizedStandbyFromSystem()
    }
}
