package com.wangzan101.mika.cap.phone.setup

internal object PhoneCallRestorePolicy {
    fun accepts(action: String?, userUnlocked: Boolean): Boolean = userUnlocked && action in setOf(
        "android.intent.action.BOOT_COMPLETED", "android.intent.action.USER_UNLOCKED",
        "android.intent.action.MY_PACKAGE_REPLACED",
    )
}
