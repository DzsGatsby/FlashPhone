package com.wangzan101.mika.calls

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStandbyPolicyTest {
    @Test fun standbyPointsToAudioHelperUntilItConnects() {
        assertTrue(CallStandbyPolicy.needsHelperBeforePreparing(helperConnected = false, configuring = false))
        assertFalse(CallStandbyPolicy.needsHelperBeforePreparing(helperConnected = true, configuring = false))
        assertFalse(CallStandbyPolicy.needsHelperBeforePreparing(helperConnected = false, configuring = true))
    }

    @Test fun ordinaryOutgoingRetainsLeaseWhenActivityLosesForegroundAndIncomingIsDisabled() {
        val config = CallConfig(enabled = true, autoAnswer = false)
        assertFalse(CallStandbyPolicy.optedIn(config, userUnlocked = true))
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = false, interactive = false, keyguardShowing = true))
        assertTrue(CallStandbyPolicy.foregroundOutgoing(true, config.enabled, false, CallDirection.OUTGOING))
        assertTrue(CallStandbyPolicy.acceptsStart(false, true, true))
    }

    @Test fun endingWorkOrDisablingFeatureReleasesTemporaryLease() {
        assertTrue(CallStandbyPolicy.foregroundOutgoing(true, true, false, CallDirection.OUTGOING))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, true, false, null))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, false, false, CallDirection.OUTGOING))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(false, true, false, CallDirection.OUTGOING))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, true, false, CallDirection.INCOMING))
        assertTrue(CallStandbyPolicy.foregroundOutgoing(true, true, true, null))
        assertFalse(CallStandbyPolicy.foregroundOutgoing(true, false, true, null))
    }

    @Test fun systemRestartOrStaleStartCannotReplayTemporaryOutgoingWork() {
        // Even a stale in-memory work reference cannot authorize a system/null-Intent restart.
        assertFalse(CallStandbyPolicy.acceptsStart(false, false, true))
        assertFalse(CallStandbyPolicy.acceptsStart(false, true, false))
        assertFalse(CallStandbyPolicy.acceptsStart(false, false, false))
        assertTrue(CallStandbyPolicy.acceptsStart(true, false, false))
        assertTrue(CallStandbyPolicy.acceptsStart(true, true, false))
    }

    @Test fun visibleActivityBehindKeyguardIsNotAnInteractiveForeground() {
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = true, interactive = true, keyguardShowing = true))
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = true, interactive = false, keyguardShowing = false))
        assertTrue(CallStandbyPolicy.interactiveForeground(visible = true, interactive = true, keyguardShowing = false))
    }

    @Test fun lockedIncomingRequiresActualStandbyAndItsNotificationAfterFirstUnlock() {
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(true, false, false, true))
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(true, false, true, false))
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(false, false, true, true))
        assertTrue(CallStandbyPolicy.allowsAutomaticAnswer(true, false, true, true))
        assertTrue(CallStandbyPolicy.allowsAutomaticAnswer(true, true, false, false))
    }

    @Test fun stickyStandbyRequiresExplicitAutoAnswerAndVerifiedAudio() {
        val enabled = CallConfig(enabled = true, autoAnswer = true, audioVerified = true, credentialsConfigured = true)
        assertTrue(CallStandbyPolicy.optedIn(enabled, userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled, userUnlocked = false))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(autoAnswer = false), userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(audioVerified = false), userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(credentialsConfigured = false), userUnlocked = true))
    }

    @Test fun activeCallWakeLockIsBoundedByConfiguredDurationAndCleanupAllowance() {
        assertEquals(130_000L, CallStandbyPolicy.activeWakeBudgetMs(120))
        assertEquals(610_000L, CallStandbyPolicy.activeWakeBudgetMs(Int.MAX_VALUE))
    }

    @Test fun backgroundRecoveryHonorsAUserStopUntilTheOwnerOpensTheApp() {
        val stop = ApplicationExitInfo.REASON_USER_REQUESTED
        assertTrue(CallStandbyRecoveryJob.explicitStopSinceOpen(stop, exitedAt = 200, lastOpened = 100))
        assertTrue(CallStandbyRecoveryJob.explicitStopSinceOpen(stop, exitedAt = 200, lastOpened = 200))
        assertFalse(CallStandbyRecoveryJob.explicitStopSinceOpen(stop, exitedAt = 200, lastOpened = 300))
        assertFalse(CallStandbyRecoveryJob.explicitStopSinceOpen(ApplicationExitInfo.REASON_OTHER, exitedAt = 200, lastOpened = 100))
        assertFalse(CallStandbyRecoveryJob.explicitStopSinceOpen(ApplicationExitInfo.REASON_LOW_MEMORY, exitedAt = 200, lastOpened = 100))
    }

    @Test fun foregroundRestoreWaitsForTheLateHelperAndDoesNotRetryAfterFailure() {
        val resume = CallStandbyResumeGate()
        assertFalse(resume.take(visible = true, eligible = false))
        assertTrue(resume.take(visible = true, eligible = true))
        assertFalse(resume.take(visible = true, eligible = false))
        assertFalse(resume.take(visible = true, eligible = true))
        assertFalse(resume.take(visible = false, eligible = true))
        assertTrue(resume.take(visible = true, eligible = true))
    }
}
