package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallAudioTest {
    @Test
    fun explicitlySelectedBackendNeverFallsBackToAnotherRoute() {
        assertTrue(ShellCallAudio.candidates(ShellCallAudio.DIRECT, false, true).isEmpty())
        assertTrue(ShellCallAudio.candidates(ShellCallAudio.REDIRECT, true, false).isEmpty())
        assertEquals(listOf(ShellCallAudio.DIRECT, ShellCallAudio.REDIRECT),
            ShellCallAudio.candidates(ShellCallAudio.AUTO, true, true))
    }

    @Test
    fun capturePeakDetectsQuietAndSignedLittleEndianSamples() {
        assertEquals(0, ShellCallAudio.pcmPeak(ByteArray(640), 640))
        assertEquals(32768, ShellCallAudio.pcmPeak(byteArrayOf(0, 0x80.toByte(), 10, 0), 4))
        assertEquals(256, ShellCallAudio.pcmPeak(byteArrayOf(0, 1, 0x7f), 3))
    }
}
