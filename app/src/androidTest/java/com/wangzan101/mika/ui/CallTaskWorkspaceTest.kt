package com.wangzan101.mika.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** UI-only: never presses call-dial, changes a saved setting, or calls a model. */
class CallTaskWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun taskDraftSurvivesTabsAndRecreationThenReviewsWithoutDialing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit UI review on development package only",
            InstrumentationRegistry.getArguments().getString("callUiReview") == "true" &&
                instrumentation.targetContext.packageName == "com.wangzan101.mika.dev")
        val app = instrumentation.targetContext.applicationContext as MikaApp
        assumeTrue(app.calls.state.value.phase == CallPhase.IDLE)
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithTag("call-tab-task").assertIsDisplayed().performClick()
            compose.onNodeWithTag("call-number").performScrollTo().performTextReplacement("15555550123")
            compose.onNodeWithTag("call-task-goal").performScrollTo().performTextReplacement(GOAL)
            compose.onNodeWithTag("call-task-questions").performScrollTo().performTextReplacement(QUESTION)
            compose.onNodeWithTag("call-tab-history").assertIsDisplayed().performClick()
            compose.onNodeWithTag("call-task-goal").assertDoesNotExist()
            compose.onNodeWithTag("call-tab-settings").assertIsDisplayed().performClick()
            compose.onNodeWithTag("call-tab-task").assertIsDisplayed().performClick()
            compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
            compose.onNodeWithTag("call-task-questions").performScrollTo().assertTextContains(QUESTION)
            scenario.recreate()
            compose.onNodeWithTag("call-tab-task").assertIsDisplayed()
            compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
            compose.onNodeWithTag("call-task-questions").performScrollTo().assertTextContains(QUESTION)
            compose.onNodeWithTag("call-task-review").performScrollTo().performClick()
            compose.onNodeWithTag("call-task-review-dialog").assertIsDisplayed()
            compose.onNode(hasText(GOAL) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-task-review-cancel").performClick()
            compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
            compose.onNodeWithTag("call-tab-receive").assertIsDisplayed().performClick()
            compose.onNodeWithTag("call-task-goal").assertDoesNotExist()
            compose.runOnIdle { assertEquals(CallPhase.IDLE, app.calls.state.value.phase) }
        }
    }

    private companion object {
        const val GOAL = "Confirm the closing time; do not make a reservation."
        const val QUESTION = "What time do you close today?"
    }
}
