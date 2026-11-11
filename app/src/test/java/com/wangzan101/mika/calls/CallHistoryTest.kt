package com.wangzan101.mika.calls

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CallHistoryTest {
    private class MemoryStorage(var records: List<CallRecord> = emptyList()) : CallHistory.Storage {
        var failRead = false
        var failWrite = false

        override fun read(): List<CallRecord> {
            check(!failRead)
            return records
        }

        override fun write(records: List<CallRecord>): Boolean {
            if (failWrite) return false
            this.records = records.toList()
            return true
        }
    }


    @Test fun recordingMetadataSurvivesHistoryEditsAndSerialization() = runBlocking {
        val info = CallRecordingInfo("89f1f858-043a-4677-889d-647195c5a74d", 8_000L, false, 512_044L)
        val original = record(1).copy(recording = info)
        val decoded = Json.decodeFromString<CallRecord>(Json.encodeToString(original))
        assertEquals(info, decoded.recording)
        val storage = MemoryStorage(listOf(decoded))
        val history = CallHistory(storage)
        history.setHandled(original.id, true)
        assertEquals(info, storage.records.single().recording)
        history.setHandled(original.id, false)
        assertEquals(CallHistory.Result.SAVED, history.setRecording(original.id, info.copy(partial = true)))
        assertFalse(history.state.value.single().handled)
        assertTrue(history.state.value.single().recording!!.partial)
        history.delete(original.id)
        assertEquals(CallHistory.Result.NOT_FOUND, history.setRecording(original.id, info))
        assertTrue(history.state.value.isEmpty())
    }

    @Test fun pendingArchiveFinishesWithoutReportingAFalseMissingRecording() = runBlocking {
        val history = CallHistory(MemoryStorage())
        val pending = record(1).copy(recordingPending = true)
        history.append(pending)
        assertTrue(history.state.value.single().recordingPending)
        history.setRecording(pending.id, null)
        assertFalse(history.state.value.single().recordingPending)
        assertNull(history.state.value.single().recording)
        val reopened = CallHistory(MemoryStorage(listOf(pending)))
        reopened.load()
        assertFalse("An interrupted old save cannot spin forever after restart", reopened.state.value.single().recordingPending)
        val failedStorage = MemoryStorage().apply { failRead = true }
        val failedHistory = CallHistory(failedStorage)
        failedHistory.append(pending)
        assertEquals(CallHistory.Result.WRITE_FAILED, failedHistory.setRecording(pending.id, null))
        assertFalse(failedHistory.state.value.single().recordingPending)
        assertTrue(failedHistory.writeFailed.value)
    }

    private fun record(id: Int) = CallRecord(
        id = id.toString(), number = null, direction = CallDirection.INCOMING,
        startedAtMs = id.toLong(), endedAtMs = id + 1L, result = "通话已结束",
        transcript = listOf(CallTranscriptLine("caller", "留言。\n请于明日联系。"), CallTranscriptLine("assistant", "我会转告。")),
    )

    @Test fun recordingEvictionClearsOnlyMissingAudioMetadataWithoutReorderingOrChangingPending() = runBlocking {
        val missing = CallRecordingInfo("89f1f858-043a-4677-889d-647195c5a74d", 8000L, false, 512L)
        val kept = missing.copy(id = "248b74d0-adce-417e-a15d-7d682e711760")
        val task = CallTaskBrief("问清服务时间", listOf("周末开门吗？"), "门店", "不预约")
        val original = listOf(record(4).copy(recording = missing, handled = true, task = task),
            record(3).copy(recording = kept), record(2))
        val storage = MemoryStorage(original)
        val history = CallHistory(storage)
        history.load()
        val pending = record(5).copy(recordingPending = true)
        history.append(pending)
        assertEquals(CallHistory.Result.SAVED, history.reconcileRecordings(setOf(kept.id)))
        val expected = listOf(pending, original[0].copy(recording = null), original[1], original[2])
        assertEquals(expected, history.state.value)
        assertEquals(expected, storage.records)
        assertEquals(CallHistory.Result.SAVED, history.reconcileRecordings(setOf(kept.id)))
        assertEquals(expected, history.state.value)
    }

    @Test fun recordingReconciliationShowsEvictionOnWriteFailureThenRetriesDurably() = runBlocking {
        val info = CallRecordingInfo("89f1f858-043a-4677-889d-647195c5a74d", 8000L, false, 512L)
        val original = record(1).copy(recording = info, handled = true)
        val storage = MemoryStorage(listOf(original)).apply { failWrite = true }
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.reconcileRecordings(emptySet()))
        assertEquals(listOf(original.copy(recording = null)), history.state.value)
        assertEquals(listOf(original), storage.records)
        assertTrue(history.writeFailed.value)
        storage.failWrite = false
        assertEquals(CallHistory.Result.SAVED, history.reconcileRecordings(emptySet()))
        assertEquals(history.state.value, storage.records)
        assertFalse(history.writeFailed.value)
    }

    @Test fun recordingReconciliationDoesNotReplaceUnreadableHistoryButCorrectsVisibleMetadata() = runBlocking {
        val info = CallRecordingInfo("89f1f858-043a-4677-889d-647195c5a74d", 8000L, false, 512L)
        val diskOnly = record(1)
        val storage = MemoryStorage(listOf(diskOnly)).apply { failRead = true }
        val history = CallHistory(storage)
        val visible = record(2).copy(recording = info, recordingPending = true)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.append(visible))
        assertEquals(CallHistory.Result.WRITE_FAILED, history.reconcileRecordings(emptySet()))
        assertEquals(listOf(visible.copy(recording = null)), history.state.value)
        assertEquals(listOf(diskOnly), storage.records)
        assertTrue(history.writeFailed.value)
    }

    @Test fun handledChangesAndRemovalPersistWithoutRewritingTranscript() = runBlocking {
        val initial = record(1)
        val other = record(2)
        val storage = MemoryStorage(listOf(other, initial))
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.SAVED, history.setHandled(initial.id, true))
        assertEquals(listOf(other, initial.copy(handled = true)), storage.records)
        val reopened = CallHistory(storage)
        reopened.load()
        assertTrue(reopened.state.value.last().handled)
        assertEquals(CallHistory.Result.SAVED, reopened.setHandled(initial.id, false))
        assertEquals(initial, storage.records.last())
        assertEquals(CallHistory.Result.SAVED, reopened.delete(other.id))
        assertEquals(listOf(initial), reopened.state.value)
        assertEquals(CallHistory.Result.NOT_FOUND, reopened.delete(other.id))
        assertEquals(CallHistory.Result.SAVED, reopened.clear())
        assertTrue(storage.records.isEmpty())
        assertTrue(reopened.state.value.isEmpty())
    }

    @Test fun failedEditsKeepExistingRecordsAndNewCallsRemainVisible() = runBlocking {
        val initial = record(1)
        val newCall = record(2)
        val storage = MemoryStorage(listOf(initial)).apply { failWrite = true }
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.setHandled(initial.id, true))
        assertEquals(CallHistory.Result.WRITE_FAILED, history.delete(initial.id))
        assertEquals(CallHistory.Result.WRITE_FAILED, history.clear())
        assertEquals(listOf(initial), history.state.value)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.append(newCall))
        assertEquals(listOf(newCall, initial), history.state.value)
        assertEquals(listOf(initial), storage.records)
        assertTrue(history.writeFailed.value)
        storage.failWrite = false
        assertEquals(CallHistory.Result.SAVED, history.setHandled(newCall.id, true))
        assertEquals(listOf(newCall.copy(handled = true), initial), storage.records)
        assertFalse(history.writeFailed.value)
    }

    @Test fun unreadableHistoryIsNotSilentlyReplacedByAppend() = runBlocking {
        val old = record(1)
        val recent = record(2)
        val storage = MemoryStorage(listOf(old)).apply { failRead = true }
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.WRITE_FAILED, history.append(recent))
        assertEquals(listOf(old), storage.records)
        assertEquals(listOf(recent), history.state.value)
        assertTrue(history.writeFailed.value)
        assertEquals(CallHistory.Result.SAVED, history.clear())
        assertTrue(storage.records.isEmpty())
        assertFalse(history.writeFailed.value)
    }

    @Test fun keepsNewestFiftyCallsAndDecodesLegacyRecordsAsUnhandled() = runBlocking {
        val storage = MemoryStorage()
        val history = CallHistory(storage)
        for (id in 1..51) assertEquals(CallHistory.Result.SAVED, history.append(record(id)))
        assertEquals((51 downTo 2).map(Int::toString), storage.records.map(CallRecord::id))
        val legacy = Json.decodeFromString<CallRecord>(
            """{"id":"legacy","number":null,"direction":"INCOMING","startedAtMs":1,"endedAtMs":2,"result":"通话已结束","transcript":[]}""",
        )
        assertFalse(legacy.handled)
        assertNull(legacy.task)
        assertNull(legacy.audioReport)
        assertNull(legacy.recording)
    }

    @Test fun outgoingTaskRoundTripsWithoutClaimingCompletionOrReusingItOnTheNextCall() = runBlocking {
        val task = CallTaskBrief("问清服务时间", listOf("周末开门吗？"), "门店", "不预约").validatedSnapshot()
        val report = CallAudioReport(3, 3, CallAudioReport.Turn.TRANSCRIPT_RECEIVED, 2880, 1920, 1920,
            inputFramesAfterLastReply = 50, inputSignalFramesAfterLastReply = 20, recognitionFailures = 1)
        val attempted = record(1).copy(direction = CallDirection.OUTGOING, result = "通话已结束", task = task, audioReport = report)
        val decoded = Json.decodeFromString<CallRecord>(Json.encodeToString(attempted))
        assertEquals(task, decoded.task)
        assertEquals(report, decoded.audioReport)
        assertEquals("通话已结束", decoded.result)
        assertFalse(decoded.handled)
        assertFalse(decoded.toString().contains(task.goal))

        val storage = MemoryStorage(listOf(decoded))
        val next = record(2).copy(direction = CallDirection.OUTGOING)
        val history = CallHistory(storage)
        assertEquals(CallHistory.Result.SAVED, history.append(next))
        val reopened = CallHistory(storage)
        reopened.load()
        assertNull(reopened.state.value.first().task)
        assertNull(reopened.state.value.first().audioReport)
        assertEquals(task, reopened.state.value.last().task)
    }
}
