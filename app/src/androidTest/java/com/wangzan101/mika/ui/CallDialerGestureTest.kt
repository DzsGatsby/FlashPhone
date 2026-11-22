package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallSim
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Pure UI callbacks on a disposable emulator. No runtime, provider, microphone or carrier call. */
@RunWith(AndroidJUnit4::class)
class CallDialerGestureTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)
    private val draft = CallTaskDraft().apply { number = "15555550123" }
    private val busy = mutableStateOf(false)
    private var voiceStarts = 0
    private var normalConfirmations = 0
    private var lockedConfirmations = 0

    private fun show() = compose.setContent {
        MikaTheme(dark = false, reduceMotion = true) {
            KeyboardDismissArea(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CallTaskScreen(
                        draft = draft,
                        config = CallConfig(defaultOutgoingSimId = "sim-1"),
                        state = CallRuntimeState(simOptions = listOf(
                            CallSim("sim-1", "主号", true), CallSim("sim-2", "备用号", false),
                        )),
                        ready = true, editable = true, canDial = true, feedback = null, onSetup = {},
                        onConfirm = { normalConfirmations++ },
                        onConfirmLocked = { lockedConfirmations++ },
                        onStartVoiceBriefing = { number, simId, simLabel ->
                            assertEquals("15555550123", number)
                            assertEquals("sim-1", simId)
                            assertEquals("主号", simLabel)
                            voiceStarts++
                        },
                        voiceBusy = busy.value,
                    )
                }
            }
        }
    }

    @Test fun tapStartsOnlyVoiceBriefingAndBusyStateBlocksAnotherStart() {
        show()
        compose.onNodeWithTag("call-task-text-brief").assertDoesNotExist()
        textEntry().assertTextContains(workspaceText(R.string.flash_text_brief))
        compose.onNodeWithTag("call-task-brief-hint").performScrollTo()
            .assertTextContains(workspaceText(R.string.flash_voice_brief_hint))
        compose.onNodeWithTag("call-start-voice").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, voiceStarts)
            assertEquals(0, normalConfirmations + lockedConfirmations)
            busy.value = true
        }
        compose.onNodeWithTag("call-start-voice").performScrollTo().assertIsNotEnabled().performClick()
        textEntry().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, voiceStarts) }
    }

    @Test fun longPressAndReleaseOpensSingleTextSheetWithoutStartingVoiceOrDialing() {
        draft.goal = "确认营业时间"
        draft.questions = "几点关门？"
        draft.context = "只有今天方便去"
        draft.constraints = "不要预约"
        val original = draft.snapshotFields()
        show()
        textEntry().assertTextContains(workspaceText(R.string.flash_edit_draft))
        compose.onNodeWithTag("call-start-voice").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已有交代草稿"))
        compose.onNodeWithTag("call-start-voice").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("call-text-brief-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("call-task-text-brief").assertCountEquals(1)
        compose.onNodeWithTag("call-task-text-brief").assertTextContains("几点关门？", substring = true)
        compose.onNodeWithTag("call-task-text-brief").assertTextContains("只有今天方便去", substring = true)
        compose.onNodeWithTag("call-task-text-brief").assertTextContains("不要预约", substring = true)
        compose.onNodeWithTag("call-task-goal").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(original, draft.snapshotFields())
            assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations)
        }
        compose.onNodeWithText("返回拨号键盘").performScrollTo().performClick()
        textEntry().assertTextContains(workspaceText(R.string.flash_edit_draft)).performClick()
        compose.onNodeWithTag("call-task-text-brief").assertTextContains("几点关门？", substring = true)
        compose.runOnIdle {
            assertEquals(original, draft.snapshotFields())
            assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations)
        }
    }

    @Test fun visibleTextEntryCreatesAnEditableDraftWithoutCalling() {
        show()
        textEntry().assertTextContains(workspaceText(R.string.flash_text_brief)).performClick()
        compose.onNodeWithTag("call-task-text-brief").performTextReplacement("确认营业时间，不要预约。")
        compose.onNodeWithText("返回拨号键盘").performScrollTo().performClick()
        textEntry().assertTextContains(workspaceText(R.string.flash_edit_draft)).performClick()
        compose.onNodeWithTag("call-task-text-brief").assertTextContains("确认营业时间，不要预约。")
        compose.runOnIdle { assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations) }
    }

    @Test fun clearingCanBeUndoneUntilNewInputReplacesTheOldDraft() {
        draft.goal = "确认营业时间"
        draft.questions = "几点关门？"
        draft.context = "只有今天方便去"
        draft.constraints = "不要预约"
        draft.simOverride = "sim-1"
        val original = draft.snapshotFields()
        show()

        val clear = compose.onNodeWithTag("call-task-clear")
        val undo = compose.onNodeWithTag("call-task-undo-clear")
        clear.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(List(6) { "" }, draft.snapshotFields()) }
        undo.performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(original, draft.snapshotFields()) }
        undo.assertDoesNotExist()

        clear.performScrollTo().performClick()
        undo.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-key-8").performScrollTo().performClick()
        undo.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("8", "", "", "", "", ""), draft.snapshotFields())
            assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations)
        }
    }

    @Test fun selectingOnlyASimMakesTheOldClearUndoUnavailable() {
        draft.goal = "旧草稿"
        draft.simOverride = "sim-1"
        show()
        compose.onNodeWithTag("call-task-clear").performScrollTo().performClick()
        val undo = compose.onNodeWithTag("call-task-undo-clear")
        undo.assertIsDisplayed()
        compose.onNodeWithTag("call-task-sim").performScrollTo().performClick()
        compose.onNodeWithText("备用号").assertIsDisplayed().performClick()
        undo.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("", "", "", "", "", "sim-2"), draft.snapshotFields())
            assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations)
        }
    }

    @Test fun enteringThenRemovingOnlyWhitespaceDoesNotBringBackTheOldDraft() {
        draft.goal = "旧草稿"
        show()
        compose.onNodeWithTag("call-task-clear").performScrollTo().performClick()
        val undo = compose.onNodeWithTag("call-task-undo-clear")
        undo.assertIsDisplayed()
        val number = compose.onNodeWithTag("call-number")
        number.performScrollTo().performTextReplacement(" ")
        undo.assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(" ", "", "", "", "", ""), draft.snapshotFields()) }
        number.performTextReplacement("")
        undo.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(List(6) { "" }, draft.snapshotFields())
            assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations)
        }
    }

    @Test fun accessibleTextEntryRetainsReviewAndLockedConfirmation() {
        show()
        compose.onNodeWithTag("call-start-voice").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("call-task-text-brief").performTextReplacement("确认营业时间，不要预约。")
        compose.onNodeWithTag("call-task-review").performScrollTo().performClick()
        compose.onNodeWithTag("call-task-review-dialog").assertIsDisplayed()
        compose.onNodeWithTag("call-mode-lock").performScrollTo().performClick()
        compose.onNodeWithTag("call-dial-locked").performClick()
        compose.runOnIdle {
            assertEquals(0, voiceStarts)
            assertEquals(0, normalConfirmations)
            assertEquals(1, lockedConfirmations)
            assertEquals("确认营业时间，不要预约。", draft.goal)
        }
    }

    @Test fun keypadKeysAndDeleteEditAtTheSystemTextSelection() {
        show()
        val input = compose.onNodeWithTag("call-number")
        input.performTextReplacement("123456")
        fun select(start: Int, end: Int) {
            input.performScrollTo().performSemanticsAction(SemanticsActions.SetSelection) {
                assertTrue(it(start, end, false))
            }
        }
        fun tap(tag: String) {
            compose.onNodeWithTag(tag).performScrollTo().performTouchInput { click() }
        }

        select(2, 2)
        tap("call-key-9")
        input.assertTextContains("1293456")
        tap("call-number-delete")
        input.assertTextContains("123456")

        select(4, 1)
        tap("call-key-8")
        input.assertTextContains("1856")
        select(2, 4)
        tap("call-number-delete")
        input.assertTextContains("18")

        select(0, 0)
        compose.onNodeWithTag("call-number-delete").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("18", draft.snapshotFields()[0])
            assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations)
        }
    }

    @Test fun keypadPlusLongPressAndDeleteKeepNumberEditableWithoutAnyStart() {
        draft.number = ""
        show()
        compose.onNodeWithTag("call-key-0").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("call-key-1").performClick()
        compose.onNodeWithTag("call-key-2").performClick()
        compose.onNodeWithTag("call-number-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-number").performScrollTo().assertTextContains("+1")
        compose.runOnIdle { assertEquals(0, voiceStarts + normalConfirmations + lockedConfirmations) }
    }

    private fun textEntry() = compose.onNodeWithTag("call-task-text-entry").performScrollTo().assertIsDisplayed()
}
