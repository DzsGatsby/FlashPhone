package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
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
}
