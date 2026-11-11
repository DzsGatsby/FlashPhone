package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.CallTaskBrief
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal object CallDeviceProtocol {
    const val ORIGIN = "https://flash-phone.dzsgatsby.workers.dev"
    const val MAX_COMMAND_BYTES = 16 * 1024
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    val capabilities = listOf("calls.dial", "calls.cancel", "calls.hangup", "recording")
    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { Json.parseToJsonElement(canonical(it.value)) }).toString()
        is JsonArray -> JsonArray(value.map { Json.parseToJsonElement(canonical(it)) }).toString()
        else -> value.toString()
    }
    fun digest(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray()).joinToString("") { "%02x".format(it) }

    fun command(text: String, deviceId: String, now: Long): DeviceCommand {
        require(text.toByteArray().size <= MAX_COMMAND_BYTES) { "远程命令过大" }
        val envelope = json.parseToJsonElement(text).jsonObject
        require(envelope.keys == setOf("v", "type", "command") && envelope["v"]?.jsonPrimitive?.int == 1 &&
            envelope["type"]?.jsonPrimitive?.content == "command") { "远程命令格式无效" }
        val raw = envelope.getValue("command").jsonObject
        val command = json.decodeFromJsonElement<DeviceCommand>(raw)
        require(validId(command.command_id) && validId(command.task_id) && command.device_id == deviceId) { "远程任务身份不匹配" }
        require(command.action in setOf("dial", "cancel", "hangup")) { "不支持的远程电话动作" }
        require(command.issued_at > 0 && command.issued_at <= now + 30_000 && command.expires_at > now &&
            command.expires_at > command.issued_at && command.expires_at - command.issued_at <= 120_000) { "远程命令已过期或时间无效" }
        require(command.expected_call_id == null || validId(command.expected_call_id)) { "通话身份无效" }
        if (command.action == "dial") {
            require(command.expected_call_id == null) { "拨号不能指定已有通话" }
            val payload = json.decodeFromJsonElement<DialPayload>(command.payload)
            require(CallPolicy.outgoingNumber(payload.number) != null) { "远程拨号仅支持普通号码" }
            require(payload.sim_id == null || payload.sim_id.length in 1..256) { "SIM 无效" }
            validatedDeviceTask(payload.task)
        } else {
            require(command.payload.isEmpty()) { "控制命令不接受额外内容" }
            if (command.action == "hangup") require(command.expected_call_id != null) { "挂断须指定本通电话" }
        }
        return command
    }
}

@Serializable
internal data class DeviceCommand(
    val command_id: String,
    val task_id: String,
    val device_id: String,
    val action: String,
    val issued_at: Long,
    val expires_at: Long,
    val expected_call_id: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
) { override fun toString() = "DeviceCommand(action=$action)" }

@Serializable
internal data class DialPayload(val number: String, val sim_id: String? = null, val task: CallTaskBrief) {
    override fun toString() = "DialPayload()"
}

@Serializable
internal data class DeviceEvent(
    val v: Int = 1,
    val type: String = "event",
    val task_id: String,
    val sequence: Long,
    val phase: String,
    val carrier_ended: Boolean = false,
    val ai_active: Boolean = false,
    val work_id: String? = null,
    val call_id: String? = null,
    val result: JsonObject? = null,
)

/** The remote control plane has a wider bounded task contract than the local phone form. */
internal fun validatedDeviceTask(task: CallTaskBrief): CallTaskBrief {
    require(task.goal.length <= 2_000 && task.goal.isNotBlank()) { "远程任务目标需为 1–2000 字" }
    require(task.questions.size <= 20 && task.questions.all { it.length <= 500 && it.isNotBlank() }) { "远程任务最多 20 问，每项 1–500 字" }
    require(task.context.length <= 4_000 && task.constraints.length <= 2_000) { "远程任务背景或边界过长" }
    return CallTaskBrief(task.goal.trim(), java.util.Collections.unmodifiableList(task.questions.map(String::trim)),
        task.context.trim(), task.constraints.trim())
}
