package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
            // A fresh launch opens the actual four-tab dialer; capture it before any draft edits.
            compose.onNodeWithTag("call-page-task").assertIsDisplayed()
            compose.onNodeWithTag("call-tab-task").assertIsSelected()
            assertWorkspaceNavigationVisible(compose)
            captureWorkspaceScreenshot("native-dialer-home.png")
            compose.onNodeWithTag("call-task-text-brief").assertDoesNotExist()
            compose.onNodeWithTag("call-number").performScrollTo().performTextReplacement(NUMBER)
            assertNumber("after-number-set")
            hideWorkspaceKeyboard(scenario)
            assertNumber("after-number-keyboard-hide")
            openTextBrief()
            assertNumber("after-sheet-open")
            assertSheetNumber("after-sheet-open")
            compose.onNodeWithTag("call-task-text-brief").performTextReplacement(BRIEF)
            assertNumber("after-brief-set")
            assertSheetNumber("after-brief-set")
            compose.onNodeWithTag("call-task-text-brief").performClick()
            assertNumber("after-brief-click")
            compose.waitUntil(5_000) { workspaceImeVisible(scenario) }
            compose.onNodeWithTag("call-task-text-brief").assertIsDisplayed()
            captureWorkspaceScreenshot("native-task-keyboard.png")
            hideWorkspaceKeyboard(scenario)
            assertNumber("after-brief-keyboard-hide")
            assertSheetNumber("after-brief-keyboard-hide")
            captureWorkspaceScreenshot("native-task-text-brief-sheet.png")
            closeTextBrief()
            assertNumber("after-sheet-close")
            for (route in listOf("history", "receive", "settings")) {
                compose.onNodeWithTag("call-tab-$route").assertIsDisplayed().performClick()
                assertWorkspaceNavigationVisible(compose)
            }
            selectTask()
            assertNumber("after-tab-detour")
            assertDraft()
            // Recreate with the editor open: both the sheet and the complete brief must restore.
            scenario.recreate()
            compose.onNodeWithTag("call-tab-task").assertIsSelected()
            compose.onNodeWithTag("call-text-brief-sheet").assertIsDisplayed()
            assertBriefText()
            hideWorkspaceKeyboard(scenario)
            compose.onNodeWithTag("call-text-brief-sheet").assertIsDisplayed()
            captureWorkspaceScreenshot("native-task-restored-sheet.png")
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
            assertBriefText()
            hideWorkspaceKeyboard(scenario)
            closeTextBrief()
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
            closeTextBrief()
            compose.runOnIdle { assertEquals(CallPhase.IDLE, app.calls.state.value.phase) }
        }
    }

    private fun selectTask() {
        compose.onNodeWithTag("call-tab-task").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-tab-task").assertIsSelected()
    }

    /** Opens the real editor, then checks the old structured fields through its single text view. */
    private fun assertDraft() {
        compose.onNodeWithTag("call-number").performScrollTo().assertTextContains(NUMBER)
        openTextBrief()
        assertBriefText()
    }

    /** Stage assertions localize loss before any later navigation or persistence can obscure it. */
    private fun assertNumber(stage: String) {
        try {
            // Do not scroll a covered dialer while its sheet is open; inspect its bound value only.
            compose.onNodeWithTag("call-number").assertTextContains(NUMBER)
        } catch (failure: AssertionError) {
            captureWorkspaceScreenshot("native-task-number-loss-$stage.png")
            throw AssertionError("Dialer number changed at stage: $stage", failure)
        }
    }

    private fun assertSheetNumber(stage: String) {
        try {
            compose.onNode(hasText(NUMBER, substring = true) and hasAnyAncestor(hasTestTag("call-text-brief-sheet")))
                .assertExists()
        } catch (failure: AssertionError) {
            captureWorkspaceScreenshot("native-task-number-loss-$stage-sheet-summary.png")
            throw AssertionError("Sheet target no longer matches number at stage: $stage", failure)
        }
    }

    private fun assertBriefText() {
        for (text in listOf(GOAL, QUESTION, CONTEXT, CONSTRAINTS)) {
            compose.onNodeWithTag("call-task-text-brief").assertTextContains(text, substring = true)
        }
        // These were separate input widgets; the new journey must not depend on hidden aliases.
        compose.onNodeWithTag("call-task-goal").assertDoesNotExist()
        compose.onNodeWithTag("call-task-questions").assertDoesNotExist()
    }

    private fun openTextBrief() {
        compose.onNodeWithTag("call-start-voice").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("call-text-brief-sheet").assertIsDisplayed()
    }

    private fun closeTextBrief() {
        compose.onNodeWithText("返回拨号键盘").performScrollTo().performClick()
        compose.onNodeWithTag("call-text-brief-sheet").assertDoesNotExist()
    }

    private companion object {
        const val NUMBER = "15555550123"
        const val GOAL = "确认今天几点关门，不预约、不付款。"
        const val CONTEXT = "仅询问今天营业时间。"
        const val CONSTRAINTS = "不预约、不付款，不透露个人信息。"
        const val QUESTION = "今天营业到几点？"
        val BRIEF = "【目标】\n$GOAL\n\n【必问问题】\n$QUESTION\n\n【背景】\n$CONTEXT\n\n【边界】\n$CONSTRAINTS"
    }
}
