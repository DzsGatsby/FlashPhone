package com.wangzan101.mika.calls.voiceclone

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceCloneWavTest {
    @Test
    fun exportsMono24kPcm16WithExactPayloadAndNoFramingLoss() {
        val pcm = ByteArray(VoiceCloneWav.MIN_PCM_BYTES).also {
            it[0] = 0x34
            it[1] = 0x12
            it[it.lastIndex] = 0x21
        }
        val out = ByteArrayOutputStream()
        VoiceCloneWav.write(out, pcm, pcm.size)
        val result = out.toByteArray()
        val header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", result.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(result.size - 8, header.getInt(4))
        assertEquals("WAVEfmt ", result.copyOfRange(8, 16).toString(Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(24000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, result.copyOfRange(44, result.size))
    }

    @Test
    fun rejectsTruncatedSampleOrDurationAboveThirtySeconds() {
        val pcm = ByteArray(VoiceCloneWav.MAX_PCM_BYTES + 2)
        for (size in listOf(VoiceCloneWav.MIN_PCM_BYTES - 2, VoiceCloneWav.MIN_PCM_BYTES + 1, pcm.size)) {
            assertThrows(IllegalArgumentException::class.java) {
                VoiceCloneWav.write(ByteArrayOutputStream(), pcm, size)
            }
        }
    }
}
