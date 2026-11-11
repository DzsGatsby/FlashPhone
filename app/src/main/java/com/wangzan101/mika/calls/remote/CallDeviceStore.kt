package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class DeviceIdentity(val deviceId: String, val token: String, val account: String, val name: String) {
    override fun toString() = "DeviceIdentity()"
}
@Serializable
internal data class CommandReceipt(val commandId: String, val taskId: String, val digest: String, val expiresAt: Long)
@Serializable
internal data class DeviceTask(val id: String, val sequence: Long = 0, val phase: String = "accepted",
    val workId: String? = null, val callId: String? = null, val carrierEnded: Boolean = false,
    val terminal: Boolean = false, val recordingUploaded: Boolean = false,
    val uploadAttempts: Int = 0, val uploadHash: String? = null)
@Serializable
internal data class DeviceLedger(val identity: DeviceIdentity? = null, val enabled: Boolean = false,
    val receipts: List<CommandReceipt> = emptyList(), val tasks: List<DeviceTask> = emptyList(),
    val pending: List<DeviceEvent> = emptyList())

/** Cipher and all local atomic commits share one lock, including first Keystore alias initialization. */
internal class CallDeviceStore(private val file: File, private val cipher: KeyCipher) {
    private val lock = Mutex()
    private val disabledMarker = File(file.parentFile, file.name + ".disabled")
    private var loaded: DeviceLedger? = null
    suspend fun read(): DeviceLedger = io { it }
    private suspend fun <T> io(block: (DeviceLedger) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val persisted = loaded ?: if (file.exists()) {
                require(file.length() in 1..MAX_BYTES.toLong())
                val plain = cipher.decrypt(file.readBytes())
                try { CallDeviceProtocol.json.decodeFromString<DeviceLedger>(plain.decodeToString()) }
                finally { plain.fill(0) }
            } else DeviceLedger()
            val current = if (disabledMarker.exists()) persisted.copy(enabled = false) else persisted
            loaded = current
            block(current)
        }
    }
    private fun commit(next: DeviceLedger) {
        val plain = CallDeviceProtocol.json.encodeToString(next).toByteArray()
        try {
            require(plain.size <= MAX_BYTES) { "远程电话任务本地队列已满" }
            val encrypted = cipher.encrypt(plain)
            check(file.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
            val temporary = File(file.parentFile, file.name + ".tmp")
            try {
                java.io.FileOutputStream(temporary).use { it.write(encrypted); it.fd.sync() }
                check(temporary.renameTo(file))
                loaded = next
            } finally { temporary.delete() }
        } finally { plain.fill(0) }
    }
    suspend fun enroll(identity: DeviceIdentity) = io {
        check(it.identity == null || it.tasks.none { task -> !task.terminal }) { "请先结束现有远程任务" }
        commit(DeviceLedger(identity = identity))
    }
    suspend fun enabled(value: Boolean) = io {
        check(it.identity != null || !value)
        if (!value) {
            // Non-sensitive revocation marker survives a failed Keystore encryption/ledger commit.
            check(file.parentFile?.let { parent -> parent.mkdirs() || parent.isDirectory } == true)
            java.io.FileOutputStream(disabledMarker).use { marker -> marker.write(1); marker.fd.sync() }
            loaded = it.copy(enabled = false)
        }
        commit(it.copy(enabled = value))
        if (value && disabledMarker.exists()) check(disabledMarker.delete())
    }
    suspend fun forget() = io { commit(DeviceLedger()) }

    /** Returns false for a byte-equivalent duplicate. This commit MUST precede every phone action. */
    suspend fun claim(command: DeviceCommand, now: Long): Boolean = io { ledger ->
        val digest = CallDeviceProtocol.digest(CallDeviceProtocol.json.encodeToJsonElement(command))
        ledger.receipts.find { it.commandId == command.command_id }?.let {
            check(it.digest == digest && it.taskId == command.task_id) { "命令标识重复但内容不同" }
            return@io false
        }
        if (command.action == "dial") {
            check(ledger.tasks.none { it.id == command.task_id }) { "任务已执行，不能重新拨打" }
            check(ledger.tasks.none { !it.terminal }) { "已有远程电话任务" }
        } else check(ledger.tasks.any { it.id == command.task_id }) { "远程任务不存在" }
        val receipts = ledger.receipts.filter { it.expiresAt > now }
        check(receipts.size < 256) { "远程命令过多" }
        val tasks = if (command.action == "dial") {
            (ledger.tasks + DeviceTask(command.task_id)).takeLast(200)
        } else ledger.tasks
        commit(ledger.copy(receipts = receipts + CommandReceipt(command.command_id, command.task_id, digest, command.expires_at), tasks = tasks))
        true
    }
    suspend fun event(taskId: String, phase: String, carrierEnded: Boolean = false, aiActive: Boolean = false,
        workId: String? = null, callId: String? = null, result: JsonObject? = null): DeviceEvent = io { ledger ->
        val task = ledger.tasks.single { it.id == taskId }
        val terminal = phase in setOf("ended", "failed", "canceled", "interrupted")
        val event = DeviceEvent(task_id = taskId, sequence = task.sequence + 1, phase = phase,
            carrier_ended = phase == "ended" && (carrierEnded || task.carrierEnded), ai_active = aiActive, work_id = workId ?: task.workId,
            call_id = callId ?: task.callId, result = result)
        val updated = task.copy(sequence = event.sequence, phase = if (phase == "recording_ready") task.phase else phase,
            workId = event.work_id, callId = event.call_id, carrierEnded = carrierEnded || task.carrierEnded,
            terminal = terminal || task.terminal, recordingUploaded = phase == "recording_ready" || task.recordingUploaded)
        // Keep terminal/result events until server ACK. Coalesce redundant unacknowledged progress only.
        var pending = ledger.pending
        if (!terminal && phase != "recording_ready") pending = pending.filterNot { it.task_id == taskId && it.phase == phase }
        check(pending.size < 256) { "远程结果待发送队列已满" }
        commit(ledger.copy(tasks = ledger.tasks.map { if (it.id == taskId) updated else it }, pending = pending + event))
        event
    }
    suspend fun ack(taskId: String, sequence: Long) = io { ledger ->
        val task = ledger.tasks.find { it.id == taskId } ?: return@io
        if (sequence <= 0 || sequence > task.sequence) return@io
        if (ledger.pending.any { it.task_id == taskId && it.sequence <= sequence }) {
            commit(ledger.copy(pending = ledger.pending.filterNot { it.task_id == taskId && it.sequence <= sequence }))
        }
    }
    /** A persisted transfer budget survives reconnect/restart and never grants another dial. */
    suspend fun reserveUpload(taskId: String, hash: String): Boolean = io { ledger ->
        val task = ledger.tasks.find { it.id == taskId } ?: return@io false
        if (!task.terminal || task.recordingUploaded || task.uploadAttempts >= 3 ||
            (task.uploadHash != null && task.uploadHash != hash)) return@io false
        commit(ledger.copy(tasks = ledger.tasks.map { if (it.id == taskId)
            it.copy(uploadAttempts = it.uploadAttempts + 1, uploadHash = hash) else it }))
        true
    }
    suspend fun recover() {
        for (task in read().tasks.filterNot { it.terminal }) event(task.id, "interrupted", workId = task.workId,
            callId = task.callId, result = buildJsonObject { put("reason", "设备进程已重启；未重新拨号，运营商通话结束状态未知") })
    }
    private companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
