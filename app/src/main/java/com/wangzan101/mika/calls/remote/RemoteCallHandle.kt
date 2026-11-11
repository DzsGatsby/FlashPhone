package com.wangzan101.mika.calls.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Two lifetimes: the AI work may finish before Telecom confirms that the carrier call ended. */
internal data class RemoteCallSnapshot(
    val phase: String = "preparing",
    val workId: String? = null,
    val callId: String? = null,
    val carrierEnded: Boolean = false,
    val aiActive: Boolean = false,
    val reason: String? = null,
    val workFinished: Boolean = false,
    val issued: Boolean = false,
) {
    fun acceptsControl(expectedCallId: String?, hangUp: Boolean): Boolean =
        !terminal && callId == expectedCallId && (!hangUp || expectedCallId != null)
    val terminal: Boolean get() = phase in setOf("ended", "failed", "canceled", "interrupted")
}

internal class RemoteCallHandle(
    val taskId: String,
    val expiresAt: Long,
    private val authorized: () -> Boolean,
) {
    var cancelRequested: Boolean = false
    private val mutable = MutableStateFlow(RemoteCallSnapshot())
    val state = mutable.asStateFlow()
    fun allowed(): Boolean = !cancelRequested && authorized()
    fun update(snapshot: RemoteCallSnapshot) {
        // A late work cleanup must never replace an already confirmed carrier end.
        val old = mutable.value
        mutable.value = if (old.terminal) old.copy(workFinished = old.workFinished || snapshot.workFinished,
            reason = snapshot.reason ?: old.reason) else snapshot
    }
}
