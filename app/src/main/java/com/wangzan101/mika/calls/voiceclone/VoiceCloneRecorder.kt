package com.wangzan101.mika.calls.voiceclone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Ordinary microphone input in the foreground. A carrier call always takes priority. */
internal object VoiceCloneRecorder {
    @SuppressLint("MissingPermission") // The controller checks the grant before acquiring the microphone.
    suspend fun record(
        destination: File,
        stop: AtomicBoolean,
        callIsIdle: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        if (!callIsIdle()) throw VoiceCloneFailure(VoiceCloneError.CALL_BUSY)
        val minBuffer = AudioRecord.getMinBufferSize(
            VoiceCloneWav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw VoiceCloneFailure(VoiceCloneError.RECORDING_FAILED)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            VoiceCloneWav.RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, VoiceCloneWav.RATE / 5),
        )
        val data = ByteArray(VoiceCloneWav.MAX_PCM_BYTES)
        val startedAt = SystemClock.elapsedRealtime()
        var position = 0
        var lastReport = -100L
        var lastCallCheck = startedAt
        try {
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw VoiceCloneFailure(VoiceCloneError.RECORDING_FAILED)
                }
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw VoiceCloneFailure(VoiceCloneError.RECORDING_FAILED)
                }
                while (!stop.get() && position < data.size) {
                    ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    if (now - startedAt >= VoiceCloneWav.MAX_SECONDS * 1000L) break
                    if (now - lastCallCheck >= 200L) {
                        if (!callIsIdle()) throw VoiceCloneFailure(VoiceCloneError.RECORDING_INTERRUPTED)
                        lastCallCheck = now
                    }
                    val length = recorder.read(
                        data, position, minOf(data.size - position, 960), AudioRecord.READ_NON_BLOCKING,
                    )
                    if (length < 0 || length % VoiceCloneWav.BYTES_PER_SAMPLE != 0) {
                        throw VoiceCloneFailure(VoiceCloneError.RECORDING_FAILED)
                    }
                    if (length == 0) {
                        delay(10)
                        continue
                    }
                    position += length
                    val elapsed = VoiceCloneWav.milliseconds(position)
                    if (elapsed - lastReport >= 100L) {
                        lastReport = elapsed
                        onProgress(elapsed)
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
            ensureActive()
            if (!callIsIdle()) throw VoiceCloneFailure(VoiceCloneError.RECORDING_INTERRUPTED)
            if (position < VoiceCloneWav.MIN_PCM_BYTES) throw VoiceCloneFailure(VoiceCloneError.AUDIO_LENGTH)
            VoiceCloneWav.write(destination, data, position)
            VoiceCloneWav.milliseconds(position)
        } finally {
            data.fill(0)
        }
    }
}
