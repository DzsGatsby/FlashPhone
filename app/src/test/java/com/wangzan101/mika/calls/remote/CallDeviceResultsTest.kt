package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.calls.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    @Test fun transientProgressWriteFailureStillObservesRealEndAndDeliversFinalResult() = runTest {
        var failures = 0; var completions = 0
        val state = MutableStateFlow(RemoteCallSnapshot("active", workId, "call", aiActive = true))
        val observing = launch {
            observeDeviceCall(state, onProgress = { throw IOException("synthetic temporary disk failure") },
                onCompleted = { completions++ }, onFailure = { failures++ })
        }
        runCurrent()
        assertEquals(1, failures)
        assertTrue("A failed progress receipt must not abandon the live call", observing.isActive)
        state.value = state.value.copy(phase = "ended", carrierEnded = true, aiActive = false, workFinished = true)
        observing.join()
        assertEquals(1, completions)
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

    @Test fun transientFinalWriteFailureRetriesTheSameFactsBeforeReleasingOwnership() = runTest {
        val snapshot = RemoteCallSnapshot("ended", workId, "call", carrierEnded = true, workFinished = true)
        val result = buildJsonObject { put("transcript", "synthetic final facts") }
        val pending = PendingDeviceCallResult(snapshot, result)
        var writes = 0
        var releases = 0
        if (pending.deliver { observedSnapshot, observedResult ->
            assertSame(snapshot, observedSnapshot)
            assertSame(result, observedResult)
            if (++writes == 1) throw IOException("synthetic transient final write failure")
        }) releases++
        assertEquals(2, writes)
        assertEquals(1, releases)
        assertTrue(pending.delivered)
        assertEquals(250L, currentTime)
        assertTrue(pending.deliver { _, _ -> fail("Durable completion may not be written twice") })
    }

    @Test fun persistentFinalWriteFailureRetainsResultAndHasAFiniteReconnectBudget() = runTest {
        val snapshot = RemoteCallSnapshot("ended", workId, "call", carrierEnded = true, workFinished = true)
        val result = buildJsonObject { put("transcript", "retained synthetic facts") }
        val pending = PendingDeviceCallResult(snapshot, result)
        var writes = 0
        val failing: suspend (RemoteCallSnapshot, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ ->
            writes++; throw IOException("synthetic persistent disk failure")
        }
        assertFalse(pending.deliver(failing))
        assertEquals(3, writes)
        assertFalse(pending.delivered)
        assertSame(snapshot, pending.snapshot)
        assertSame(result, pending.result)
        assertFalse(pending.deliver(failing)) // A later successful socket handshake triggers one more burst.
        assertEquals(6, writes)
        assertFalse(pending.deliver(failing))
        advanceUntilIdle()
        assertEquals("No infinite storage retry loop or additional phone action", 6, writes)
        assertFalse(pending.delivered)
        assertEquals(1_500L, currentTime)
    }

    @Test fun finalRetryCancellationIsPromptAndRetainsUndeliveredFacts() = runTest {
        val pending = PendingDeviceCallResult(
            RemoteCallSnapshot("ended", workId, "call", carrierEnded = true, workFinished = true),
            buildJsonObject { put("transcript", "synthetic facts survive cancellation") })
        var writes = 0
        val delivery = launch { pending.deliver { _, _ -> writes++; throw IOException("synthetic failure") } }
        runCurrent()
        assertEquals(1, writes)
        delivery.cancelAndJoin()
        assertTrue(delivery.isCancelled)
        assertFalse(pending.delivered)
        assertEquals(0L, currentTime)
        assertTrue(pending.deliver { _, _ -> writes++ })
        assertEquals(2, writes)
    }

    @Test fun explicitEnableRestoresExhaustedPersistenceBudgetWithoutChangingSavedFacts() = runTest {
        val snapshot = RemoteCallSnapshot("ended", workId, "call", carrierEnded = true, workFinished = true)
        val result = buildJsonObject { put("transcript", "same synthetic facts after storage recovery") }
        val pending = PendingDeviceCallResult(snapshot, result)
        var writes = 0
        val failing: suspend (RemoteCallSnapshot, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ ->
            writes++; throw IOException("synthetic full disk")
        }
        assertFalse(pending.deliver(failing))
        assertFalse(pending.deliver(failing))
        assertEquals(6, writes)
        assertFalse(pending.deliver { _, _ -> fail("Reconnect cannot replenish the budget") })
        pending.resetForExplicitEnable()
        assertTrue(pending.deliver { observed, facts ->
            writes++
            assertSame(snapshot, observed)
            assertSame(result, facts)
        })
        assertEquals(7, writes)
        assertTrue(pending.delivered)
        pending.resetForExplicitEnable()
        assertTrue(pending.deliver { _, _ -> fail("Re-enabling cannot duplicate an already durable result") })
    }
}
