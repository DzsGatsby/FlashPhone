package com.wangzan101.mika.calls.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Internal handshake timeout is a reconnect condition; caller cancellation always propagates. */
internal suspend fun awaitDeviceHandshake(exchange: suspend () -> Unit): Boolean =
    withTimeoutOrNull(20_000L) { exchange(); true } == true

/** Only retries transferring the same recording, never any phone action. At most 3 HTTP attempts. */
internal suspend fun <T : Any> uploadDeviceRecording(authorized: () -> Boolean, transfer: suspend () -> T): T? {
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        if (!authorized()) return null
        try { return transfer() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Fixed bounded retries; the local encrypted recording remains intact. */ }
        if (attempt < 2) delay(if (attempt == 0) 2_000L else 10_000L)
    }
    return null
}
