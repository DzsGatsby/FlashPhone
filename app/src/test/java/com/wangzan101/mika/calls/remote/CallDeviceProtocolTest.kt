package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.calls.CallTaskBrief
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CallDeviceProtocolTest {
    private val device = "00000000-0000-0000-0000-000000000001"
    private fun envelope(action: String = "dial", payload: JsonObject? = null, call: String? = null,
        expires: Long = 1_060_000L): String = buildJsonObject {
        put("v", 1); put("type", "command")
        put("command", CallDeviceProtocol.json.encodeToJsonElement(DeviceCommand(
            "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003",
            device, action, 1_000_000L, expires, call,
            payload ?: if (action == "dial") buildJsonObject {
                put("number", "13800138000"); put("task", CallDeviceProtocol.json.encodeToJsonElement(CallTaskBrief("询问营业时间")))
            } else JsonObject(emptyMap()),
        )))
    }.toString()
    @Test fun validOwnerTaskIsParsedWithoutInventingAnObjective() {
        val parsed = CallDeviceProtocol.command(envelope(), device, 1_000_001L)
        val payload = CallDeviceProtocol.json.decodeFromJsonElement<DialPayload>(parsed.payload)
        assertEquals("询问营业时间", payload.task.goal)
        assertFalse(parsed.toString().contains(payload.number))
    }
    @Test fun missingGoalUnknownActionWrongDeviceExpiredOrExcessiveLeaseFailsClosed() {
        for (text in listOf(envelope(action = "shell"), envelope(expires = 1_000_000L), envelope(expires = 1_120_001L),
            envelope(payload = buildJsonObject { put("number", "13800138000"); putJsonObject("task") { put("goal", " ") } }),
            envelope(payload = buildJsonObject { put("number", "*123#"); put("task", CallDeviceProtocol.json.encodeToJsonElement(CallTaskBrief("询问"))) }))) {
            assertTrue(runCatching { CallDeviceProtocol.command(text, device, 1_000_001L) }.isFailure)
        }
        assertTrue(runCatching { CallDeviceProtocol.command(envelope(), "00000000-0000-0000-0000-000000000004", 1_000_001L) }.isFailure)
    }
    @Test fun cancelCanPrecedeCallBindingButHangupRequiresExactCallId() {
        assertNull(CallDeviceProtocol.command(envelope("cancel"), device, 1_000_001L).expected_call_id)
        assertTrue(runCatching { CallDeviceProtocol.command(envelope("hangup"), device, 1_000_001L) }.isFailure)
        assertEquals(device, CallDeviceProtocol.command(envelope("hangup", call = device), device, 1_000_001L).expected_call_id)
    }
    @Test fun unexpectedPayloadPropertiesAndOversizedMessagesAreRejected() {
        assertTrue(runCatching { CallDeviceProtocol.command(envelope(payload = buildJsonObject {
            put("number", "13800138000"); put("task", CallDeviceProtocol.json.encodeToJsonElement(CallTaskBrief("询问"))); put("shell", "forbidden")
        }), device, 1_000_001L) }.isFailure)
        assertTrue(runCatching { CallDeviceProtocol.command(" ".repeat(16 * 1024 + 1), device, 1_000_001L) }.isFailure)
    }
    @Test fun remoteTaskAcceptsTheWiderServerContractWithoutTruncationOrChangingLocalFormLimits() {
        val questions = MutableList(9) { "Q".repeat(201) }
        val supplied = CallTaskBrief("G".repeat(401), questions, "C".repeat(1001), "L".repeat(601))
        val parsed = CallDeviceProtocol.command(envelope(payload = buildJsonObject {
            put("number", "13800138000"); put("task", CallDeviceProtocol.json.encodeToJsonElement(supplied))
        }), device, 1_000_001L)
        val snapshot = validatedDeviceTask(CallDeviceProtocol.json.decodeFromJsonElement<DialPayload>(parsed.payload).task)
        assertEquals(supplied, snapshot)
        questions.clear()
        assertEquals(9, snapshot.questions.size)
        assertTrue(runCatching { supplied.validatedSnapshot() }.isFailure)
        assertTrue(runCatching { validatedDeviceTask(CallTaskBrief("G".repeat(2_001))) }.isFailure)
        assertTrue(runCatching { validatedDeviceTask(CallTaskBrief("询问", List(21) { "Q" })) }.isFailure)
    }

    @Test fun canonicalDigestIsIndependentOfJsonObjectFieldOrder() {
        assertEquals(CallDeviceProtocol.digest(Json.parseToJsonElement("{\"a\":1,\"b\":{\"x\":2,\"y\":3}}")),
            CallDeviceProtocol.digest(Json.parseToJsonElement("{\"b\":{\"y\":3,\"x\":2},\"a\":1}")))
    }
}
