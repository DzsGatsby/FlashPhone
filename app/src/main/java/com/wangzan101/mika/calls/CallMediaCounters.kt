package com.wangzan101.mika.calls

import kotlin.math.abs

/** Fixed-size, debug-only observations. Never retains PCM, event text or wall-clock timestamps. */
internal class CallMediaCounters {
    private var inputFramesAccepted = 0L
    private var inputBytesAccepted = 0L
    private var inputPeakMax = 0L
    private var inputRmsOver200Frames = 0L
    private var inputFirstRmsOver200Frame = 0L
    private var inputLastRmsOver200Frame = 0L
    private var inputLastFrameNanos = 0L
    private var inputMaxGapNanos = 0L
    private var inputUnder10msGapFrames = 0L
    private var userSpeakingEvents = 0L
    private var userSpeechEndedEvents = 0L
    private var userTranscriptFinalEvents = 0L
    private var userTranscriptPartialEvents = 0L
    private var assistantAudioStartedEvents = 0L
    private var assistantAudioEndedEvents = 0L
    private var assistantInterruptedEvents = 0L
    private var assistantTextEndedEvents = 0L
    private var assistantAudioEvents = 0L
    private var assistantAudioBytes = 0L
    private var providerFailedEvents = 0L
    private var providerClosedEvents = 0L

    /** Called only after sendPcm16 accepts the frame locally; not a server delivery acknowledgement. */
    fun inputAccepted(frame: ByteArray, acceptedAtNanos: Long) {
        var peak = 0
        var sumSquares = 0L
        var offset = 0
        while (offset + 1 < frame.size) {
            val sample = (frame[offset].toInt() and 0xff) or (frame[offset + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample))
            sumSquares += sample.toLong() * sample
            offset += 2
        }
        synchronized(this) {
            if (inputFramesAccepted > 0) {
                val gap = (acceptedAtNanos - inputLastFrameNanos).coerceAtLeast(0L)
                inputMaxGapNanos = maxOf(inputMaxGapNanos, gap)
                if (gap < TEN_MS_NANOS) inputUnder10msGapFrames++
            }
            inputLastFrameNanos = acceptedAtNanos
            inputFramesAccepted++
            inputBytesAccepted += frame.size
            inputPeakMax = maxOf(inputPeakMax, peak.toLong())
            // Compare RMS squared to avoid a square root on every 20 ms frame.
            if (sumSquares > (offset / 2) * RMS_THRESHOLD_SQUARED) {
                inputRmsOver200Frames++
                // Successful send indices are one-based; zero means no frame crossed the threshold.
                if (inputFirstRmsOver200Frame == 0L) inputFirstRmsOver200Frame = inputFramesAccepted
                inputLastRmsOver200Frame = inputFramesAccepted
            }
        }
    }

    @Synchronized
    fun eventReceived(event: VolcRealtimeEvent) {
        when (event) {
            VolcRealtimeEvent.UserSpeaking -> userSpeakingEvents++
            VolcRealtimeEvent.UserSpeechEnded -> userSpeechEndedEvents++
            is VolcRealtimeEvent.UserTranscript -> {
                if (event.isFinal) userTranscriptFinalEvents++ else userTranscriptPartialEvents++
            }
            VolcRealtimeEvent.AssistantAudioStarted -> assistantAudioStartedEvents++
            VolcRealtimeEvent.AssistantAudioEnded -> assistantAudioEndedEvents++
            VolcRealtimeEvent.AssistantInterrupted -> assistantInterruptedEvents++
            VolcRealtimeEvent.AssistantTextEnded -> assistantTextEndedEvents++
            is VolcRealtimeEvent.Audio -> {
                assistantAudioEvents++
                assistantAudioBytes += event.pcm.size
            }
            is VolcRealtimeEvent.Failed -> providerFailedEvents++
            VolcRealtimeEvent.Closed -> providerClosedEvents++
            else -> Unit
        }
    }

    @Synchronized
    fun snapshot(): Map<String, Long> = mapOf(
        "input_frames_accepted" to inputFramesAccepted,
        "input_bytes_accepted" to inputBytesAccepted,
        "input_peak_max" to inputPeakMax,
        "input_rms_over_200_frames" to inputRmsOver200Frames,
        "input_first_rms_over_200_frame" to inputFirstRmsOver200Frame,
        "input_last_rms_over_200_frame" to inputLastRmsOver200Frame,
        "input_max_gap_us" to inputMaxGapNanos / 1_000,
        "input_under_10ms_gap_frames" to inputUnder10msGapFrames,
        "user_speaking_events" to userSpeakingEvents,
        "user_speech_ended_events" to userSpeechEndedEvents,
        "user_transcript_final_events" to userTranscriptFinalEvents,
        "user_transcript_partial_events" to userTranscriptPartialEvents,
        "assistant_audio_started_events" to assistantAudioStartedEvents,
        "assistant_audio_ended_events" to assistantAudioEndedEvents,
        "assistant_interrupted_events" to assistantInterruptedEvents,
        "assistant_text_ended_events" to assistantTextEndedEvents,
        "assistant_audio_events" to assistantAudioEvents,
        "assistant_audio_bytes" to assistantAudioBytes,
        "provider_failed_events" to providerFailedEvents,
        "provider_closed_events" to providerClosedEvents,
    )

    private companion object {
        const val TEN_MS_NANOS = 10_000_000L
        const val RMS_THRESHOLD_SQUARED = 200L * 200L
    }
}
