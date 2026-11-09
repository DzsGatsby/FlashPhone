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

    @Test
    fun playbackDiagnosticsTracksPendingZeroWritesAndFreezesAtStop() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertEquals(-1L, stats.lastWriteAgeMs(0L))
        stats.recordWrite(0, 0L)
        stats.recordWrite(0, 6L)
        assertEquals(2L, stats.writeZeroCount)
        assertEquals(12L, stats.zeroMaxMs(12L))
        stats.recordWrite(480, 15L)
        assertEquals(15L, stats.zeroMaxMs(45L))
        assertEquals(30L, stats.lastWriteAgeMs(45L))

        stats.recordWrite(0, 50L)
        stats.stop(71L)
        assertEquals(3L, stats.writeZeroCount)
        assertEquals(21L, stats.zeroMaxMs(10_000L))
        assertEquals(56L, stats.lastWriteAgeMs(10_000L))
    }

    @Test
    fun flushingOrLosingTheRouteDoesNotExtendTheZeroWriteStreak() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        stats.recordWrite(0, 100L)
        stats.endZeroStreak(114L)
        stats.recordWrite(0, 800L)
        stats.recordWrite(960, 811L)
        assertEquals(2L, stats.writeZeroCount)
        assertEquals(14L, stats.zeroMaxMs(900L))
        stats.recordFlush(21L)
        stats.recordFlush(12L)
        assertEquals(21L, stats.flushMaxMs)
    }

    @Test
    fun playbackHeadIsAnUnsignedPositionAndCanBeInvalidatedAfterFlush() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertEquals(-1L, stats.playbackHeadFrames)
        stats.recordPlaybackHead(Int.MIN_VALUE)
        assertEquals(0x8000_0000L, stats.playbackHeadFrames)
        stats.resetPlaybackHead()
        assertEquals(-1L, stats.playbackHeadFrames)
        stats.recordPlaybackHead(0)
        assertEquals(0L, stats.playbackHeadFrames)
    }
}
