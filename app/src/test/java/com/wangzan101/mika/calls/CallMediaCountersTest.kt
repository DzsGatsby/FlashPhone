package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallMediaCountersTest {
    @Test fun summarizesSignedPcmAndTimingWithoutRetainingFrames() {
        val counters = CallMediaCounters()
        counters.inputAccepted(pcm(200, -200), 100_000_000L)
        assertEquals(0L, counters.snapshot()["input_first_rms_over_200_frame"])
        assertEquals(0L, counters.snapshot()["input_last_rms_over_200_frame"])
        counters.inputAccepted(pcm(0, 400), 105_000_000L)
        counters.inputAccepted(pcm(-32768), 115_000_000L)
        counters.inputAccepted(pcm(0, 1), 136_000_000L)

        val result = counters.snapshot()
        assertEquals(4L, result["input_frames_accepted"])
        assertEquals(14L, result["input_bytes_accepted"])
        assertEquals(32768L, result["input_peak_max"])
        assertEquals(2L, result["input_rms_over_200_frames"]) // Exactly 200 does not qualify.
        assertEquals(2L, result["input_first_rms_over_200_frame"])
        assertEquals(3L, result["input_last_rms_over_200_frame"])
        assertEquals(21_000L, result["input_max_gap_us"])
        assertEquals(1L, result["input_under_10ms_gap_frames"]) // The first frame and exactly 10 ms do not qualify.
    }

    @Test fun recordsProviderEventCountsWithoutTextOrPcmContent() {
        val counters = CallMediaCounters()
        counters.eventReceived(VolcRealtimeEvent.UserSpeaking)
        counters.eventReceived(VolcRealtimeEvent.UserTranscript("private test input", false))
        counters.eventReceived(VolcRealtimeEvent.UserTranscript("private test input", true))
        counters.eventReceived(VolcRealtimeEvent.UserSpeechEnded)
        counters.eventReceived(VolcRealtimeEvent.AssistantAudioStarted)
        counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(12)))
        counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(8)))
        counters.eventReceived(VolcRealtimeEvent.AssistantAudioEnded)

        val result = counters.snapshot()
        assertEquals(1L, result["user_speaking_events"])
        assertEquals(1L, result["user_speech_ended_events"])
        assertEquals(1L, result["user_transcript_final_events"])
        assertEquals(1L, result["user_transcript_partial_events"])
        assertEquals(1L, result["assistant_audio_started_events"])
        assertEquals(2L, result["assistant_audio_events"])
        assertEquals(20L, result["assistant_audio_bytes"])
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2) { index ->
        val sample = samples[index / 2]
        (if (index % 2 == 0) sample else sample shr 8).toByte()
    }
}
