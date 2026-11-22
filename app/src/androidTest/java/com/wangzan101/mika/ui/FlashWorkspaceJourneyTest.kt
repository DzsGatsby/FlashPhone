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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import com.wangzan101.mika.ui.theme.FlashPhoneTheme
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
        selectStatus("open")
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
        assertStatus("open")
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement("配送")
        compose.onNodeWithTag("call-history-row-hours").assertDoesNotExist()
        open("delivery")
        backToList()
        compose.onNodeWithTag("call-history-search").assertTextContains("配送")
        assertStatus("open")
        compose.onNodeWithTag("call-history-row-delivery").performScrollTo().assertIsDisplayed()
        captureWorkspaceScreenshot("native-history-filter-return.png")
        assertEquals(3, records.value.size)
        assertEquals(emptyList<String>(), marked.toList())
        assertEquals(emptyList<String>(), deleted.toList())
    }

    @Test fun consumedHomeRecordLinkDoesNotOverwriteReviewStateAfterLeavingHistoryTab() {
        val route = mutableStateOf(CallWorkspaceTab.Task)
        val requestedRecord = mutableStateOf<String?>(null)
        val consumed = AtomicInteger()
        compose.setContent {
            FlashPhoneTheme(reduceMotion = true) {
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
        selectStatus("open")
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement("配送")
        open("delivery")
        assertSelectedRecord("15555550456")

        // Dismiss the modal detail before switching the workspace underneath it.
        backToList()
        // The real navigation removes the history composition; SaveableStateHolder must restore it.
        compose.onNodeWithTag("call-tab-task").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-history-detail").assertDoesNotExist()
        compose.onNodeWithTag("call-tab-history").assertIsDisplayed().performClick()
        assertStatus("open")
        compose.onNodeWithTag("call-history-search").assertTextContains("配送")
        open("delivery")
        assertSelectedRecord("15555550456")
        compose.runOnIdle { assertEquals("A consumed target must not open again on re-entry", 1, consumed.get()) }
        captureWorkspaceScreenshot("native-history-consumed-link-return.png")

        // A new explicit dashboard request for that same ID must take priority over the saved query.
        backToList()
        compose.onNodeWithTag("call-tab-task").performClick()
        compose.onNodeWithTag("fixture-home-record-hours").performClick()
        compose.waitUntil(3_000) { consumed.get() == 2 && requestedRecord.value == null }
        assertSelectedRecord(NUMBER)
        backToList()
        assertStatus("all")
        // Reveal the list in the page before reviewing the remaining rows.
        // Desktop master/detail panes may retain an independent list viewport.
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
        selectStatus("done")
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

    @Test fun failedRecordActionsStayVisibleInsideDetailAndKeepTheSelectedRecord() {
        showHistory(actionFailure = "测试操作失败，记录已保留")
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement("营业")
        compose.onNodeWithTag("call-history-search").assertContentDescriptionEquals(workspaceText(R.string.flash_history_search_hint))
        open("hours")
        compose.onNodeWithTag("call-history-mark").performScrollTo().performClick()
        compose.onNode(hasText("测试操作失败，记录已保留") and hasAnyAncestor(hasTestTag("call-history-sheet-feedback")))
            .assertIsDisplayed()
        compose.onNodeWithTag("call-history-detail").assertIsDisplayed()
        compose.onNodeWithTag("call-history-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-delete-confirm").performClick()
        compose.onNode(hasText("测试操作失败，记录已保留") and hasAnyAncestor(hasTestTag("call-history-sheet-feedback")))
            .assertIsDisplayed()
        assertEquals(3, records.value.size)
        backToList()
        compose.onNodeWithTag("call-history-search").assertTextContains("营业")
        compose.onNodeWithTag("call-history-row-hours").performScrollTo().assertIsDisplayed()
    }

    private fun showHistory(allDirections: Boolean = true, actionFailure: String? = null) {
        compose.setContent {
            FlashPhoneTheme(reduceMotion = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CallHistorySection(
                        history = records.value,
                        historyWriteFailed = false,
                        onMark = { id, handled ->
                            marked += "$id:$handled"
                            if (actionFailure == null) {
                                records.value = records.value.map { if (it.id == id) it.copy(handled = handled) else it }
                                CallActionResult(true, "已更新测试记录")
                            } else CallActionResult(false, actionFailure)
                        },
                        onDelete = { id ->
                            deleted += id
                            if (actionFailure == null) {
                                records.value = records.value.filterNot { it.id == id }
                                CallActionResult(true, "已删除测试记录")
                            } else CallActionResult(false, actionFailure)
                        },
                        onReuseTask = { number, task -> reused += number to task },
                        onOpenRecording = { id -> opened += id; null },
                    )
                }
            }
        }
        if (allDirections) compose.onNodeWithTag("call-history-direction-all").performScrollTo().performClick()
    }

    @Test fun historyStartsWithIncomingAndDirectionsRemainIndependentOfStatus() {
        showHistory(allDirections = false)
        compose.onNodeWithTag("call-history-direction-incoming").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("call-history-row-delivery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-row-hours").assertDoesNotExist()
        compose.onNodeWithTag("call-history-direction-outgoing").performScrollTo().performClick()
        selectStatus("open")
        open("hours")
        backToList()
        compose.onNodeWithTag("call-history-direction-outgoing").performScrollTo().assertIsSelected()
        assertStatus("open")
        compose.onNodeWithTag("call-history-row-completed").assertDoesNotExist()
        compose.onNodeWithTag("call-history-row-delivery").assertDoesNotExist()
        assertEquals(emptyList<String>(), opened.toList())
    }

    @Test fun clearingTheQueryPreservesFiltersAndEmptyResultsCanResetThemTogether() {
        showHistory(allDirections = false)
        val incoming = compose.onNodeWithTag("call-history-row-delivery")
        val completed = compose.onNodeWithTag("call-history-row-completed")
        val open = compose.onNodeWithTag("call-history-row-hours")
        val outgoing = compose.onNodeWithTag("call-history-direction-outgoing")
        val clear = compose.onNodeWithTag("call-history-clear-search")
        val reset = compose.onNodeWithTag("call-history-reset-filters")
        val search = compose.onNodeWithTag("call-history-search")
        val emptyQuery = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))

        outgoing.performScrollTo().performClick()
        selectStatus("open")
        search.performScrollTo().performTextReplacement("没有对应记录")
        reset.performScrollTo().assertIsDisplayed()
        open.assertDoesNotExist()
        clear.performScrollTo().assertIsDisplayed().performClick()
        search.assert(emptyQuery)
        outgoing.assertIsSelected()
        assertStatus("open")
        open.performScrollTo().assertIsDisplayed()
        incoming.assertDoesNotExist()
        completed.assertDoesNotExist()
        clear.assertDoesNotExist()
        reset.assertDoesNotExist()

        search.performScrollTo().performTextReplacement("没有对应记录")
        reset.performScrollTo().assertIsDisplayed().performClick()
        search.assert(emptyQuery)
        compose.onNodeWithTag("call-history-direction-all").performScrollTo().assertIsSelected()
        assertStatus("all")
        for (row in listOf(open, incoming, completed)) row.performScrollTo().assertIsDisplayed()
        reset.assertDoesNotExist()
        assertEquals(fixtures(), records.value)
        assertEquals(emptyList<String>(), opened.toList())
    }

    private fun selectStatus(status: String) {
        compose.onNodeWithTag("call-history-status").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-filter-$status").performClick()
    }

    private fun assertStatus(status: String) {
        val label = when (status) {
            "open" -> R.string.calls_history_open
            "done" -> R.string.calls_history_done
            "recorded" -> R.string.flash_audit_filter_recorded
            else -> R.string.flash_history_all_status
        }
        compose.onNodeWithTag("call-history-status").performScrollTo().assertTextContains(compose.activity.getString(label))
    }

    private fun open(id: String) {
        compose.onNodeWithTag("call-history-row-$id").performScrollTo().assertIsDisplayed().performClick()
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
