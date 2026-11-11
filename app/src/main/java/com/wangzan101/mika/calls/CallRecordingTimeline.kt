package com.wangzan101.mika.calls

import java.io.RandomAccessFile

/** Bounded stereo WAV assembler. The helper timestamps are observation ends, not acoustic timestamps. */
internal class CallRecordingTimeline(private val wav: RandomAccessFile) : AutoCloseable {
    private var frames = 0L
    private val channelEnd = LongArray(2)
    private var inputIndex = 0L
    private var nextOutputTwice = 0L
    private var previousSample = 0
    var hasAudio = false
        private set
    val durationMs: Long get() = frames * 1000L / CallRecordingLimits.RATE

    init {
        wav.setLength(0)
        wav.write(ByteArray(CallRecordingLimits.WAV_HEADER_BYTES))
    }

    /** Returns false at the recording duration limit. Never allocates based on an untrusted length. */
    fun append(kind: Int, offsetMicros: Long, pcm: ByteArray): Boolean {
        require(kind == 1 || kind == 2)
        require(pcm.size in 2..CallRecordingLimits.MAX_PAYLOAD && pcm.size % 2 == 0)
        require(offsetMicros >= 0L)
        val rate = if (kind == 1) CallRecordingLimits.RATE else 24_000
        val samples = if (kind == 1) pcm else resample(pcm)
        if (samples.isEmpty()) return true
        val channel = kind - 1
        val startMicros = (offsetMicros - pcm.size * 1_000_000L / (rate * 2L)).coerceAtLeast(0L)
        if (startMicros >= CallRecordingLimits.MAX_SECONDS * 1_000_000L) return false
        val start = maxOf(channelEnd[channel], startMicros * CallRecordingLimits.RATE / 1_000_000L)
        val count = minOf(samples.size / 2L, CallRecordingLimits.MAX_FRAMES - start).coerceAtLeast(0L).toInt()
        if (count == 0) return false
        val interleaved = ByteArray(count * 4)
        val position = CallRecordingLimits.WAV_HEADER_BYTES + start * 4L
        val existing = minOf(interleaved.size.toLong(), (wav.length() - position).coerceAtLeast(0L)).toInt()
        wav.seek(position)
        if (existing > 0) wav.readFully(interleaved, 0, existing)
        repeat(count) { frame ->
            interleaved[frame * 4 + channel * 2] = samples[frame * 2]
            interleaved[frame * 4 + channel * 2 + 1] = samples[frame * 2 + 1]
        }
        wav.seek(position)
        wav.write(interleaved)
        channelEnd[channel] = start + count
        frames = maxOf(frames, start + count)
        hasAudio = true
        return count == samples.size / 2 && offsetMicros <= CallRecordingLimits.MAX_SECONDS * 1_000_000L
    }

    fun endAt(offsetMicros: Long) {
        require(offsetMicros >= 0)
        frames = maxOf(frames, minOf(offsetMicros, CallRecordingLimits.MAX_SECONDS * 1_000_000L) * CallRecordingLimits.RATE / 1_000_000L)
    }

    // Output positions 0, 1.5, 3, ... in the 24 kHz input. Preserve phase across packet boundaries.
    private fun resample(pcm: ByteArray): ByteArray {
        val output = ByteArray((pcm.size / 2 + 1) * 2)
        var size = 0
        for (position in pcm.indices step 2) {
            val current = ((pcm[position].toInt() and 255) or (pcm[position + 1].toInt() shl 8)).toShort().toInt()
            if (nextOutputTwice <= inputIndex * 2L) {
                val sample = if (nextOutputTwice % 2L == 0L) current else (previousSample + current) / 2
                output[size++] = sample.toByte()
                output[size++] = (sample shr 8).toByte()
                nextOutputTwice += 3L
            }
            previousSample = current
            inputIndex++
        }
        return output.copyOf(size)
    }

    override fun close() {
        try {
            val dataBytes = frames * 4L
            wav.setLength(CallRecordingLimits.WAV_HEADER_BYTES + dataBytes)
            wav.seek(0)
            wav.writeBytes("RIFF")
            wav.le32(dataBytes + 36)
            wav.writeBytes("WAVEfmt ")
            wav.le32(16)
            wav.le16(1)
            wav.le16(2)
            wav.le32(CallRecordingLimits.RATE.toLong())
            wav.le32(CallRecordingLimits.RATE * 4L)
            wav.le16(4)
            wav.le16(16)
            wav.writeBytes("data")
            wav.le32(dataBytes)
        } finally {
            wav.close()
        }
    }

    private fun RandomAccessFile.le16(value: Int) { write(value and 255); write((value ushr 8) and 255) }
    private fun RandomAccessFile.le32(value: Long) { repeat(4) { write(((value ushr (it * 8)) and 255).toInt()) } }
}
