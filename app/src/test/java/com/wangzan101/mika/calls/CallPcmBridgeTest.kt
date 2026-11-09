package com.wangzan101.mika.calls

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallPcmBridgeTest {
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
