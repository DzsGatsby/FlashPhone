package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
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
            compose.onNodeWithTag("call-task-goal").performScrollTo().performClick()
            compose.onNodeWithTag("call-task-goal").performTextReplacement(GOAL)
            compose.waitUntil(5_000) { workspaceImeVisible(scenario) }
            compose.onNodeWithTag("call-task-goal").assertIsDisplayed()
            captureWorkspaceScreenshot("native-task-keyboard.png")
            compose.onNodeWithTag("call-task-questions").performScrollTo().performTextReplacement(QUESTION)
            revealOptionalField("context")
            compose.onNodeWithTag("call-task-context").performScrollTo().performTextReplacement(CONTEXT)
            revealOptionalField("constraints")
            compose.onNodeWithTag("call-task-constraints").performScrollTo().performTextReplacement(CONSTRAINTS)
            hideWorkspaceKeyboard(scenario)
            for (route in listOf("history", "receive", "settings", "home")) {
                compose.onNodeWithTag("call-tab-$route").assertIsDisplayed().performClick()
                assertWorkspaceNavigationVisible(compose)
            }
            selectTask()
            assertDraft()
            scenario.recreate()
            compose.onNodeWithTag("call-tab-task").assertIsSelected()
            assertDraft()
            hideWorkspaceKeyboard(scenario)
            compose.onNodeWithTag("call-task-review").performScrollTo().performClick()
            compose.onNodeWithTag("call-task-review-dialog").assertIsDisplayed()
            compose.onNode(hasText(GOAL) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(NUMBER, substring = true) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(QUESTION, substring = true) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            captureWorkspaceScreenshot("native-task-review.png")
            compose.onNodeWithTag("call-task-review-cancel").performClick()
            assertDraft()
            hideWorkspaceKeyboard(scenario)
            compose.runOnIdle {
                assertEquals(settingsBefore, app.calls.settings.state.value)
                assertEquals(CallPhase.IDLE, app.calls.state.value.phase)
            }
        }
        // A new Activity has no saved instance state; the private draft must restore from local storage.
        ActivityScenario.launch<MainActivity>(workspaceIntent(app)).use { scenario ->
            selectTask()
            assertDraft()
            hideWorkspaceKeyboard(scenario)
            captureWorkspaceScreenshot("native-task-restored.png")
            compose.runOnIdle { assertEquals(CallPhase.IDLE, app.calls.state.value.phase) }
        }
    }

    private fun selectTask() {
        compose.onNodeWithTag("call-tab-task").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-tab-task").assertIsSelected()
    }

    private fun assertDraft() {
        compose.onNodeWithTag("call-number").performScrollTo().assertTextContains(NUMBER)
        compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
        compose.onNodeWithTag("call-task-questions").performScrollTo().assertTextContains(QUESTION)
        revealOptionalField("context")
        compose.onNodeWithTag("call-task-context").performScrollTo().assertTextContains(CONTEXT)
        revealOptionalField("constraints")
        compose.onNodeWithTag("call-task-constraints").performScrollTo().assertTextContains(CONSTRAINTS)
    }

    private fun revealOptionalField(name: String) {
        if (compose.onAllNodesWithTag("call-task-$name").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("call-task-$name-fold").performScrollTo().performClick()
        }
    }

    private companion object {
        const val NUMBER = "15555550123"
        const val GOAL = "确认今天几点关门，不预约、不付款。"
        const val CONTEXT = "仅询问今天营业时间。"
        const val CONSTRAINTS = "不预约、不付款，不透露个人信息。"
        const val QUESTION = "今天营业到几点？"
    }
}
