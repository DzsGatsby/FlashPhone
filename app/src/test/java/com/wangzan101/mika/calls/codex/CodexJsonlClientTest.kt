package com.wangzan101.mika.calls.codex

import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexJsonlClientTest {
    @Test
    fun `initialize precedes thread request and unsolicited reverse request is denied`() = runBlocking {
        val toServer = PipedInputStream(8_192)
        val clientOut = PipedOutputStream(toServer)
        val clientIn = PipedInputStream(8_192)
        val fromServer = PipedOutputStream(clientIn)
        val sent = LinkedBlockingQueue<JsonObject>()
        val server = thread(isDaemon = true) {
            toServer.bufferedReader().use { reader ->
                fromServer.bufferedWriter().use { writer ->
                    fun emit(frame: String) {
                        writer.write(frame)
                        writer.newLine()
                        writer.flush()
                    }
                    repeat(4) {
                        val frame = parse(reader.readLine() ?: return@thread)
                        sent.put(frame)
                        when (frame.str("method")) {
                            "initialize" -> emit("""{"id":${frame.num("id")},"result":{"platformOs":"linux","userAgent":"mika_calls/0.167.0 (Linux Unknown; aarch64)"}}""")
                            "thread/start" -> {
                                emit("""{"id":"approval-1","method":"item/commandExecution/requestApproval","params":{}}""")
                                emit("""{"id":${frame.num("id")},"result":{"thread":{"id":"thread_1"}}}""")
                            }
                        }
                    }
                }
            }
        }
        val client = CodexJsonlClient(clientIn, clientOut, ByteArrayInputStream(byteArrayOf()))
        try {
            withTimeout(4_000) {
                client.initialize()
                val thread = client.request("thread/start", CodexWire.thread("/private/work", "gpt-test", null))
                assertEquals("thread_1", thread.obj("thread")?.str("id"))
            }
            assertEquals("initialize", sent.poll(1, TimeUnit.SECONDS)?.str("method"))
            assertEquals("initialized", sent.poll(1, TimeUnit.SECONDS)?.str("method"))
            assertEquals("thread/start", sent.poll(1, TimeUnit.SECONDS)?.str("method"))
            val rejected = sent.poll(1, TimeUnit.SECONDS)
            assertEquals("approval-1", rejected?.str("id"))
            assertEquals(-32601L, rejected?.obj("error")?.num("code"))
        } finally {
            client.close()
            clientOut.close()
            clientIn.close()
            server.join(1_000)
        }
    }

    @Test
    fun `turn routing cannot mix two threads or two turns and denies tool items`() {
        val delta = parse("""{"method":"item/agentMessage/delta","params":{"threadId":"threadA","turnId":"turnA","itemId":"part","delta":"答复"}}""")
        assertEquals(CodexTurnEvent.Delta("part", "答复"), codexTurnEvent(delta, "threadA", "turnA"))
        assertEquals(CodexTurnEvent.Ignore, codexTurnEvent(delta, "threadB", "turnA"))
        assertEquals(CodexTurnEvent.Ignore, codexTurnEvent(delta, "threadA", "turnB"))

        val unsafe = parse("""{"method":"item/started","params":{"threadId":"threadA","turnId":"turnA","item":{"id":"command","type":"commandExecution"}}}""")
        assertEquals(CodexTurnEvent.UnsupportedTool, codexTurnEvent(unsafe, "threadA", "turnA"))
        val cancelled = parse("""{"method":"turn/completed","params":{"threadId":"threadA","turn":{"id":"turnA","status":"interrupted"}}}""")
        assertEquals(CodexTurnEvent.End("interrupted", null), codexTurnEvent(cancelled, "threadA", "turnA"))
    }

    @Test
    fun `restore shows bounded owner messages and does not display engine artifacts`() {
        val history = codexHistory(parse("""
            {"data":[
                {"id":"new","status":"interrupted","items":[
                    {"id":"n1","type":"userMessage","content":[{"type":"text","text":"新问题"}]},
                    {"id":"n2","type":"agentMessage","text":"中途停止"},
                    {"id":"n3","type":"reasoning","text":"隐藏"}]},
                {"id":"old","status":"completed","items":[
                    {"id":"o1","type":"userMessage","content":[{"type":"text","text":"旧问题"}]},
                    {"id":"o2","type":"agentMessage","text":"旧回复"}]}
            ]}
        """.trimIndent()))
        assertEquals(listOf("旧问题", "旧回复", "新问题", "中途停止"), history.map { it.text })
        assertTrue(history[1].complete)
        assertFalse(history.last().complete)
        assertFalse(history.toString().contains("新问题"))
    }

    @Test
    fun `new and resumed threads get current saved instructions including an explicit empty snapshot`() = runBlocking {
        var saved = "第一次保存的交代"
        val first = CodexWire.thread("/private/work", "gpt-test", null, readCodexOwnerSnapshot { saved })
        assertTrue(first.str("developerInstructions").orEmpty().contains(saved))

        saved = "后来明确修改的交代"
        val resumed = CodexWire.thread("/private/work", "gpt-test", "thread_1", readCodexOwnerSnapshot { saved })
        assertEquals("thread_1", resumed.str("threadId"))
        val current = resumed.str("developerInstructions").orEmpty()
        assertTrue(current.contains(saved))
        assertFalse(current.contains("第一次保存的交代"))
        assertTrue(current.contains("聊天历史和草稿不代表当前规则"))

        saved = ""
        val removed = CodexWire.thread("/private/work", "gpt-test", "thread_1", readCodexOwnerSnapshot { saved })
            .str("developerInstructions").orEmpty()
        assertTrue(removed.contains("当前没有生效的已保存交代"))
        assertFalse(removed.contains("后来明确修改的交代"))
    }

    @Test
    fun `saved instruction snapshot rejects oversized and unavailable reads without stale data`() = runBlocking {
        val good = "新".repeat(8_000)
        assertEquals(good, readCodexOwnerSnapshot { good })
        for (source in listOf({ good + "超" }, { error("private storage detail") })) {
            val failure = runCatching { readCodexOwnerSnapshot(source) }.exceptionOrNull() as? CodexProblem
            assertEquals(CodexIssue.OWNER_INSTRUCTIONS, failure?.issue)
            assertFalse(failure.toString().contains("private storage detail"))
        }
    }

    @Test
    fun `API keys are restricted to the exact official provider endpoint`() {
        assertTrue(CodexOwnerRuntime.isOfficialEndpoint("https://api.openai.com/v1"))
        assertTrue(CodexOwnerRuntime.isOfficialEndpoint("https://api.openai.com:443/v1/"))
        for (untrusted in listOf(
            "http://api.openai.com/v1", "https://api.openai.com.evil.test/v1",
            "https://api.openai.com:444/v1", "https://api.openai.com@evil.test/v1",
            "https://evil@api.openai.com/v1", "https://api.openai.com/v1?q=1",
            "https://api.openai.com/v1#other", "https://api.openai.com/%76%31",
        )) assertFalse(untrusted, CodexOwnerRuntime.isOfficialEndpoint(untrusted))
    }

    @Test
    fun `same provider id cannot reopen a thread after its credential changes`() {
        val original = codexKeyFingerprint("abc")
        val replacement = codexKeyFingerprint("different")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", original)
        assertTrue(codexKeyMatchesThread("thread_1", original, original))
        assertFalse(codexKeyMatchesThread("thread_1", original, replacement))
        assertFalse(codexKeyMatchesThread("legacy_thread", null, original))
        assertTrue(codexKeyMatchesThread(null, null, replacement))
    }

    @Test
    fun `upstream errors are mapped without exposing the raw message`() {
        val issue = CodexWire.error(parse("""{"code":401,"message":"private credential detail"}"""))
        assertEquals(CodexIssue.AUTH, issue)
        assertFalse(CodexProblem(issue).toString().contains("private credential detail"))
    }

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject
}
