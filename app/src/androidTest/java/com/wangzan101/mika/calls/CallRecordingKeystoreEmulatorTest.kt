package com.wangzan101.mika.calls

import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Disposable emulator only: -e recordingEmulator true. Uses actual Android pipes and Keystore,
 * synthetic PCM, isolated directories and a fresh test-only key alias. Never opens phone audio,
 * accesses business history, changes calling settings, starts a cloud session or places a call.
 * Passing this verifies local recording infrastructure, not capture on a physical phone.
 */
@RunWith(AndroidJUnit4::class)
class CallRecordingKeystoreEmulatorTest {
    @Test fun actualPipeAndKeystorePreserveCallerAndAiAcrossAuthenticatedChunks() = runBlocking {
        roundTrip(packets = 70, cleanTerminal = true)
    }

    @Test fun actualPipeEofWithoutTerminalStillPreservesAPlayableEncryptedPartial() = runBlocking {
        roundTrip(packets = 3, cleanTerminal = false)
    }

    private suspend fun roundTrip(packets: Int, cleanTerminal: Boolean) {
        requireDisposableEmulator()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals(app.applicationInfo.uid, Process.myUid())
        assertTrue("Test must use the ordinary application UID", Process.myUid() >= 10_000)
        val testId = UUID.randomUUID().toString()
        val alias = "flashphone-recording-test-$testId"
        val root = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "recording-emulator-").toFile()
        val archives = File(root, "archives")
        val cache = File(root, "playback")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = ObservedCipher(KeystoreKeyCipher(alias))
        val driver = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var pipe: Array<ParcelFileDescriptor>? = null
        var received: ParcelFileDescriptor? = null
        var producer: Job? = null
        var session: CallRecordingSession? = null
        var store: CallRecordingStore? = null
        try {
            assertFalse("The isolated recording alias must be fresh", keyStore.containsAlias(alias))
            val recordingStore = CallRecordingStore(archives, cache, cipher)
            store = recordingStore
            val descriptors = ParcelFileDescriptor.createPipe()
            pipe = descriptors
            // As with the helper's Bundle return, the receiver must own a descriptor that
            // survives parcel recycling and closure of the server's serialized copy.
            val transferred = parcelRecordingDescriptor(descriptors[0])
            received = transferred
            val input = ParcelFileDescriptor.AutoCloseInputStream(transferred)
            val recordingSession = recordingStore.start(testId, input)
            session = recordingSession
            val producerJob = driver.launch {
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])).use { output ->
                    repeat(packets) { packet ->
                        val endMicros = (packet + 1) * 20_000L
                        output.frame(1, endMicros, pcm(320, 1000 + packet))
                        output.frame(2, endMicros, pcm(480, 2000 + packet))
                    }
                    if (cleanTerminal) output.frame(3, packets * 20_000L, byteArrayOf())
                }
            }
            producer = producerJob
            withTimeout(20_000L) {
                producerJob.join()
                assertFalse("Synthetic pipe producer was cancelled", producerJob.isCancelled)
                val info = recordingSession.finish()
                assertNotNull("Recording commit failed: ${cipher.diagnostic()}", info)
                checkNotNull(info)
                assertEquals(testId, info.id)
                assertEquals(packets * 20L, info.durationMs)
                assertEquals(!cleanTerminal, info.partial)
                assertTrue("Android Keystore created only the test alias", keyStore.containsAlias(alias))
                assertEquals(setOf(testId), recordingStore.availableIds())
                assertTrue("Scratch PCM was removed after finish", File(archives, "scratch").listFiles().orEmpty().isEmpty())
                assertTrue("Ciphertext was committed atomically", archives.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
                val wav = recordingStore.playbackFile(testId)
                assertNotNull("Recording decrypt failed: ${cipher.diagnostic()}", wav)
                val bytes = checkNotNull(wav).readBytes()
                try {
                    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                    assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
                    assertEquals(2, header.getShort(22).toInt())
                    assertEquals(16_000, header.getInt(24))
                    assertEquals(44 + packets * 320 * 4, bytes.size)
                    for (packet in 0 until packets) {
                        for (position in listOf(packet * 320, packet * 320 + 319)) {
                            assertEquals("Caller PCM on the left", 1000 + packet, header.getShort(44 + position * 4).toInt())
                            assertEquals("24-to-16 kHz AI PCM on the right", 2000 + packet, header.getShort(46 + position * 4).toInt())
                        }
                    }
                    if (cleanTerminal) {
                        assertTrue("Exercise a full 64 KiB encrypted data block on Android", cipher.largestPlain.get() >= 64 * 1024)
                        assertTrue("Data spans multiple authenticated chunks plus the trailer", cipher.encryptCalls.get() >= 3)
                    }
                    assertTrue("Decode used the actual Keystore cipher", cipher.decryptCalls.get() >= 2)
                } finally {
                    bytes.fill(0)
                }
                recordingStore.closePlayback(wav)
                assertFalse("Decrypted playback lease was deleted", wav.exists())
                assertTrue("Delete removed the encrypted recording", recordingStore.delete(testId))
                assertTrue(recordingStore.availableIds().isEmpty())
                assertNull(recordingStore.playbackFile(testId))
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                pipe?.forEach { runCatching { it.close() } }
                runCatching { received?.close() }
                producer?.cancel()
                withTimeoutOrNull(2_000L) { producer?.join() }
                runCatching { session?.finish() }
                runCatching { store?.closePlayback() }
                driver.cancel()
                try {
                    keyStore.deleteEntry(alias)
                    assertFalse("Test-only Keystore alias was removed", keyStore.containsAlias(alias))
                } finally {
                    assertTrue("Isolated recording files were removed", root.deleteRecursively())
                }
            }
        }
    }

    private fun parcelRecordingDescriptor(serverReadEnd: ParcelFileDescriptor): ParcelFileDescriptor {
        val parcel = Parcel.obtain()
        var client: ParcelFileDescriptor? = null
        try {
            Bundle().apply {
                putInt("recordingProtocol", 1)
                putString("recordingStatus", "available")
                putParcelable("recording", serverReadEnd)
            }.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val received = Bundle.CREATOR.createFromParcel(parcel)
            assertEquals(1, received.getInt("recordingProtocol"))
            client = if (Build.VERSION.SDK_INT >= 33) received.getParcelable("recording", ParcelFileDescriptor::class.java)
                else @Suppress("DEPRECATION") received.getParcelable("recording")
            assertNotNull("Parcel must transfer the monitor descriptor", client)
            serverReadEnd.close()
            return checkNotNull(client)
        } catch (error: Exception) {
            runCatching { client?.close() }
            throw error
        } finally {
            parcel.recycle()
        }
    }

    private fun requireDisposableEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e recordingEmulator true only on a disposable development emulator",
            InstrumentationRegistry.getArguments().getString("recordingEmulator") == "true" &&
                instrumentation.targetContext.packageName == "com.wangzan101.mika.dev")
        val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        assumeTrue("Recording integration is forbidden on a physical phone", qemu == "1")
    }

    private fun DataOutputStream.frame(kind: Int, endMicros: Long, payload: ByteArray) {
        writeInt(kind); writeLong(endMicros); writeInt(payload.size); write(payload)
    }

    private fun pcm(samples: Int, value: Int): ByteArray = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { repeat(samples) { putShort(value.toShort()) } }.array()

    /** No audio/key/exception message logging; test failures retain only operation counts and class. */
    private class ObservedCipher(private val actual: KeyCipher) : KeyCipher {
        val encryptCalls = AtomicInteger()
        val decryptCalls = AtomicInteger()
        val largestPlain = AtomicInteger()
        @Volatile private var failure = "none"
        override fun encrypt(plain: ByteArray): ByteArray {
            encryptCalls.incrementAndGet()
            largestPlain.updateAndGet { maxOf(it, plain.size) }
            return try { actual.encrypt(plain) } catch (error: Exception) {
                failure = "encrypt:${error.javaClass.simpleName}"; throw error
            }
        }
        override fun decrypt(data: ByteArray): ByteArray {
            decryptCalls.incrementAndGet()
            return try { actual.decrypt(data) } catch (error: Exception) {
                failure = "decrypt:${error.javaClass.simpleName}"; throw error
            }
        }
        fun diagnostic(): String = "encrypt_calls=${encryptCalls.get()} decrypt_calls=${decryptCalls.get()} failure=$failure"
    }
}
