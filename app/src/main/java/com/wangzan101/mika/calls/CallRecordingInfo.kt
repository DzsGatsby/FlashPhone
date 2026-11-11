package com.wangzan101.mika.calls

import kotlinx.serialization.Serializable
import java.util.UUID

/** Local recording: left = captured caller; right = AI accepted by the phone's audio track.
 * Track acceptance is not evidence of far-end delivery; later interruption may discard queued audio.
 */
@Serializable
data class CallRecordingInfo(val id: String, val durationMs: Long, val partial: Boolean, val bytes: Long) {
    override fun toString(): String = "CallRecordingInfo(durationMs=$durationMs, partial=$partial, bytes=$bytes)"
}

internal object CallRecordingLimits {
    const val RATE = 16_000
    const val MAX_SECONDS = 600
    const val MAX_FRAMES = RATE * MAX_SECONDS
    const val WAV_HEADER_BYTES = 44
    const val MAX_WAV_BYTES = MAX_FRAMES * 4L + WAV_HEADER_BYTES
    const val MAX_PAYLOAD = 960
    const val MAX_PACKETS = 100_000
    const val MAX_FILES = 50
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    fun validId(value: String): Boolean = value.length == 36 &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
