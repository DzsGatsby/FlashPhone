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
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallSim
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                CallTaskScreen(
                    draft = draft,
                    config = CallConfig(defaultOutgoingSimId = "sim-1"),
                    state = CallRuntimeState(simOptions = listOf(CallSim("sim-1", "主号", true))),
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

    @Test fun tapStartsOnlyVoiceBriefingAndBusyStateBlocksAnotherStart() {
        show()
        compose.onNodeWithTag("call-task-text-brief").assertDoesNotExist()
        compose.onNodeWithTag("call-open-text-brief").assertDoesNotExist()
        compose.onNodeWithText("文字交代").assertDoesNotExist()
        compose.onNodeWithTag("call-task-brief-hint").assertTextContains("轻点语音交代 · 长按文字交代")
        compose.onNodeWithTag("call-start-voice").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, voiceStarts)
            assertEquals(0, normalConfirmations + lockedConfirmations)
            busy.value = true
        }
        compose.onNodeWithTag("call-start-voice").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("call-open-text-brief").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, voiceStarts) }
    }

    @Test fun longPressAndReleaseOpensSingleTextSheetWithoutStartingVoiceOrDialing() {
        draft.goal = "确认营业时间"
        draft.questions = "几点关门？"
        draft.context = "只有今天方便去"
        draft.constraints = "不要预约"
        val original = draft.snapshotFields()
        show()
        compose.onNodeWithTag("call-task-brief-hint").assertTextContains("轻点语音交代 · 长按编辑草稿")
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
        compose.onNodeWithTag("call-task-clear").performScrollTo().performClick()
        compose.onNodeWithTag("call-task-brief-hint").assertTextContains("轻点语音交代 · 长按文字交代")
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
}
