package com.wangzan101.mika.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CallSummaryCoordinatorTest {
    private class Storage(var rows: List<CallRecord>, var fail: Boolean = false) : CallHistory.Storage {
        override fun read() = rows
        override fun write(records: List<CallRecord>): Boolean { if (fail) return false; rows = records; return true }
    }
    private suspend fun awaitDone(c: CallSummaryCoordinator, id: String) {
        withTimeout(3_000L) { c.states.first { it[id] != CallSummaryStatus.RUNNING } }
    }
    private fun record(id: String = "1") = CallRecord(id, "sensitive-number", CallDirection.INCOMING, 1, 2, "ended",
        listOf(CallTranscriptLine("user", "明天下午再联系。")))
    private fun summary(r: CallRecord) = CallSummary(3, "provider", "model", CallSummaryEngine.sourceFingerprint(r),
        listOf(CallSummaryItem("对方希望明天下午联系", listOf(CallSummaryEvidence(1, "明天下午再联系。")))), emptyList(), emptyList(), emptyList())

    @Test fun duplicateClickCostsOneRequestAndSurvivesParallelRecordingUpdate() = runBlocking {
        val r = record(); val h = CallHistory(Storage(listOf(r))); h.load()
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val c = CallSummaryCoordinator(this, h, { true }, { true }, { calls++; gate.await(); summary(it) })
        c.request(r.id); c.request(r.id); yield()
        assertEquals(1, calls)
        h.setHandled(r.id, true)
        val audio = CallRecordingInfo("recording", 100, false, 50)
        h.setRecording(r.id, audio)
        gate.complete(Unit); awaitDone(c, r.id)
        assertNotNull(h.state.value.single().summary); assertTrue(h.state.value.single().handled)
        assertEquals(audio, h.state.value.single().recording)
        assertTrue(c.states.value.isEmpty())
    }
    @Test fun emptyAndUnconfiguredNeverInvokeModel() = runBlocking {
        val r = record(); val h = CallHistory(Storage(listOf(r, r.copy(id = "empty", transcript = emptyList())))); h.load()
        var calls = 0
        val c = CallSummaryCoordinator(this, h, { true }, { false }, { calls++; summary(it) })
        c.request("empty"); c.request(r.id); awaitDone(c, r.id)
        assertEquals(0, calls); assertEquals(CallSummaryStatus.EMPTY, c.states.value["empty"])
        assertEquals(CallSummaryStatus.UNCONFIGURED, c.states.value[r.id])
    }
    @Test fun newCarrierCallCancelsEvenLateNonCooperativeModelAndNeverSaves() = runBlocking {
        val r = record(); val h = CallHistory(Storage(listOf(r))); h.load(); var idle = true
        val gate = CompletableDeferred<Unit>()
        val c = CallSummaryCoordinator(this, h, { idle }, { true }, { withContext(NonCancellable) { gate.await() }; summary(it) })
        c.request(r.id); yield(); idle = false; c.onCallStateChanged(); gate.complete(Unit); coroutineContext[Job]!!.children.toList().joinAll(); awaitDone(c, r.id)
        assertNull(h.state.value.single().summary); assertEquals(CallSummaryStatus.CANCELED, c.states.value[r.id])
    }
    @Test fun deleteWhileRequestInFlightDoesNotResurrectRecordOrStatus() = runBlocking {
        val r = record(); val h = CallHistory(Storage(listOf(r))); h.load()
        val gate = CompletableDeferred<Unit>()
        val c = CallSummaryCoordinator(this, h, { true }, { true }, { withContext(NonCancellable) { gate.await() }; summary(it) })
        c.request(r.id); yield(); c.forget(r.id); h.delete(r.id); gate.complete(Unit); coroutineContext[Job]!!.children.toList().joinAll(); awaitDone(c, r.id)
        assertTrue(h.state.value.isEmpty()); assertTrue(c.states.value.isEmpty())
    }
    @Test fun timeoutDoesNotRetryAndSaveFailureRetainsOriginalSummary() = runBlocking {
        val r = record(); val old = summary(r); val storage = Storage(listOf(r.copy(summary = old)))
        val h = CallHistory(storage); h.load(); var calls = 0
        val c = CallSummaryCoordinator(this, h, { true }, { true }, { calls++; delay(200); summary(it) }, timeoutMs = 100)
        c.request(r.id); awaitDone(c, r.id); assertEquals(1, calls)
        assertEquals(CallSummaryStatus.TIMEOUT, c.states.value[r.id]); assertEquals(old, h.state.value.single().summary)
        storage.fail = true
        val c2 = CallSummaryCoordinator(this, h, { true }, { true }, { summary(it).copy(generatedAtMs = 4) })
        c2.request(r.id); awaitDone(c2, r.id); assertEquals(CallSummaryStatus.SAVE_FAILED, c2.states.value[r.id])
        assertEquals(old, h.state.value.single().summary)
    }
    @Test fun newRecordWaitsForRealIdleAndOldRecordsAreNeverScanned() = runBlocking {
        val old = record("old"); val fresh = record("new"); val h = CallHistory(Storage(listOf(fresh, old))); h.load()
        var idle = false; val ids = mutableListOf<String>()
        val c = CallSummaryCoordinator(this, h, { idle }, { true }, { ids.add(it.id); summary(it) })
        c.scheduleNew(fresh.id); yield(); assertTrue(ids.isEmpty())
        idle = true; c.onCallStateChanged(); awaitDone(c, fresh.id)
        assertEquals(listOf("new"), ids); assertNull(h.state.value.first { it.id == "old" }.summary)
    }
    @Test fun turningOffAutomaticDropsWaitingWorkButAllowsExplicitGeneration() = runBlocking {
        val r = record(); val h = CallHistory(Storage(listOf(r))); h.load()
        var idle = false; var automatic = true; var calls = 0
        val c = CallSummaryCoordinator(this, h, { idle }, { true }, { calls++; summary(it) },
            automaticEnabled = { automatic })
        c.scheduleNew(r.id); automatic = false; c.onCallStateChanged()
        idle = true; c.onCallStateChanged(); yield(); assertEquals(0, calls)
        c.request(r.id); awaitDone(c, r.id); assertEquals(1, calls)
    }
    @Test fun deletedQueuedRecordCannotStallTheNextValidRecord() = runBlocking {
        val a = record("a"); val b = record("b"); val h = CallHistory(Storage(listOf(a,b))); h.load()
        var idle = false; val calls = mutableListOf<String>()
        val c = CallSummaryCoordinator(this, h, { idle }, { true }, { calls.add(it.id); summary(it) })
        c.scheduleNew(a.id); c.scheduleNew(b.id); h.delete(a.id)
        idle = true; c.onCallStateChanged(); awaitDone(c, b.id); assertEquals(listOf("b"), calls)
    }

}
