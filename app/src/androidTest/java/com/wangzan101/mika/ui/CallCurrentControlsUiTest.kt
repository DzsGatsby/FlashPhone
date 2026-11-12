package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.theme.MikaTheme
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
            MikaTheme(dark = false, reduceMotion = true) {
                PageBackground {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("合成通话界面验收（未接通真实电话）")
                        CurrentCallCard(
                            state = state, feedback = null, canAnswer = false, canControl = true,
                            onAnswer = { events += "answer" }, onHangup = { events += "hangup" },
                            onTakeover = { events += "takeover" }, onCancelAuto = { events += "cancel" },
                            onTranscript = { events += "transcript" },
                        )
                        Box(Modifier.weight(1f).testTag("synthetic-workspace")) { Text("工作区仍可见") }
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

    private fun assertControlsAndWorkspaceVisible() {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("call-takeover", "call-hangup", "synthetic-workspace")) {
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must remain inside the visible screen",
                bounds.width > 0 && bounds.height > 0 && bounds.top >= screen.top - 1f && bounds.bottom <= screen.bottom + 1f)
        }
    }
}
