package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CallDeviceStoreTest {
    private class TestCipher : KeyCipher {
        var fail = false
        override fun encrypt(plain: ByteArray): ByteArray { check(!fail); return plain.map { (it.toInt() xor 87).toByte() }.toByteArray() }
        override fun decrypt(data: ByteArray) = data.map { (it.toInt() xor 87).toByte() }.toByteArray()
    }
    private val device = DeviceIdentity("00000000-0000-0000-0000-000000000001", "synthetic-test-credential", "测试账号", "测试设备")
    private fun command(id: String = "00000000-0000-0000-0000-000000000002", task: String = "00000000-0000-0000-0000-000000000003") =
        DeviceCommand(id, task, device.deviceId, "dial", 1_000_000L, 1_060_000L, payload = buildJsonObject { put("number", "13800138000") })
    @Test fun receiptIsDurableBeforeActionAndDuplicateCannotRedialAfterReload() = runTest {
        val directory = Files.createTempDirectory("device-ledger-test").toFile()
        try {
            val file = File(directory, "ledger.enc"); val cipher = TestCipher()
            val first = CallDeviceStore(file, cipher)
            first.enroll(device); first.enabled(true)
            assertTrue(first.claim(command(), 1_000_001L))
            assertFalse(file.readText().contains(device.token))
            val reloaded = CallDeviceStore(file, cipher)
            assertFalse(reloaded.claim(command(), 1_000_001L))
            assertTrue(runCatching { reloaded.claim(command().copy(payload = buildJsonObject { put("number", "13900139000") }), 1_000_001L) }.isFailure)
            reloaded.recover()
            assertEquals("interrupted", reloaded.read().tasks.single().phase)
            assertFalse(reloaded.read().pending.single().carrier_ended)
            assertFalse(reloaded.claim(command(), 1_000_001L))
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedDurableClaimDoesNotPermitThePhoneActionOrReserveAnInMemoryReceipt() = runTest {
        val directory = Files.createTempDirectory("device-claim-test").toFile()
        try {
            val cipher = TestCipher(); val store = CallDeviceStore(File(directory, "ledger.enc"), cipher)
            store.enroll(device); store.enabled(true)
            var phoneActions = 0
            cipher.fail = true
            runCatching { if (store.claim(command(), 1_000_001L)) phoneActions++ }
            assertEquals(0, phoneActions)
            assertTrue(store.read().receipts.isEmpty())
            cipher.fail = false
            if (store.claim(command(), 1_000_001L)) phoneActions++
            assertEquals(1, phoneActions)
        } finally { directory.deleteRecursively() }
    }
    @Test fun resultsRemainSpooledUntilAcknowledgedAndHandoffIsNotTerminal() = runTest {
        val directory = Files.createTempDirectory("device-event-test").toFile()
        try {
            val store = CallDeviceStore(File(directory, "ledger.enc"), TestCipher())
            store.enroll(device); store.claim(command(), 1_000_001L)
            store.event(command().task_id, "dialing", workId = "work", callId = "call")
            val handedOff = store.event(command().task_id, "handed_off")
            assertFalse(store.read().tasks.single().terminal)
            assertEquals(2, store.read().pending.size)
            store.ack(command().task_id, 999)
            assertEquals(2, store.read().pending.size)
            store.ack(command().task_id, handedOff.sequence)
            assertTrue(store.read().pending.isEmpty())
            store.event(command().task_id, "ended", carrierEnded = true)
            val recordingReady = store.event(command().task_id, "recording_ready", result = buildJsonObject { put("recording", "private") })
            // Worker v1 permits carrier_ended=true only on an ended event, never on metadata updates.
            val wire = CallDeviceProtocol.json.encodeToJsonElement(recordingReady).jsonObject
            assertEquals("recording_ready", wire["phase"]!!.jsonPrimitive.content)
            assertFalse(wire["carrier_ended"]!!.jsonPrimitive.boolean)
            assertEquals("ended", store.read().tasks.single().phase)
            assertTrue(store.read().tasks.single().carrierEnded)
            assertTrue(store.read().tasks.single().recordingUploaded)
        } finally { directory.deleteRecursively() }
    }
    @Test fun uploadBudgetAndDigestBindingSurviveReconnectAndProcessRestart() = runTest {
        val directory = Files.createTempDirectory("device-upload-budget").toFile()
        try {
            val file = File(directory, "ledger.enc"); val cipher = TestCipher()
            var store = CallDeviceStore(file, cipher)
            store.enroll(device); store.claim(command(), 1_000_001L)
            assertFalse(store.reserveUpload(command().task_id, "hash"))
            store.event(command().task_id, "ended", carrierEnded = true)
            assertTrue(store.reserveUpload(command().task_id, "hash"))
            store = CallDeviceStore(file, cipher)
            assertFalse(store.reserveUpload(command().task_id, "different hash"))
            assertTrue(store.reserveUpload(command().task_id, "hash"))
            assertTrue(store.reserveUpload(command().task_id, "hash"))
            assertFalse(store.reserveUpload(command().task_id, "hash"))
            assertEquals(3, store.read().tasks.single().uploadAttempts)
        } finally { directory.deleteRecursively() }
    }

    @Test fun disablingSurvivesKeystoreWriteFailureAndCannotReenableOnProcessRestart() = runTest {
        val directory = Files.createTempDirectory("device-disable-test").toFile()
        try {
            val file = File(directory, "ledger.enc"); val cipher = TestCipher()
            val store = CallDeviceStore(file, cipher)
            store.enroll(device); store.enabled(true)
            cipher.fail = true
            assertTrue(runCatching { store.enabled(false) }.isFailure)
            assertFalse(store.read().enabled)
            assertFalse(CallDeviceStore(file, cipher).read().enabled)
            // Only a subsequent explicit, successfully committed enable removes the marker.
            assertTrue(runCatching { store.enabled(true) }.isFailure)
            assertFalse(CallDeviceStore(file, cipher).read().enabled)
            cipher.fail = false
            store.enabled(true)
            assertTrue(CallDeviceStore(file, cipher).read().enabled)
        } finally { directory.deleteRecursively() }
    }

    @Test fun unreadableLedgerCannotBeSilentlyReplacedByAFreshDial() = runTest {
        val directory = Files.createTempDirectory("device-corrupt-test").toFile()
        try {
            val file = File(directory, "ledger.enc"); file.writeText("not a valid encrypted ledger")
            val before = file.readBytes()
            val store = CallDeviceStore(file, TestCipher())
            assertTrue(runCatching { store.claim(command(), 1_000_001L) }.isFailure)
            assertArrayEquals(before, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun taskRetentionKeepsUnacknowledgedResultsAckableWhenANewDialIsClaimed() = runTest {
        val directory = Files.createTempDirectory("device-retention-test").toFile()
        try {
            val file = File(directory, "ledger.enc"); val cipher = TestCipher()
            val tasks = (1..200).map { DeviceTask(java.util.UUID(0, it.toLong()).toString(),
                sequence = 1, phase = "ended", terminal = true) }
            val result = DeviceEvent(task_id = tasks.first().id, sequence = 1, phase = "ended", carrier_ended = true)
            file.writeBytes(cipher.encrypt(CallDeviceProtocol.json.encodeToString(
                DeviceLedger(identity = device, tasks = tasks, pending = listOf(result))).toByteArray()))
            val store = CallDeviceStore(file, cipher)
            val next = command(task = java.util.UUID(0, 201L).toString())
            assertTrue(store.claim(next, 1_000_001L))
            assertEquals(200, store.read().tasks.size)
            assertTrue(store.read().tasks.any { it.id == tasks.first().id })
            assertFalse(store.read().tasks.any { it.id == tasks[1].id })
            // Also prove the ACK can clear this event after process restart.
            val reloaded = CallDeviceStore(file, cipher)
            reloaded.ack(result.task_id, result.sequence)
            assertTrue(reloaded.read().pending.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun fullUnacknowledgedTaskLedgerRejectsAnotherDialBeforeReservingIt() = runTest {
        val directory = Files.createTempDirectory("device-full-results-test").toFile()
        try {
            val file = File(directory, "ledger.enc"); val cipher = TestCipher()
            val tasks = (1..200).map { DeviceTask(java.util.UUID(0, it.toLong()).toString(),
                sequence = 1, phase = "ended", terminal = true) }
            val pending = tasks.map { DeviceEvent(task_id = it.id, sequence = 1, phase = "ended", carrier_ended = true) }
            file.writeBytes(cipher.encrypt(CallDeviceProtocol.json.encodeToString(
                DeviceLedger(identity = device, tasks = tasks, pending = pending)).toByteArray()))
            val original = file.readBytes()
            val store = CallDeviceStore(file, cipher)
            val next = command(task = java.util.UUID(0, 201L).toString())
            assertTrue(runCatching { store.claim(next, 1_000_001L) }.isFailure)
            assertArrayEquals(original, file.readBytes())
            assertTrue(store.read().receipts.isEmpty())
            assertEquals(200, store.read().pending.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun finalCompletionCommitRemainsIdempotentAfterCancellationAndServerAck() = runTest {
        val directory = Files.createTempDirectory("device-final-result-test").toFile()
        try {
            val file = File(directory, "ledger.enc"); val cipher = TestCipher()
            val store = CallDeviceStore(file, cipher)
            val command = command()
            store.enroll(device); store.claim(command, 1_000_001L)
            val snapshot = RemoteCallSnapshot("ended", "work", "call", carrierEnded = true, workFinished = true)
            val result = buildJsonObject { put("transcript", "synthetic saved result") }
            val pending = PendingDeviceCallResult(snapshot, result)
            assertTrue(runCatching {
                pending.deliver { observed, facts ->
                    store.complete(command.task_id, observed, facts)
                    throw CancellationException("synthetic cancellation after durable commit")
                }
            }.exceptionOrNull() is CancellationException)
            assertFalse(pending.delivered)
            assertEquals(result, store.read().pending.single().result)
            store.ack(command.task_id, 1)
            val reloaded = CallDeviceStore(file, cipher)
            assertTrue(pending.deliver { observed, facts -> reloaded.complete(command.task_id, observed, facts) })
            assertTrue(reloaded.read().pending.isEmpty())
            assertEquals(1L, reloaded.read().tasks.single().sequence)
            assertTrue(reloaded.read().tasks.single().terminal)
        } finally { directory.deleteRecursively() }
    }
}
