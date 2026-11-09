package com.wangzan101.mika.calls.voiceclone

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A bounded, self-describing sample. The uploader never receives an open microphone stream. */
internal object VoiceCloneWav {
    const val RATE = 24_000
    const val BYTES_PER_SAMPLE = 2
    const val MIN_SECONDS = 14
    const val MAX_SECONDS = 30
    const val MIN_PCM_BYTES = RATE * BYTES_PER_SAMPLE * MIN_SECONDS
    const val MAX_PCM_BYTES = RATE * BYTES_PER_SAMPLE * MAX_SECONDS
    const val HEADER_BYTES = 44
    const val MIN_WAV_BYTES = (HEADER_BYTES + MIN_PCM_BYTES).toLong()
    const val MAX_WAV_BYTES = (HEADER_BYTES + MAX_PCM_BYTES).toLong()

    fun milliseconds(count: Int): Long = count.toLong() * 1000 / (RATE * BYTES_PER_SAMPLE)

    fun write(file: File, pcm: ByteArray, count: Int) =
        file.outputStream().buffered().use { write(it, pcm, count) }

    fun write(output: OutputStream, pcm: ByteArray, count: Int) {
        require(count in MIN_PCM_BYTES..MAX_PCM_BYTES && count <= pcm.size && count % BYTES_PER_SAMPLE == 0)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(36 + count)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1) // linear PCM
            .putShort(1) // mono
            .putInt(RATE)
            .putInt(RATE * BYTES_PER_SAMPLE)
            .putShort(BYTES_PER_SAMPLE.toShort())
            .putShort((8 * BYTES_PER_SAMPLE).toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(count)
            .array()
        output.write(header)
        output.write(pcm, 0, count)
    }
}
