package com.wangzan101.mika.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.ui.theme.FlashPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Synthetic UI only: no telephony, CallRuntime, audio or provider requests. */
@RunWith(AndroidJUnit4::class)
class CallLiveReadingUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @Test fun newLinesPreserveManualReadingAndResumeFollowingWhenReaderReturnsToBottom() {
        val lines = mutableStateOf(longTranscript())
        compose.setContent {
            FlashPhoneTheme {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    LiveCallTranscript("reading", lines.value, expandRequest = 1, fillViewport = true)
                }
            }
        }
        compose.waitForIdle()
        assertAtBottom()
        scrollToTop()
        compose.runOnIdle { lines.value = lines.value + CallTranscriptLine("assistant", "新增的第一句。".repeat(20)) }
        compose.waitForIdle()
        assertEquals("An incoming line must not interrupt manual review", 0f, position(), 2f)
        transcript().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val oldMaximum = maximum()
        compose.runOnIdle { lines.value = lines.value + CallTranscriptLine("user", "新增的第二句。".repeat(20)) }
        compose.waitForIdle()
        assertTrue(maximum() > oldMaximum)
        assertAtBottom()
    }

    @Test fun anotherCallStartsWithItsOwnLatestTranscript() {
        val callId = mutableStateOf("first-call")
        val lines = mutableStateOf(longTranscript())
        compose.setContent { FlashPhoneTheme {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                LiveCallTranscript(callId.value, lines.value, expandRequest = 1, fillViewport = true)
            }
        } }
        compose.waitForIdle()
        scrollToTop()
        compose.runOnIdle { callId.value = "second-call"; lines.value = longTranscript("第二通") }
        compose.waitForIdle()
        assertTrue(maximum() > 0f)
        assertAtBottom()
    }

    @Test fun restorationAndViewportResizeKeepManualReadingPosition() {
        val restoration = StateRestorationTester(compose)
        val height = mutableStateOf(300.dp)
        restoration.setContent { FlashPhoneTheme {
            Box(Modifier.fillMaxWidth().height(height.value)) {
                LiveCallTranscript("restored-call", longTranscript(), expandRequest = 1, fillViewport = true)
            }
        } }
        compose.waitForIdle()
        scrollToTop()
        transcript().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 130f) }
        compose.waitForIdle()
        val before = position()
        assertTrue(before > 0f && before < maximum())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Restoration must retain the offset", before, position(), 2f)
        compose.runOnIdle { height.value = 240.dp }
        compose.waitForIdle()
        assertEquals("A smaller viewport must retain the offset", before, position(), 2f)
    }

    @Test fun collapsingAndReopeningTheCallRetainsExpandedOriginalAndOffset() {
        val expanded = mutableStateOf(true)
        compose.setContent { FlashPhoneTheme {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                CurrentCallCard(
                    state = activeState(longTranscript()), feedback = null, canAnswer = false, canControl = true,
                    expanded = expanded.value,
                    modifier = if (expanded.value) Modifier.weight(1f) else Modifier,
                    onAnswer = {}, onHangup = {}, onTakeover = {}, onCancelAuto = {},
                    onTranscript = { expanded.value = true }, onCollapse = { expanded.value = false },
                )
                Box(Modifier.height(40.dp).testTag("reading-workspace")) { Text("其他页面") }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("call-current-transcript").performClick()
        compose.waitForIdle()
        scrollToTop()
        compose.onNodeWithTag("call-current-collapse").performClick()
        compose.onNodeWithTag("call-current-latest", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("call-current-transcript").performClick()
        compose.waitForIdle()
        assertEquals("Returning to live detail must not jump to the latest line", 0f, position(), 2f)
        compose.onNodeWithText(longTranscript().first().text).assertIsDisplayed()
        compose.onNodeWithTag("call-takeover").assertIsDisplayed()
        compose.onNodeWithTag("call-hangup").assertIsDisplayed()
    }

    @Test fun collapsedCallShowsLatestSpeakerAndTextAndFallsBackToActualStatus() {
        val lines = mutableStateOf(listOf(CallTranscriptLine("user", "对方的最新一句。")))
        compose.setContent { FlashPhoneTheme {
            Column(Modifier.fillMaxSize()) {
                CurrentCallCard(activeState(lines.value), feedback = null, canAnswer = false, canControl = true,
                    expanded = false, onAnswer = {}, onHangup = {}, onTakeover = {}, onCancelAuto = {}, onTranscript = {})
            }
        } }
        compose.onNodeWithText("对方的最新一句。", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(workspaceText(R.string.calls_caller), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { lines.value = lines.value + CallTranscriptLine("assistant", "助手的新回应。") }
        compose.onNodeWithText("助手的新回应。", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(workspaceText(R.string.app_name), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("对方的最新一句。", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { lines.value = emptyList() }
        compose.onNodeWithTag("call-current-latest", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(workspaceText(R.string.calls_ai_active), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun longCollapsedLineAtLargeFontKeepsControlsAndOtherPageVisible() {
        val events = mutableListOf<String>()
        compose.setContent { FlashPhoneTheme {
            val baseDensity = LocalDensity.current
            val compact = Configuration(LocalConfiguration.current).apply { screenHeightDp = 360; fontScale = 2f }
            CompositionLocalProvider(LocalConfiguration provides compact, LocalDensity provides Density(baseDensity.density, 2f)) {
                Column(Modifier.widthIn(max = 340.dp).height(340.dp).padding(8.dp).testTag("reading-compact-window")) {
                    CurrentCallCard(activeState(listOf(CallTranscriptLine("user", "长句与大字号".repeat(100)))),
                        feedback = null, canAnswer = false, canControl = true, expanded = false,
                        onAnswer = {}, onHangup = { events += "hangup" }, onTakeover = { events += "takeover" },
                        onCancelAuto = {}, onTranscript = {})
                    Box(Modifier.weight(1f).testTag("reading-workspace")) { Text("其他页面") }
                }
            }
        } }
        compose.onNodeWithTag("call-current-latest", useUnmergedTree = true).assertIsDisplayed()
        val root = compose.onNodeWithTag("reading-compact-window").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("call-takeover", "call-hangup", "reading-workspace")) {
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must fit the screen", bounds.height > 0 && bounds.bottom <= root.bottom + 1f)
        }
        compose.onNodeWithTag("call-takeover").performClick()
        compose.onNodeWithTag("call-hangup").performClick()
        compose.runOnIdle { assertEquals(listOf("takeover", "hangup"), events) }
    }

    private fun activeState(lines: List<CallTranscriptLine>) = CallRuntimeState(
        callId = "synthetic-reading-only", phase = CallPhase.ACTIVE, number = "5550100", aiActive = true,
        canHangUp = true, canTakeOver = true, liveTranscript = lines,
    )
    private fun longTranscript(prefix: String = "合成") = List(20) {
        CallTranscriptLine(if (it % 2 == 0) "user" else "assistant", "$prefix 回读第 $it 句。".repeat(12))
    }
    private fun transcript() = compose.onNodeWithTag("call-live-transcript")
    private fun position() = transcript().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun maximum() = transcript().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    private fun assertAtBottom() = assertEquals("New content must follow while already at the end", maximum(), position(), 2f)
    private fun scrollToTop() {
        transcript().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        compose.waitForIdle()
        assertEquals(0f, position(), 2f)
    }
}
