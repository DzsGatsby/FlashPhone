package com.wangzan101.mika.calls

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A single 640-byte frame in flight, targeting its absolute 20 ms media deadline.
 * Ordinary encode/scheduling overhead must not accumulate as capture latency. Larger overruns
 * rebase the clock instead of bursting buffered PCM to catch up. A blocked websocket send also
 * rebases after completion. The existing bounded pipe owns the capture-backpressure limit.
 */
internal class CallInputPacer(
    private val nowNanos: () -> Long = System::nanoTime,
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
) {
    private var nextFrameNanos: Long? = null

    suspend fun send(accept: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        nextFrameNanos?.let { deadline ->
            while (true) {
                val remaining = deadline - nowNanos()
                if (remaining <= 0) break
                sleepMs((remaining + NANOS_PER_MS - 1) / NANOS_PER_MS)
                currentCoroutineContext().ensureActive()
            }
        }
        currentCoroutineContext().ensureActive()
        val started = nowNanos()
        val planned = nextFrameNanos ?: started
        // Keeping a deadline almost a whole frame in the past would cause a rapid next send.
        val slot = if (started - planned >= REBASE_LAG_NANOS) started else planned
        val accepted = accept()
        if (accepted) {
            val finished = nowNanos()
            nextFrameNanos = if (finished - slot >= FRAME_NANOS) finished + FRAME_NANOS
                else slot + FRAME_NANOS
        }
        return accepted
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val FRAME_NANOS = 20 * NANOS_PER_MS
        const val REBASE_LAG_NANOS = FRAME_NANOS / 2
    }
}
