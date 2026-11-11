package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallRecordingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val records get() = File(temporary.root, "records")
    private val playback get() = File(temporary.root, "playback")

    @Test fun `caller and accepted AI are aligned on separate channels with trailing silence`() = runBlocking {
        val store = store()
        val id = id()
        val source = frames {
            frame(1, 20_000, pcm(320) { 1000 })
            frame(2, 20_000, pcm(480) { 2000 })
            frame(3, 40_000)
        }
        val session = store.start(id, ByteArrayInputStream(source))
        val info = checkNotNull(session.finish())
        assertEquals(id, info.id)
        assertEquals(40L, info.durationMs)
        assertFalse(info.partial)
        assertEquals(info, session.finish())
        val wav = checkNotNull(store.playbackFile(id))
        val bytes = wav.readBytes()
        assertEquals(44 + 640 * 4, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1000, sample(bytes, 0, 0))
        assertEquals(2000, sample(bytes, 0, 1))
        assertEquals(1000, sample(bytes, 319, 0))
        assertEquals(2000, sample(bytes, 319, 1))
        assertEquals(0, sample(bytes, 320, 0))
        assertEquals(0, sample(bytes, 320, 1))
        assertEquals(info.bytes, File(records, "$id.fpc").length())
        assertFalse(File(records, "$id.fpc").readBytes().contentEquals(bytes))
        assertTrue(File(records, "scratch").listFiles().orEmpty().isEmpty())
        store.closePlayback(wav)
        assertFalse(wav.exists())
    }

    @Test fun `resampling keeps phase across fragmented packets and timestamp gaps keep silence`() = runBlocking {
        val store = store()
        val id = id()
        val source = frames {
            // Two 24 kHz fragments constitute samples 0,1000,2000,3000,4000,5000.
            frame(2, 125, pcm(3) { it * 1000 })
            frame(2, 250, pcm(3) { (it + 3) * 1000 })
            frame(1, 1_000_125, pcm(2) { -2000 })
            frame(3, 1_000_125)
        }
        val info = checkNotNull(store.start(id, ByteArrayInputStream(source)).finish())
        assertFalse(info.partial)
        val bytes = checkNotNull(store.playbackFile(id)).readBytes()
        assertEquals(listOf(0, 1500, 3000, 4500), (0..3).map { sample(bytes, it, 1) })
        assertEquals(0, sample(bytes, 10_000, 0))
        assertEquals(-2000, sample(bytes, 16_000, 0))
        assertEquals(0, sample(bytes, 16_000, 1))
        store.closePlayback()
    }

    @Test fun `EOF or malformed monitor after PCM yields playable partial without trusting a length`() = runBlocking {
        val store = store()
        for (badTail in listOf(byteArrayOf(), frames { writeInt(1); writeLong(30_000); writeInt(Int.MAX_VALUE) },
            frames { frame(2, -1, byteArrayOf(0, 0)) })) {
            val id = id()
            val source = frames { frame(1, 20_000, pcm(320) { 50 }); write(badTail) }
            val info = checkNotNull(store.start(id, ByteArrayInputStream(source)).finish())
            assertTrue(info.partial)
            assertNotNull(store.playbackFile(id))
        }
        store.closePlayback()
    }

    @Test fun `valid empty recording returns no phantom playback and releases the active slot`() = runBlocking {
        val store = store()
        assertNull(store.start(id(), ByteArrayInputStream(frames { frame(3, 10_000) })).finish())
        assertNotNull(store.start(id(), ByteArrayInputStream(simple())).finish())
    }

    @Test fun `finish allows the stopped helper terminal to arrive before closing the reader`() = runBlocking {
        val store = store()
        val input = PipedInputStream(4096)
        val output = PipedOutputStream(input)
        val session = store.start(id(), input)
        output.write(frames { frame(1, 20_000, pcm(320) { 500 }) })
        val finishing = async { session.finish() }
        try {
            delay(100)
            assertFalse("finish waits for the clean monitor terminal", finishing.isCompleted)
            output.write(frames { frame(3, 20_000) })
            output.close()
            assertFalse(checkNotNull(finishing.await()).partial)
        } finally {
            output.close()
            input.close()
        }
    }

    @Test fun `path traversal is rejected and invalid start still closes the monitor`() = runBlocking {
        val store = store()
        val outside = File(temporary.root, "keep.wav").apply { writeText("keep") }
        var closed = false
        val monitor = object : ByteArrayInputStream(simple()) { override fun close() { closed = true; super.close() } }
        assertTrue(runCatching { store.start("../keep", monitor) }.isFailure)
        assertTrue(closed)
        assertNull(store.playbackFile("../keep"))
        assertFalse(store.delete("../keep"))
        assertEquals("keep", outside.readText())
    }

    @Test fun `a stale player cleanup cannot delete a newer plaintext lease`() = runBlocking {
        val store = store()
        val firstId = id()
        val secondId = id()
        store.start(firstId, ByteArrayInputStream(simple())).finish()
        store.start(secondId, ByteArrayInputStream(simple())).finish()
        val first = checkNotNull(store.playbackFile(firstId))
        val second = checkNotNull(store.playbackFile(secondId))
        assertFalse(first.exists())
        store.closePlayback(first)
        assertTrue(second.exists())
        store.closePlayback(second)
        assertFalse(second.exists())
    }

    @Test fun `corrupted ciphertext and truncated authenticated trailer never leave playable plaintext`() = runBlocking {
        val store = store()
        val id = id()
        store.start(id, ByteArrayInputStream(simple())).finish()
        val file = File(records, "$id.fpc")
        val encrypted = file.readBytes()
        file.writeBytes(encrypted.copyOf(encrypted.size - 1))
        assertNull(store.playbackFile(id))
        file.writeBytes(encrypted.copyOf().apply { this[30] = (this[30].toInt() xor 1).toByte() })
        assertNull(store.playbackFile(id))
        assertTrue(playback.listFiles().orEmpty().isEmpty())
    }

    @Test fun `recording ciphertext cannot be substituted for another recording ID`() = runBlocking {
        val store = store()
        val firstId = id()
        store.start(firstId, ByteArrayInputStream(simple())).finish()
        val otherId = id()
        File(records, "$firstId.fpc").copyTo(File(records, "$otherId.fpc"))
        assertNull(store.playbackFile(otherId))
        assertNotNull(store.playbackFile(firstId))
        store.closePlayback()
    }

    @Test fun `cancellation during decrypt cleans the unpublished plaintext lease`() = runBlocking {
        val decrypting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = cipher()
        val store = store(object : KeyCipher {
            override fun encrypt(plain: ByteArray) = delegate.encrypt(plain)
            override fun decrypt(data: ByteArray): ByteArray {
                decrypting.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return delegate.decrypt(data)
            }
        })
        val id = id()
        store.start(id, ByteArrayInputStream(simple())).finish()
        val opening = async(Dispatchers.Default) { store.playbackFile(id) }
        try {
            assertTrue(withContext(Dispatchers.IO) { decrypting.await(5, TimeUnit.SECONDS) })
            val next = withTimeout(1_000L) { store.start(id(), ByteArrayInputStream(simple())) }
            assertNotNull(next.finish())
            opening.cancel()
        } finally {
            release.countDown()
            opening.cancelAndJoin()
        }
        assertTrue(playback.listFiles().orEmpty().isEmpty())
    }

    @Test fun `previous archive commit does not delay next capture and deletion cannot resurrect it`() = runBlocking {
        val encrypting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockFirst = AtomicBoolean(true)
        val delegate = cipher()
        val store = store(object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                if (blockFirst.getAndSet(false)) {
                    encrypting.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return delegate.encrypt(plain)
            }
            override fun decrypt(data: ByteArray) = delegate.decrypt(data)
        })
        val firstId = id()
        val first = store.start(firstId, ByteArrayInputStream(simple()))
        val committing = async(Dispatchers.Default) { first.finish() }
        try {
            assertTrue(withContext(Dispatchers.IO) { encrypting.await(5, TimeUnit.SECONDS) })
            val nextId = id()
            val next = withTimeout(1_000L) { store.start(nextId, ByteArrayInputStream(simple())) }
            assertNotNull(next.finish())
            store.prune(setOf(nextId))
            assertTrue(store.delete(firstId))
            release.countDown()
            assertNull(committing.await())
            assertFalse(File(records, "$firstId.fpc").exists())
            assertNotNull(store.playbackFile(nextId))
            store.closePlayback()
        } finally {
            release.countDown()
            committing.cancelAndJoin()
        }
    }

    @Test fun `startup removes crash scratch and cache without touching encrypted recordings`() = runBlocking {
        val store = store()
        val id = id()
        store.start(id, ByteArrayInputStream(simple())).finish()
        File(records, "scratch/crashed.wav").writeText("raw leftover")
        File(playback, "old.wav").writeText("old plaintext")
        File(records, "$id.fpc.tmp").writeText("incomplete encrypted commit")
        val restarted = store()
        restarted.prune(setOf(id))
        assertTrue(File(records, "scratch").listFiles().orEmpty().isEmpty())
        assertTrue(playback.listFiles().orEmpty().isEmpty())
        assertTrue(File(records, "$id.fpc").exists())
        assertFalse(File(records, "$id.fpc.tmp").exists())
    }

    @Test fun `pruning history removes orphan recordings but not an active monitor`() = runBlocking {
        val store = store()
        val oldId = id()
        store.start(oldId, ByteArrayInputStream(simple())).finish()
        val keepId = id()
        store.start(keepId, ByteArrayInputStream(simple())).finish()
        val activeId = id()
        val active = store.start(activeId, ByteArrayInputStream(simple()))
        store.prune(setOf(keepId))
        assertFalse(File(records, "$oldId.fpc").exists())
        assertTrue(File(records, "$keepId.fpc").exists())
        assertNotNull(active.finish())
        assertTrue(File(records, "$activeId.fpc").exists())
        assertTrue(store.delete(keepId))
        assertNull(store.playbackFile(keepId))
    }

    @Test fun `archive handles the full ten minute WAV in authenticated bounded chunks`() {
        val wav = temporary.newFile("long.wav")
        CallRecordingTimeline(RandomAccessFile(wav, "rw")).use {
            it.append(1, 63, byteArrayOf(1, 0))
            it.endAt(600_000_000L)
        }
        assertEquals(38_400_044L, wav.length())
        val delegate = cipher()
        var largestPlain = 0
        var chunks = 0
        val archive = CallRecordingArchive(object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                largestPlain = maxOf(largestPlain, plain.size)
                chunks++
                return delegate.encrypt(plain)
            }
            override fun decrypt(data: ByteArray) = delegate.decrypt(data)
        })
        val encrypted = temporary.newFile("long.fpc")
        val replay = temporary.newFile("replay.wav")
        val id = id()
        archive.encrypt(id, wav, encrypted)
        archive.decrypt(id, encrypted, replay)
        assertTrue(chunks > 500)
        assertTrue(largestPlain <= 64 * 1024 + 21)
        assertEquals(wav.length(), replay.length())
    }

    @Test fun `recording duration is capped even if a later monitor timestamp claims more`() = runBlocking {
        val store = store()
        val id = id()
        val source = frames { frame(1, 20_000, pcm(320) { 100 }); frame(3, Long.MAX_VALUE) }
        val info = checkNotNull(store.start(id, ByteArrayInputStream(source)).finish())
        assertTrue(info.partial)
        assertEquals(20L, info.durationMs)
    }

    @Test fun `retention age follows recording capture rather than delayed archive completion`() = runBlocking {
        var clock = 1_000L
        val store = CallRecordingStore(records, playback, cipher(), now = { clock })
        val id = id()
        val session = store.start(id, ByteArrayInputStream(simple()))
        clock = 10_000L
        assertNotNull(session.finish())
        assertEquals(1_000L, File(records, "$id.fpc").lastModified())
    }

    @Test fun `disk retention caps calls and bytes using oldest first`() = runBlocking {
        val store = store()
        store.prune(emptySet())
        val ids = (0..50).map {
            id().also { name -> File(records, "$name.fpc").apply { writeText("encrypted fixture"); setLastModified((it + 1).toLong()) } }
        }.toSet()
        store.prune(ids)
        assertEquals(50, records.listFiles().orEmpty().count { it.name.endsWith(".fpc") })
        records.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        val bigIds = (1..3).map { index ->
            id().also { name ->
                val file = File(records, "$name.fpc")
                RandomAccessFile(file, "rw").use { it.setLength(80L * 1024 * 1024) }
                file.setLastModified(index.toLong())
            }
        }
        store.prune(bigIds.toSet())
        assertFalse(File(records, "${bigIds.first()}.fpc").exists())
        assertTrue(records.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L } <= CallRecordingLimits.MAX_TOTAL_BYTES)
    }

    private fun store(cipher: KeyCipher = cipher()) = CallRecordingStore(records, playback, cipher)
    private fun cipher(): KeyCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmKeyCipher { key }
    }
    private fun id() = UUID.randomUUID().toString()
    private fun frames(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { it.write() }
    }.toByteArray()
    private fun DataOutputStream.frame(kind: Int, offset: Long, pcm: ByteArray = byteArrayOf()) {
        writeInt(kind); writeLong(offset); writeInt(pcm.size); write(pcm)
    }
    private fun simple() = frames { frame(1, 20_000, pcm(320) { 1000 }); frame(3, 20_000) }
    private fun pcm(samples: Int, value: (Int) -> Int) = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(samples) { putShort(value(it).toShort()) }
    }.array()
    private fun sample(wav: ByteArray, frame: Int, channel: Int): Int = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        .getShort(44 + frame * 4 + channel * 2).toInt()
}
