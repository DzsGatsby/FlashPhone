package com.wangzan101.mika.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallSummaryItem
import com.wangzan101.mika.calls.CallSummaryStatus
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.text.DateFormat
import java.util.Date

/** Display saved analysis only. Opening or expanding this card never requests a model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallSummaryCard(
    record: CallRecord,
    status: CallSummaryStatus?,
    onSummarize: () -> Unit,
    onCancel: () -> Unit,
    onConfigure: () -> Unit,
) {
    val c = MikaTheme.colors
    val summary = record.summary
    val contentHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceIn(160.dp, 440.dp)
    val running = status == CallSummaryStatus.RUNNING
    val empty = record.transcript.none { it.text.isNotBlank() }
    Column(
        Modifier.fillMaxWidth().border(0.5.dp, c.edge, MikaShapes.Card)
            .background(c.surface, MikaShapes.Card).padding(16.dp).testTag("call-summary-card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.call_summary_title), style = MikaType.BodyStrong, color = c.ink)
        Text(stringResource(R.string.call_summary_scope), style = MikaType.Caption, color = c.ink2)
        Text(stringResource(R.string.call_summary_caution), style = MikaType.Hint, color = c.ink3)
        if (summary != null) {
            val generatedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(summary.generatedAtMs))
            Text(stringResource(R.string.call_summary_generated, generatedAt, summary.model), style = MikaType.Hint, color = c.ink3)
        }
        val message = when {
            running -> R.string.call_summary_running
            status == CallSummaryStatus.UNCONFIGURED -> R.string.call_summary_unconfigured
            status == CallSummaryStatus.SAVE_FAILED -> R.string.call_summary_save_failed
            status == CallSummaryStatus.CANCELED -> R.string.call_summary_canceled
            status == CallSummaryStatus.TIMEOUT -> R.string.call_summary_timeout
            status == CallSummaryStatus.BUSY -> R.string.call_summary_busy
            status == CallSummaryStatus.INVALID -> R.string.call_summary_invalid
            status == CallSummaryStatus.FAILED -> R.string.call_summary_failed
            empty || status == CallSummaryStatus.EMPTY -> R.string.call_summary_no_transcript
            summary == null -> R.string.call_summary_prompt
            else -> null
        }
        message?.let {
            Text(stringResource(it), style = MikaType.Caption, color = c.ink2, modifier = Modifier.testTag("call-summary-status"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (running) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-cancel")) {
                    Text(stringResource(R.string.call_summary_cancel))
                }
            } else if (status == CallSummaryStatus.UNCONFIGURED) {
                OutlinedButton(onClick = onConfigure, shape = MikaShapes.Button,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-configure")) {
                    Text(stringResource(R.string.call_summary_configure))
                }
                TextButton(onClick = onSummarize, enabled = !empty,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-generate")) {
                    Text(stringResource(if (summary == null) R.string.call_summary_generate else R.string.call_summary_regenerate))
                }
            } else {
                OutlinedButton(onClick = onSummarize, enabled = !empty && status != CallSummaryStatus.EMPTY,
                    shape = MikaShapes.Button, modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-generate")) {
                    Text(stringResource(if (summary == null) R.string.call_summary_generate else R.string.call_summary_regenerate))
                }
            }
        }
        if (summary != null) {
            CallCardDivider()
            // Keep generation controls and the original recording/transcript actions within reach.
            Column(Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState())
                .testTag("call-summary-content"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SummaryGroup(record, "highlights", R.string.call_summary_highlights, summary.highlights)
                SummaryGroup(record, "confirmed", R.string.call_summary_confirmed, summary.confirmed)
                SummaryGroup(record, "follow-ups", R.string.call_summary_follow_ups, summary.followUps)
                SummaryGroup(record, "unknowns", R.string.call_summary_unknowns, summary.unknowns)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SummaryGroup(record: CallRecord, group: String, @StringRes label: Int, items: List<CallSummaryItem>) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().testTag("call-summary-$group"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(label), style = MikaType.Label, color = c.ink2)
        if (items.isEmpty()) Text(stringResource(R.string.call_summary_none), style = MikaType.Caption, color = c.ink3)
        items.forEachIndexed { index, item ->
            var expanded by rememberSaveable(record.id, record.summary?.generatedAtMs, group, index) { mutableStateOf(false) }
            val source = remember(record.id, group, index) { BringIntoViewRequester() }
            LaunchedEffect(expanded) {
                if (expanded) {
                    withFrameNanos { }
                    // Expand at the user's tap location, then reveal the source through both scroll parents.
                    source.bringIntoView()
                }
            }
            SelectionContainer { Text("• ${item.text}", style = MikaType.Body, color = c.ink) }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("call-summary-$group-$index-sources")) {
                Text(stringResource(if (expanded) R.string.call_summary_hide_sources else R.string.call_summary_sources, item.evidence.size))
            }
            if (expanded) SelectionContainer {
                Column(Modifier.fillMaxWidth().background(c.fill, MikaShapes.Card).padding(12.dp)
                    .bringIntoViewRequester(source).testTag("call-summary-$group-$index-evidence"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item.evidence.forEach { evidence ->
                        val line = record.transcript.getOrNull(evidence.lineId - 1)
                        Text(stringResource(R.string.call_summary_source_line, evidence.lineId), style = MikaType.Label, color = c.ink2)
                        Text(evidence.quote, style = MikaType.Body, color = c.ink)
                        if (line != null) {
                            Text(stringResource(R.string.call_summary_saved_original,
                                stringResource(if (line.role == "assistant") R.string.app_name else R.string.calls_caller)),
                                style = MikaType.Hint, color = c.ink3)
                            Text(line.text, style = MikaType.Body, color = c.ink2)
                        } else Text(stringResource(R.string.call_summary_source_missing), style = MikaType.Caption, color = c.ink3)
                    }
                }
            }
        }
    }
}
