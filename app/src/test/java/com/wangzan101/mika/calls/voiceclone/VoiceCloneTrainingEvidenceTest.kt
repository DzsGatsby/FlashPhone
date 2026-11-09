package com.wangzan101.mika.calls.voiceclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCloneTrainingEvidenceTest {
    @Test fun switchingSlotsDoesNotUnlockAnAlreadySentSampleForTheOriginalSlot() {
        val submissions = VoiceCloneSampleSubmissions()
        val original = "S_original"
        val other = "S_other"
        submissions.mark(original)
        assertFalse(other in submissions)
        submissions.mark(other)
        submissions.reject(other) // A definite rejection does not affect another slot's submission.
        assertFalse(other in submissions)
        assertTrue(original in submissions)
        assertFalse(VoiceCloneState(original, hasRecording = true, currentSampleSubmitted = original in submissions).canSubmit)
        submissions.discardSample()
        assertTrue(VoiceCloneState(original, hasRecording = true, currentSampleSubmitted = original in submissions).canSubmit)
    }

    @Test fun oldReadyAndActiveDoNotResolveARequestWithNoAcknowledgement() {
        val pending = VoiceCloneTrainingEvidence.UNCONFIRMED
        for (status in listOf(VoiceCloneStatus.READY, VoiceCloneStatus.ACTIVE, VoiceCloneStatus.FAILED,
            VoiceCloneStatus.NOT_FOUND, VoiceCloneStatus.UNKNOWN)) {
            assertEquals(pending, pending.after(status, fromSubmission = false))
        }
        assertNull(pending.after(VoiceCloneStatus.READY, fromSubmission = true))
        assertNull(pending.after(VoiceCloneStatus.FAILED, fromSubmission = true))
    }

    @Test fun observedTrainingCanBeResumedAfterScreenClosesAndResolveOnTerminalStatus() {
        val unconfirmed = VoiceCloneTrainingEvidence.UNCONFIRMED
        val observed = checkNotNull(unconfirmed.after(VoiceCloneStatus.TRAINING, fromSubmission = false))
        assertEquals(VoiceCloneTrainingEvidence.TRAINING_OBSERVED, observed)
        val reopened = checkNotNull(VoiceCloneTrainingEvidence.restore(observed.name, legacyPending = false))
        assertNull(reopened.after(VoiceCloneStatus.READY, fromSubmission = false))
        assertNull(reopened.after(VoiceCloneStatus.FAILED, fromSubmission = false))
        assertEquals(reopened, reopened.after(VoiceCloneStatus.NOT_FOUND, fromSubmission = false))
    }

    @Test fun legacyPendingMarkerDoesNotImplyTheNewTrainingWasSeen() {
        val oldMarker = checkNotNull(VoiceCloneTrainingEvidence.restore(null, legacyPending = true))
        assertEquals(VoiceCloneTrainingEvidence.UNCONFIRMED, oldMarker)
        assertEquals(oldMarker, oldMarker.after(VoiceCloneStatus.READY, fromSubmission = false))
        assertNull(VoiceCloneTrainingEvidence.restore(null, legacyPending = false))
    }
}
