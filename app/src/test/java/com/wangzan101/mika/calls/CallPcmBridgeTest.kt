package com.wangzan101.mika.calls

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallPcmBridgeTest {
    @Test fun `caller resuming during the final playback query cannot commit a stale hangup`() = runTest {
        val playbackQuery = CompletableDeferred<Boolean>()
        var committed = false
        val waiter = launch {
            // A Binder operation can still return a positive result after speech cancels the waiter.
            if (withContext(NonCancellable) { playbackQuery.await() }) {
                commitDrainedFarewell { committed = true; true }
            }
        }
        runCurrent()
        waiter.cancel()
        playbackQuery.complete(true)
        waiter.join()
        assertFalse(committed)
    }

    @Test fun `closing a blocked capture cannot replace the bridge deadline with an IO fault`() = runTest {
        val capture = BlockingCapture()
        val timed = async {
            withTimeoutOrNull(1000L) {
                coroutineScope {
                    val worker = launch(Dispatchers.IO) { callPipeIo { capture.read() } }
                    try {
                        awaitCancellation()
                    } finally {
                        worker.cancel()
                        capture.close()
                    }
                }
            }
        }
        try {
            runCurrent()
            assertTrue("Capture was blocked before reaching the timeout", capture.entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(1000L)
            runCurrent()
            assertNull("The Telecom duration-limit branch relies on a null timeout result", timed.await())
            assertEquals("Close woke up the blocked pipe read", 0L, capture.closeFault.count)
        } finally {
            capture.close()
        }
    }

    @Test fun `real IO failure still reaches the active call runtime`() = runTest {
        val fault = IOException("pipe failed while active")
        val result = runCatching { callPipeIo { throw fault } }
        assertEquals(fault, result.exceptionOrNull())
    }

    @Test fun `provider burst queues by PCM duration rather than packet count and preserves order`() = runTest {
        val queue = CallPlaybackQueue()
        // A previous successful Direct call averaged 389 ms per provider audio event.
        val packets = List(20) { index -> ByteArray(18_672) { (index + it).toByte() } }
        packets.forEachIndexed { index, packet -> assertEquals((index + 1) * packet.size, queue.offer(packet)) }
        val expected = packets.fold(ByteArray(0)) { acc, packet -> acc + packet }
        val played = ByteArray(expected.size)
        val buffer = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        var position = 0
        while (position < played.size) {
            val next = checkNotNull(queue.take(buffer))
            assertTrue(queue.isCurrent(next))
            assertTrue(next.byteCount <= CallPlaybackQueue.WRITE_BYTES)
            buffer.copyInto(played, position, 0, next.byteCount)
            position += next.byteCount
            queue.release(next)
        }
        assertArrayEquals(expected, played)
        queue.close()
    }

    @Test fun `healthy real time sink survives repeated bursts past thirty seconds`() = runTest {
        val queue = CallPlaybackQueue()
        val burstPacket = ByteArray(18_672) // 389 ms at the actual 24 kHz PCM16 format.
        val sink = ByteArray(CallPlaybackQueue.WRITE_BYTES)
        var written = 0L
        val writer = launch {
            while (true) {
                val block = queue.take(sink) ?: break
                delay((block.byteCount * 1_000L + 47_999L) / 48_000L)
                written += block.byteCount
                queue.release(block)
            }
        }
        try {
            repeat(12) {
                // Six deltas land together; each reply drains at media rate before the next burst.
                repeat(6) { assertTrue(queue.offer(burstPacket) != null) }
                advanceTimeBy(3_000L)
                runCurrent()
            }
            assertEquals(36_000L, currentTime)
            assertEquals(72L * burstPacket.size, written)
        } finally {
            queue.close()
            writer.join()
        }
    }

    @Test fun `blocked writer counts against cap and interruption clears only stale playback`() = runTest {
        val blockBytes = CallPlaybackQueue.WRITE_BYTES
        val queue = CallPlaybackQueue(blockBytes * 2)
        val sink = ByteArray(blockBytes)
        val stale = ByteArray(blockBytes * 2) { 12 }
        assertEquals(stale.size, queue.offer(stale))
        val blocked = checkNotNull(queue.take(sink)) // Deliberately do not release: output pipe is blocked.
        assertNull(queue.offer(ByteArray(2))) // In-flight bytes still count, so rejection is bounded.
        queue.invalidate()
        assertFalse(queue.isCurrent(blocked))
        val nextPcm = ByteArray(blockBytes) { 42 }
        assertEquals(nextPcm.size, queue.offer(nextPcm))
        queue.release(blocked)
        val next = checkNotNull(queue.take(sink))
        assertTrue(queue.isCurrent(next))
        assertArrayEquals(nextPcm, sink)
        queue.release(next)
        queue.close()
    }

    @Test fun `a silent opener does not mistake absent playback for a dead bridge`() {
        assertFalse(callAudioHasStarted("", downBytes = 0, upBytes = 0))
        assertTrue(callAudioHasStarted("", downBytes = 640, upBytes = 0))
        assertFalse(callAudioHasStarted("开场白", downBytes = 640, upBytes = 0))
        assertTrue(callAudioHasStarted("开场白", downBytes = 640, upBytes = 960))
    }

    private class BlockingCapture : InputStream() {
        val entered = CountDownLatch(1)
        val closeFault = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "Test did not close the blocked stream" }
            closeFault.countDown()
            throw IOException("pipe closed")
        }

        override fun close() { closed.countDown() }
    }
}
