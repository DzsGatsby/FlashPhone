package com.wangzan101.mika.calls.remote

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDeviceRetryTest {
    @Test fun helloTimeoutReturnsReconnectConditionInsteadOfCancelingTheConnectionOwner() = runTest {
        assertFalse(awaitDeviceHandshake { awaitCancellation() })
        assertEquals(20_000L, currentTime)
        currentCoroutineContext().ensureActive()
        assertTrue(awaitDeviceHandshake { })
    }
    @Test fun externalCancellationDuringHelloIsNeverTurnedIntoANormalTimeout() = runTest {
        var returned = false
        val waiting = launch { awaitDeviceHandshake { awaitCancellation() }; returned = true }
        runCurrent(); waiting.cancelAndJoin()
        advanceTimeBy(21_000L); runCurrent()
        assertTrue(waiting.isCancelled)
        assertFalse(returned)
    }
    @Test fun transientUploadRetriesTheSameTransferOnlyAtTwoAndTwelveSeconds() = runTest {
        val at = mutableListOf<Long>(); val hashes = mutableListOf<String>()
        val sameFileHash = "synthetic-content-sha"
        val result = uploadDeviceRecording({ true }) {
            at += currentTime; hashes += sameFileHash
            if (at.size < 3) throw IOException("synthetic failure")
            "uploaded"
        }
        assertEquals("uploaded", result)
        assertEquals(listOf(0L, 2_000L, 12_000L), at)
        assertEquals(listOf(sameFileHash, sameFileHash, sameFileHash), hashes)
    }
    @Test fun permanentUploadFailureHasThreeAttemptsAndNoUnboundedRetry() = runTest {
        var attempts = 0
        assertNull(uploadDeviceRecording<String>({ true }) { attempts++; throw IOException("synthetic failure") })
        assertEquals(3, attempts)
        assertEquals(12_000L, currentTime)
    }
    @Test fun disablingRemoteOrCancelingStopsBeforeTheNextUploadAttempt() = runTest {
        var enabled = true; var attempts = 0
        val disabled = async { uploadDeviceRecording<String>({ enabled }) { attempts++; throw IOException("synthetic failure") } }
        runCurrent(); enabled = false
        assertNull(disabled.await())
        assertEquals(1, attempts)
        val canceled = async { uploadDeviceRecording<String>({ true }) { attempts++; throw IOException("synthetic failure") } }
        runCurrent(); canceled.cancelAndJoin()
        advanceTimeBy(20_000L); runCurrent()
        assertEquals(2, attempts)
    }
}
