package com.wangzan101.mika.calls

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.wangzan101.mika.MikaApp

/** Non-UI companion service; the user's dialer owns the native call screens and ring tone. */
class MikaInCallService : InCallService() {
    private val runtime get() = (application as MikaApp).calls
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = runtime.onCallChanged(call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = runtime.onCallChanged(call)
    }
    private val attached = mutableSetOf<Call>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        attached.add(call)
        call.registerCallback(callback)
        runtime.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        attached.remove(call)
        runtime.onCallRemoved(call)
        super.onCallRemoved(call)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCalls()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCalls()
        super.onDestroy()
    }

    private fun clearCalls() {
        attached.toList().forEach { call ->
            call.unregisterCallback(callback)
            runtime.onCallRemoved(call)
        }
        attached.clear()
    }
}
