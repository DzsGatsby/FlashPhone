package com.wangzan101.mika.ui

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production summary UI with disposable in-memory records and callback counters only.
 * No runtime/store, model, credentials, phone actions, microphone or recording file is used.
 * The opt-in dev-emulator rule runs before locale changes and Activity launch.
 */
@RunWith(AndroidJUnit4::class)
class CallSummaryUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)
    private val records = mutableStateOf(listOf(record()))
    private val statuses = mutableStateOf<Map<String, CallSummaryStatus>>(emptyMap())
    private val requested = CopyOnWriteArrayList<String>()
    private val canceled = CopyOnWriteArrayList<String>()
    private val configured = AtomicInteger()

    @org.junit.Before fun foregroundWindowIsActuallyFocused() {
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun openingDetailDoesNotRequestModelAndExplicitGenerationCanCancelAndRetry() {
        showHistory()
        open()
        compose.onNodeWithTag("call-summary-card").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), requested.toList())
        compose.onNodeWithTag("call-summary-generate").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-status").assertTextContains("正在生成总结", substring = true)
        compose.onNodeWithTag("call-summary-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-status").assertTextContains("已取消生成", substring = true)
        compose.onNodeWithTag("call-summary-generate").performScrollTo().performClick()
        compose.runOnIdle { statuses.value = mapOf(ID to CallSummaryStatus.FAILED) }
        compose.onNodeWithTag("call-summary-status").assertTextContains("总结生成失败", substring = true)
        assertEquals(listOf(ID, ID), requested.toList())
        assertEquals(listOf(ID), canceled.toList())
        captureWorkspaceScreenshot("native-summary-failed.png")
    }

    @Test fun savedSummaryHasVerifiableSourcesAndSurvivesRegenerationFailure() {
        records.value = listOf(record().copy(summary = summary()))
        showHistory()
        open()
        compose.onNodeWithTag("call-summary-content").performScrollTo()
        compose.onNodeWithTag("call-summary-highlights-0-sources").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText(ORIGINAL) and hasAnyAncestor(hasTestTag("call-summary-highlights-0-evidence")))
            .performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), requested.toList())
        captureWorkspaceScreenshot("native-summary-source-review.png")
        compose.onNodeWithTag("call-summary-generate").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-content").performScrollTo()
        compose.onNodeWithText("• 九点闭店").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { statuses.value = mapOf(ID to CallSummaryStatus.SAVE_FAILED) }
        compose.onNodeWithTag("call-summary-status").performScrollTo().assertTextContains("新总结保存失败", substring = true)
        compose.onNodeWithTag("call-summary-content").performScrollTo()
        compose.onNodeWithText("• 九点闭店").performScrollTo().assertIsDisplayed()
        // Existing original transcript and record actions remain accessible after summary review.
        compose.onNodeWithTag("call-record-transcript-toggle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-mark").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-delete").performScrollTo().assertIsDisplayed()
    }

    @Test fun summarySearchAndSharedCopyExportPayloadIncludeSummarySourcesAndOriginal() {
        records.value = listOf(record().copy(summary = summary()))
        showHistory()
        // Search by phrasing present only in the summary.
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement("九点闭店")
        open()
        compose.onNodeWithTag("call-history-copy").performScrollTo().performClick()
        compose.runOnIdle {
            val payload = historyExport(compose.activity, records.value.single())
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(payload, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            listOf("通话 AI 总结", "要点", "已确认", "待办", "未知", "九点闭店", "转写第 1 条", ORIGINAL).forEach {
                assertTrue("Shared export payload must include $it", payload.contains(it))
            }
        }
        compose.onNodeWithTag("call-history-back").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-search").assertTextContains("九点闭店")
        compose.onNodeWithTag("call-history-row-$ID").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), requested.toList())
    }

    @Test fun missingModelHasConfigurationEntryAndEmptyTranscriptCannotGenerate() {
        statuses.value = mapOf(ID to CallSummaryStatus.UNCONFIGURED)
        showHistory()
        open()
        compose.onNodeWithTag("call-summary-configure").performScrollTo().performClick()
        assertEquals(1, configured.get())
        assertEquals(emptyList<String>(), requested.toList())
        compose.runOnIdle {
            records.value = listOf(record().copy(transcript = emptyList()))
            statuses.value = mapOf(ID to CallSummaryStatus.EMPTY)
        }
        compose.onNodeWithTag("call-summary-generate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("call-summary-status").assertTextContains("没有可用于总结", substring = true)
        captureWorkspaceScreenshot("native-summary-empty.png")
    }

    private fun open() = compose.onNodeWithTag("call-history-row-$ID").performScrollTo().performClick()

    private fun showHistory() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                Column(Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CallHistorySection(
                        history = records.value,
                        historyWriteFailed = false,
                        onMark = { _, _ -> error("Summary review must not modify handled status") },
                        onDelete = { error("Summary review must not delete records") },
                        summaryStates = statuses.value,
                        onSummarize = { id -> requested += id; statuses.value = mapOf(id to CallSummaryStatus.RUNNING) },
                        onCancelSummary = { id -> canceled += id; statuses.value = mapOf(id to CallSummaryStatus.CANCELED) },
                        onConfigureSummary = { configured.incrementAndGet() },
                    )
                }
            }
        }
    }

    private companion object {
        const val ID = "summary-fixture"
        const val ORIGINAL = "今天营业到晚上九点，明天也一样。"
        fun record() = CallRecord(ID, "15555550123", CallDirection.OUTGOING,
            1_700_000_000_000, 1_700_000_030_000, "对方挂断", listOf(CallTranscriptLine("user", ORIGINAL)))
        fun summary() = CallSummary(
            generatedAtMs = 1_700_000_040_000,
            providerId = "fixture-provider", model = "fixture-text-model", sourceFingerprint = "fixture-only",
            highlights = listOf(CallSummaryItem("九点闭店", listOf(CallSummaryEvidence(1, "今天营业到晚上九点")))),
            confirmed = listOf(CallSummaryItem("明天营业安排不变", listOf(CallSummaryEvidence(1, "明天也一样")))),
            followUps = emptyList(), unknowns = emptyList(),
        )
    }
}
