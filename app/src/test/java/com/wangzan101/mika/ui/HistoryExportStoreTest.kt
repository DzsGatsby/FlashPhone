package com.wangzan101.mika.ui

import com.wangzan101.mika.settings.AesGcmKeyCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryExportStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private val directory get() = File(temporary.root, "exports")
    private fun store(now: () -> Long = System::currentTimeMillis) = HistoryExportStore(directory, cipher, now)

    @Test fun `large private snapshot survives a new store and exports every byte`() {
        val text = "private-transcript-marker 合成私有转写不得出现在磁盘明文中。".repeat(30_000)
        val token = store().create(text)
        val stored = File(directory, "$token.data").readBytes()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("private-transcript-marker"))
        assertFalse(stored.contentEquals(text.toByteArray()))
        val output = ByteArrayOutputStream()
        assertTrue(store().deliver(token) { output })
        assertEquals(text, output.toString("UTF-8"))
        assertFalse(File(directory, "$token.data").exists())
        assertEquals("ok", File(directory, "$token.done").readText())
    }

    @Test fun `destination is closed before source release and receipt prevents duplicate writes`() {
        val token = store().create("snapshot")
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                assertTrue(File(directory, "$token.data").exists())
                closed = true
                super.close()
            }
        }
        assertTrue(store().deliver(token) { output })
        assertTrue(closed)
        assertTrue(store().deliver(token) { error("Recreation must observe the receipt") })
        store().discard(token)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `cancel removes only its own encrypted snapshot`() {
        val first = store().create("cancel me")
        val second = store().create("keep me")
        store().discard(first)
        assertFalse(File(directory, "$first.data").exists())
        assertTrue(File(directory, "$second.data").exists())
    }

    @Test fun `missing snapshot and destination failure are durable failures without invented content`() {
        val missing = java.util.UUID.randomUUID().toString()
        assertFalse(store().deliver(missing) { error("Missing snapshot must not open destination") })
        val token = store().create("snapshot")
        assertFalse(store().deliver(token) { object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic output failure") }
        } })
        assertFalse(store().deliver(token) { error("Must retain the failure result after recreation") })
        assertFalse(File(directory, "$token.data").exists())
    }

    @Test fun `cleanup bounds orphan retention while protecting a picker that remains open`() {
        val token = store().create("still waiting for the picker")
        val abandoned = store().create("orphan")
        directory.listFiles().orEmpty().forEach { assertTrue(it.setLastModified(1L)) }
        val later = store { HistoryExportStore.MAX_AGE_MS + 2L }
        later.cleanup(setOf(token))
        assertTrue(File(directory, "$token.data").exists())
        assertFalse(File(directory, "$abandoned.data").exists())
        val output = ByteArrayOutputStream()
        assertTrue(later.deliver(token) { output })
        assertEquals("still waiting for the picker", output.toString("UTF-8"))
    }

    @Test fun `capacity refuses additional exports without evicting unfinished ones`() {
        val tokens = List(HistoryExportStore.MAX_ENTRIES) { store().create("pending $it") }
        assertThrows(IllegalStateException::class.java) { store().create("overflow") }
        tokens.forEach { assertTrue(File(directory, "$it.data").exists()) }
        store().discard(tokens.first())
        assertNotNull(store().create("room after cancellation"))
    }

    @Test fun `tampered ciphertext cannot produce exported speech`() {
        val token = store().create("private-transcript-marker")
        val source = File(directory, "$token.data")
        val bytes = source.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        source.writeBytes(bytes)
        assertFalse(store().deliver(token) { error("Authentication must finish before opening output") })
    }

    @Test fun `an interrupted receipt does not discard an unfinished export`() {
        val token = store().create("retained snapshot")
        File(directory, "$token.receipt").writeText("o")
        val output = ByteArrayOutputStream()
        assertTrue(store().deliver(token) { output })
        assertEquals("retained snapshot", output.toString("UTF-8"))
        assertEquals("ok", File(directory, "$token.done").readText())
    }

    @Test fun `a recreated writer waits for destination close then observes the same receipt`() {
        val token = store().create("one immutable snapshot")
        val writing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray) {
                writing.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                assertTrue("Source remains owned until destination closes", File(directory, "$token.data").exists())
                super.write(bytes)
            }
        }
        try {
            val first = pool.submit<Boolean> { store().deliver(token) { output } }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val replacement = pool.submit<Boolean> {
                store().deliver(token) { error("A recreated effect must not truncate completed output") }
            }
            assertTrue(File(directory, "$token.data").exists())
            finish.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(replacement.get(5, TimeUnit.SECONDS))
            assertEquals("one immutable snapshot", output.toString("UTF-8"))
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `untrusted references cannot escape the private directory`() {
        assertThrows(IllegalArgumentException::class.java) { store().discard("../outside") }
        assertThrows(IllegalArgumentException::class.java) { store().deliver("../outside") { ByteArrayOutputStream() } }
    }
}
