package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallAutoAnswerStage
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.theme.FlashPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Long synthetic text must never push the live takeover/hangup controls out of reach. */
@RunWith(AndroidJUnit4::class)
class CallCurrentControlsUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @Test fun expandedLongTranscriptKeepsTakeoverHangupAndWorkspaceReachable() {
        val events = mutableListOf<String>()
        val state = CallRuntimeState(
            phase = CallPhase.ACTIVE, direction = CallDirection.INCOMING,
            callId = "synthetic-ui-only", number = "5550100", aiActive = true,
            canHangUp = true, canTakeOver = true,
            liveTranscript = List(32) { index ->
                CallTranscriptLine(
                    if (index % 2 == 0) "user" else "assistant",
                    "合成文本，仅用于界面安全验收。".repeat(20).take(240),
                )
            },
        )
        compose.setContent {
            FlashPhoneTheme(reduceMotion = true) {
                PageBackground {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("合成通话界面验收（未接通真实电话）")
                        CurrentCallCard(
                            state = state, feedback = null, canAnswer = false, canControl = true,
                            modifier = Modifier.weight(1f), expanded = true,
                            onAnswer = { events += "answer" }, onHangup = { events += "hangup" },
                            onTakeover = { events += "takeover" }, onCancelAuto = { events += "cancel" },
                            onTranscript = { events += "transcript" },
                        )
                        Box(Modifier.height(48.dp).testTag("synthetic-workspace")) { Text("导航仍可见") }
                    }
                }
            }
        }
        if (compose.onAllNodesWithText(workspaceText(R.string.calls_expand)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(workspaceText(R.string.calls_expand)).assertIsDisplayed().performClick()
            compose.onNodeWithText(workspaceText(R.string.calls_collapse)).assertIsDisplayed()
        } else {
            // Compact landscapes and large text use the same always-visible transcript shortcut.
            compose.onNodeWithTag("call-current-transcript").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(listOf("transcript"), events); events.clear() }
        }
        assertControlsAndWorkspaceVisible()
        compose.onNodeWithTag("call-live-transcript").performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("call-live-transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
        }
        assertControlsAndWorkspaceVisible()
        captureWorkspaceScreenshot("long-live-transcript-controls.png")
        compose.onNodeWithTag("call-takeover").performClick()
        compose.onNodeWithTag("call-hangup").performClick()
        compose.runOnIdle { assertEquals(listOf("takeover", "hangup"), events) }
    }

    @Test fun normalLiveDetailShowsBoundTaskAndReturnsWithoutDialing() {
        val events = mutableListOf<String>()
        val brief = CallTaskBrief(
            goal = "确认今天的营业时间",
            questions = listOf("今天几点关门？", "到店需要提前预约吗？"),
            context = "背景：我准备下班后到店。",
            constraints = "限制：只咨询，不代为预约或支付订金。",
        ).validatedSnapshot()
        val state = CallRuntimeState(
            phase = CallPhase.ACTIVE, direction = CallDirection.OUTGOING,
            callId = "synthetic-normal-detail", number = "5550103", aiActive = true,
            canHangUp = true, canTakeOver = true, activeTask = brief,
            liveTranscript = listOf(
                CallTranscriptLine("assistant", "想问一下今天几点关门，到店需要预约吗？"),
                CallTranscriptLine("user", "晚上九点前都可以，直接过来就好，不用预约。"),
                CallTranscriptLine("assistant", "好的，已经了解了，谢谢。"),
            ),
        )
        compose.setContent {
            FlashPhoneTheme(reduceMotion = true) {
                PageBackground {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("合成示例 · 未拨打真实电话")
                        CurrentCallCard(
                            state = state, task = state.activeTask,
                            feedback = null, canAnswer = false, canControl = true,
                            modifier = Modifier.weight(1f), expanded = true,
                            onAnswer = { events += "answer" }, onHangup = { events += "hangup" },
                            onTakeover = { events += "takeover" }, onCancelAuto = { events += "cancel" },
                            onTranscript = { events += "transcript" }, onCollapse = { events += "collapse" },
                        )
                        Box(Modifier.height(48.dp).testTag("synthetic-workspace")) {
                            Text("合成导航 · 外呼　记录　规则　设置")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertControlsAndWorkspaceVisible()
        compose.onNodeWithTag("call-current-task").assertIsDisplayed().performClick()
        // Read every field in the bound task, including lower content at large font sizes.
        compose.awaitOne(brief.goal, inDialog = true).performScrollTo().assertIsDisplayed()
        brief.questions.forEach { question ->
            compose.awaitOne("• $question", inDialog = true).performScrollTo().assertIsDisplayed()
        }
        compose.awaitOne(brief.context, inDialog = true).performScrollTo().assertIsDisplayed()
        compose.awaitOne(brief.constraints, inDialog = true).performScrollTo().assertIsDisplayed()
        compose.clickInDialog(workspaceText(R.string.calls_collapse))
        assertControlsAndWorkspaceVisible()
        compose.onNodeWithTag("call-current-task").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Task review must not control or dial a call", events.isEmpty()) }
        compose.waitForIdle()
        captureWorkspaceScreenshot("native-live-detail-normal.png")
        compose.onNodeWithTag("call-current-collapse").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("collapse"), events) }
    }

    @Test fun collapsedCallKeepsOtherPageVisibleAndOpensLiveDetail() {
        val events = mutableListOf<String>()
        compose.setContent {
            FlashPhoneTheme(reduceMotion = true) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    CurrentCallCard(
                        state = CallRuntimeState(callId = "mini-only", phase = CallPhase.ACTIVE,
                            number = "5550101", aiActive = true, canHangUp = true, canTakeOver = true),
                        feedback = null, canAnswer = false, canControl = true, expanded = false,
                        onAnswer = { events += "answer" }, onHangup = { events += "hangup" },
                        onTakeover = { events += "takeover" }, onCancelAuto = { events += "cancel" },
                        onTranscript = { events += "transcript" },
                    )
                    Box(Modifier.weight(1f).testTag("synthetic-workspace")) { Text("其他页面") }
                }
            }
        }
        assertControlsAndWorkspaceVisible()
        compose.onNodeWithTag("call-current-transcript").performClick()
        compose.onNodeWithTag("call-takeover").performClick()
        compose.onNodeWithTag("call-hangup").performClick()
        compose.runOnIdle { assertEquals(listOf("transcript", "takeover", "hangup"), events) }
    }

    @Test fun countdownAndManualPreparationKeepTheirDistinctCancelActions() {
        val events = mutableListOf<String>()
        val state = mutableStateOf(CallRuntimeState(callId = "ringing-only", phase = CallPhase.RINGING,
            number = "5550102", canAnswer = true, canHangUp = true, canTakeOver = true,
            autoAnswerStage = CallAutoAnswerStage.COUNTDOWN, autoAnswerRemainingSeconds = 3))
        compose.setContent {
            FlashPhoneTheme(reduceMotion = true) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    CurrentCallCard(state = state.value, feedback = null, canAnswer = true, canControl = true,
                        expanded = false, onAnswer = { events += "answer" }, onHangup = { events += "hangup" },
                        onTakeover = { events += "cancel-manual" }, onCancelAuto = { events += "cancel-auto" },
                        onTranscript = {})
                    Box(Modifier.weight(1f).testTag("synthetic-workspace")) { Text("其他页面") }
                }
            }
        }
        compose.onNodeWithTag("call-answer").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-cancel-auto").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-hangup").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(autoAnswerStage = CallAutoAnswerStage.OFF) }
        compose.onNodeWithTag("call-cancel-ai-answer").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("answer", "cancel-auto", "cancel-manual"), events) }
    }

    private fun assertControlsAndWorkspaceVisible() {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("call-takeover", "call-hangup", "synthetic-workspace")) {
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must remain inside the visible screen",
                bounds.width > 0 && bounds.height > 0 && bounds.top >= screen.top - 1f && bounds.bottom <= screen.bottom + 1f)
        }
    }
}
