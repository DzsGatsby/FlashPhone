package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.calls.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDeviceResultsTest {
    private val workId = "00000000-0000-0000-0000-000000000010"
    private fun pendingRecord() = CallRecord(workId, null, CallDirection.OUTGOING, 1, 2, "通话已结束",
        listOf(CallTranscriptLine("user", "synthetic caller sentence")), recordingPending = true)

    @Test fun finalTranscriptIsDeliveredImmediatelyWhileSlowRecordingFinishesLater() = runTest {
        val history = MutableStateFlow(listOf(pendingRecord()))
        val ready = mutableListOf<Set<String>>()
        val watch = launch { availableDeviceRecordings(history).collect { ready += it } }
        runCurrent()
        val result = awaitDeviceCallRecord(workId, history)
        assertEquals(0L, currentTime)
        assertEquals("synthetic caller sentence", result!!.transcript.single().text)
        assertTrue(result.recordingPending)
        // Archive takes longer than the old 20-second final-result wait. No call-state event occurs.
        advanceTimeBy(30_000L)
        history.value = history.value.map { it.copy(recordingPending = false,
            recording = CallRecordingInfo(workId, 100, false, 1_024)) }
        runCurrent()
        assertEquals(listOf(emptySet<String>(), setOf(workId)), ready)
        watch.cancelAndJoin()
    }
    @Test fun missingHistoryHasABoundedWaitButAFailedPredialNeedsNoHistory() = runTest {
        val history = MutableStateFlow<List<CallRecord>>(emptyList())
        assertNull(awaitDeviceCallRecord(null, history))
        assertEquals(0L, currentTime)
        assertNull(awaitDeviceCallRecord(workId, history))
        assertEquals(20_000L, currentTime)
    }
    @Test fun finalResultWriteFailureIsContainedInsteadOfEscapingTheApplicationCoroutine() = runTest {
        var failures = 0; var completions = 0
        val ended = MutableStateFlow(RemoteCallSnapshot("ended", workId, "call", carrierEnded = true, workFinished = true))
        observeDeviceCall(ended, onProgress = { error("terminal is not a progress receipt") }, onCompleted = {
            completions++; throw IOException("synthetic disk full during final result commit")
        }, onFailure = { failures++ })
        assertEquals(1, completions)
        assertEquals(1, failures)
        currentCoroutineContext().ensureActive()
    }
    @Test fun handoffCannotDeliverFinalResultAndCancellationCannotBeSwallowedAsFailure() = runTest {
        var completions = 0; var failures = 0
        val state = MutableStateFlow(RemoteCallSnapshot("handed_off", workId, "call", workFinished = true))
        val observing = launch { observeDeviceCall(state, {}, { completions++ }, { failures++ }) }
        runCurrent()
        assertEquals(0, completions)
        observing.cancelAndJoin()
        assertTrue(observing.isCancelled)
        assertEquals(0, failures)
        val next = launch { observeDeviceCall(state, {}, { completions++ }, { failures++ }) }
        runCurrent()
        state.value = state.value.copy(phase = "ended", carrierEnded = true)
        next.join()
        assertEquals(1, completions)
    }
}
