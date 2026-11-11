package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/** An unsent task must survive a setup detour, recreation and cancelled review. No layout/page-count contract. */
class CallTaskWorkspaceTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @Test fun taskDraftSurvivesSetupDetourAndRecreationThenReviewCancelWithoutCalling() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue("This journey requires an idle, unconfigured disposable emulator",
            app.calls.state.value.phase == CallPhase.IDLE && !app.calls.settings.state.value.credentialsConfigured)
        val settingsBefore = app.calls.settings.state.value

        ActivityScenario.launch<MainActivity>(workspaceIntent(app)).use { scenario ->
            selectTask()
            compose.onNodeWithTag("call-number").performScrollTo().performTextReplacement(NUMBER)
            compose.onNodeWithTag("call-task-goal").performScrollTo().performTextReplacement(GOAL)
            compose.onNodeWithTag("call-task-questions").performScrollTo().performTextReplacement(QUESTION)
            hideWorkspaceKeyboard(scenario)
            compose.onNodeWithTag("call-open-system").assertIsDisplayed().performClick()
            // Return to the task from the actual readiness route, without requiring any other workspace.
            compose.onNodeWithTag("call-open-system").assertIsDisplayed().performClick()
            assertDraft()
            scenario.recreate()
            assertDraft()
            hideWorkspaceKeyboard(scenario)
            compose.onNodeWithTag("call-task-review").performScrollTo().performClick()
            compose.onNodeWithTag("call-task-review-dialog").assertIsDisplayed()
            compose.onNode(hasText(GOAL) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(NUMBER, substring = true) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            captureWorkspaceScreenshot("task-review.png")
            compose.onNodeWithTag("call-task-review-cancel").performClick()
            assertDraft()
            hideWorkspaceKeyboard(scenario)
            compose.runOnIdle {
                assertEquals(settingsBefore, app.calls.settings.state.value)
                assertEquals(CallPhase.IDLE, app.calls.state.value.phase)
            }
        }
    }

    private fun selectTask() {
        compose.onNode(
            hasText(workspaceText(R.string.flash_hub_dial)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).assertIsDisplayed().performClick()
    }

    private fun assertDraft() {
        compose.onNodeWithTag("call-number").performScrollTo().assertTextContains(NUMBER)
        compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
        compose.onNodeWithTag("call-task-questions").performScrollTo().assertTextContains(QUESTION)
    }

    private companion object {
        const val NUMBER = "15555550123"
        const val GOAL = "确认今天几点关门，不预约、不付款。"
        const val QUESTION = "今天营业到几点？"
    }
}
