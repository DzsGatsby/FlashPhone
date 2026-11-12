package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallRecordingInfo
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real production history UI driven by disposable in-memory records and observable callbacks.
 * No CallRuntime/history store, credentials, model, dial action, microphone or recording file is used.
 * WorkspaceEmulatorOnlyRule runs before ChineseUi and the host Activity are launched.
 */
@RunWith(AndroidJUnit4::class)
class FlashWorkspaceJourneyTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)
    private val records = mutableStateOf(fixtures())
    private val marked = CopyOnWriteArrayList<String>()
    private val deleted = CopyOnWriteArrayList<String>()
    private val reused = CopyOnWriteArrayList<Pair<String, CallTaskBrief>>()
    private val opened = CopyOnWriteArrayList<String>()

    @Test fun filteredRecordsOpenInlineSwitchAndReturnWithSearchPreserved() {
        showHistory()
        compose.onNodeWithTag("call-history-filter-open").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-row-completed").assertDoesNotExist()
        open("hours")
        compose.onNodeWithTag("call-history-detail").assertIsDisplayed()
        compose.onNodeWithTag("call-recording-player").performScrollTo().assertIsDisplayed()
        assertEquals("Selecting detail must not load or autoplay audio", emptyList<String>(), opened.toList())
        captureWorkspaceScreenshot("native-history-detail.png")

        compose.onNodeWithTag("call-history-next").performScrollTo().performClick()
        compose.onNodeWithTag("call-recording-player").assertDoesNotExist()
        compose.onNodeWithTag("call-history-detail").assertIsDisplayed()
        compose.onNodeWithTag("call-history-previous").performScrollTo().performClick()
        compose.onNodeWithTag("call-recording-player").performScrollTo().assertIsDisplayed()
        backToList()
        compose.onNodeWithTag("call-history-filter-open").assertIsSelected()
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement("配送")
        compose.onNodeWithTag("call-history-row-hours").assertDoesNotExist()
        open("delivery")
        backToList()
        compose.onNodeWithTag("call-history-search").assertTextContains("配送")
        compose.onNodeWithTag("call-history-filter-open").assertIsSelected()
        compose.onNodeWithTag("call-history-row-delivery").performScrollTo().assertIsDisplayed()
        captureWorkspaceScreenshot("native-history-filter-return.png")
        assertEquals(3, records.value.size)
        assertEquals(emptyList<String>(), marked.toList())
        assertEquals(emptyList<String>(), deleted.toList())
    }

    @Test fun consumedHomeRecordLinkDoesNotOverwriteReviewStateAfterLeavingHistoryTab() {
        val route = mutableStateOf(CallWorkspaceTab.Home)
        val requestedRecord = mutableStateOf<String?>(null)
        val consumed = AtomicInteger()
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                val savedTabs = rememberSaveableStateHolder()
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        if (route.value == CallWorkspaceTab.History) {
                            savedTabs.SaveableStateProvider("history") {
                                CallHistorySection(
                                    history = records.value,
                                    historyWriteFailed = false,
                                    onMark = { _, _ -> error("This navigation journey must not modify a record") },
                                    onDelete = { error("This navigation journey must not delete a record") },
                                    initialRecordId = requestedRecord.value,
                                    onInitialRecordOpened = {
                                        consumed.incrementAndGet()
                                        requestedRecord.value = null
                                    },
                                    onOpenRecording = { id -> opened += id; null },
                                )
                            }
                        } else {
                            // Only this fixture's dashboard entry is synthetic; navigation and review are production UI.
                            Button(
                                onClick = { requestedRecord.value = "hours"; route.value = CallWorkspaceTab.History },
                                modifier = Modifier.testTag("fixture-home-record-hours"),
                            ) { Text("打开营业时间记录") }
                        }
                    }
                    FlashWorkspaceNavigation(
                        selected = route.value,
                        pendingCount = 2,
                        taskHasDraft = false,
                        needsSetup = false,
                        rail = false,
                        onSelect = { route.value = it },
                    )
                }
            }
        }

        compose.onNodeWithTag("fixture-home-record-hours").performClick()
        compose.waitUntil(3_000) { consumed.get() == 1 && requestedRecord.value == null }
        assertSelectedRecord(NUMBER)
        backToList()
        compose.onNodeWithTag("call-history-filter-open").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement("配送")
        open("delivery")
        assertSelectedRecord("15555550456")

        // The real navigation removes the history composition; SaveableStateHolder must restore it.
        compose.onNodeWithTag("call-tab-home").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-history-detail").assertDoesNotExist()
        compose.onNodeWithTag("call-tab-history").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-history-filter-open").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("call-history-search").assertTextContains("配送")
        assertSelectedRecord("15555550456")
        compose.runOnIdle { assertEquals("A consumed target must not open again on re-entry", 1, consumed.get()) }
        captureWorkspaceScreenshot("native-history-consumed-link-return.png")

        // A new explicit dashboard request for that same ID must take priority over the saved query.
        compose.onNodeWithTag("call-tab-home").performClick()
        compose.onNodeWithTag("fixture-home-record-hours").performClick()
        compose.waitUntil(3_000) { consumed.get() == 2 && requestedRecord.value == null }
        compose.onNodeWithTag("call-history-filter-all").performScrollTo().assertIsSelected()
        assertSelectedRecord(NUMBER)
        backToList()
        // The bounded record list has its own scroll state inside the workspace scroll.
        // Reveal that viewport before scrolling individual rows within it.
        compose.onNodeWithTag("call-history-list").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-row-delivery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-row-completed").performScrollTo().assertIsDisplayed()
        captureWorkspaceScreenshot("native-history-return-completed-visible.png")
        assertEquals("Selecting and restoring records must never load audio", emptyList<String>(), opened.toList())
        assertEquals(3, records.value.size)
    }

    private fun assertSelectedRecord(number: String) {
        compose.onNode(
            hasText(number) and hasAnyAncestor(hasTestTag("call-history-detail")),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test fun detailRetainsRecordingFailureRecoveryReuseMarkAndDeleteCallbacks() {
        showHistory()
        open("hours")
        compose.onNodeWithTag("call-recording-play-pause").performScrollTo().performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        compose.onNodeWithTag("call-recording-error").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("audio-hours"), opened.toList())
        captureWorkspaceScreenshot("native-history-recording-recovery.png")

        compose.onNodeWithTag("call-record-reuse").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(NUMBER to TASK), reused.toList())
            assertEquals("Reuse only hands a draft to its callback", 3, records.value.size)
        }
        compose.onNodeWithTag("call-history-mark").performScrollTo().performClick()
        compose.waitUntil(3_000) { marked.toList() == listOf("hours:true") }
        backToList()
        compose.onNodeWithTag("call-history-filter-done").performScrollTo().performClick()
        open("hours")
        compose.onNodeWithTag("call-history-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-delete-confirm").assertIsDisplayed()
        compose.onNodeWithText(workspaceText(R.string.calls_cancel)).performClick()
        assertEquals(emptyList<String>(), deleted.toList())
        compose.onNodeWithTag("call-history-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-delete-confirm").performClick()
        compose.waitUntil(3_000) { deleted.toList() == listOf("hours") }
        compose.onNodeWithTag("call-history-row-hours").assertDoesNotExist()
        assertEquals(listOf("delivery", "completed"), records.value.map { it.id })
    }

    private fun showHistory() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CallHistorySection(
                        history = records.value,
                        historyWriteFailed = false,
                        onMark = { id, handled ->
                            marked += "$id:$handled"
                            records.value = records.value.map { if (it.id == id) it.copy(handled = handled) else it }
                            CallActionResult(true, "已更新测试记录")
                        },
                        onDelete = { id ->
                            deleted += id
                            records.value = records.value.filterNot { it.id == id }
                            CallActionResult(true, "已删除测试记录")
                        },
                        onReuseTask = { number, task -> reused += number to task },
                        onOpenRecording = { id -> opened += id; null },
                    )
                }
            }
        }
    }

    private fun open(id: String) {
        compose.onNodeWithTag("call-history-row-$id").performScrollTo().performClick()
    }

    private fun backToList() {
        compose.onNodeWithTag("call-history-back").performScrollTo().performClick()
    }

    private companion object {
        const val NUMBER = "15555550123"
        val TASK = CallTaskBrief("确认营业时间", listOf("今天几点关门？"), constraints = "不预约、不付款")
        fun fixtures() = listOf(
            CallRecord("hours", NUMBER, CallDirection.OUTGOING, 1_700_000_000_000, 1_700_000_030_000,
                "营业时间已确认", listOf(CallTranscriptLine("assistant", "今天营业到晚上九点。")), task = TASK,
                recording = CallRecordingInfo("audio-hours", 30_000, false, 1_920_044)),
            CallRecord("delivery", "15555550456", CallDirection.INCOMING, 1_700_000_040_000, 1_700_000_060_000,
                "配送留言", listOf(CallTranscriptLine("user", "配送到了，请下楼取件。"))),
            CallRecord("completed", "15555550789", CallDirection.OUTGOING, 1_700_000_070_000, 1_700_000_090_000,
                "已处理", emptyList(), handled = true),
        )
    }
}
