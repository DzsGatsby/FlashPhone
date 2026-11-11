package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class HistoryFilter(@StringRes val label: Int) {
    ALL(R.string.calls_history_all),
    OPEN(R.string.calls_history_open),
    DONE(R.string.calls_history_done),
    RECORDED(R.string.flash_audit_filter_recorded),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallHistorySection(
    history: List<CallRecord>,
    historyWriteFailed: Boolean,
    onMark: suspend (String, Boolean) -> CallActionResult,
    onDelete: suspend (String) -> CallActionResult,
    onReuseTask: ((String, CallTaskBrief) -> Unit)? = null,
    onOpenRecording: suspend (String) -> File? = { null },
    onCloseRecording: (File) -> Unit = {},
    playbackEnabled: Boolean = true,
    onClearAll: (suspend () -> CallActionResult)? = null,
) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingClearAll by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    val scope = rememberCoroutineScope()
    val failure = stringResource(R.string.calls_action_failed)
    val deleted = stringResource(R.string.calls_history_deleted)
    val cleared = stringResource(R.string.flash_audit_cleared)
    val handled = stringResource(R.string.calls_history_done)
    val unhandled = stringResource(R.string.calls_history_open)
    val incoming = directionLabel(CallDirection.INCOMING)
    val outgoing = directionLabel(CallDirection.OUTGOING)
    val results = remember(history, filter, query, incoming, outgoing) {
        val text = query.trim()
        val digits = text.filter(Char::isDigit).takeIf {
            it.isNotEmpty() && text.all { char -> char.isDigit() || char in "+ ().-" }
        }
        history.filter { record ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.OPEN -> !record.handled
                HistoryFilter.DONE -> record.handled
                HistoryFilter.RECORDED -> record.recording != null || record.recordingPending
            }
            val direction = if (record.direction == CallDirection.INCOMING) incoming else outgoing
            matchesFilter && (text.isEmpty() || record.number.orEmpty().contains(text, ignoreCase = true) ||
                (digits != null && record.number.orEmpty().filter(Char::isDigit).contains(digits)) ||
                direction.contains(text, ignoreCase = true) || record.result.contains(text, ignoreCase = true) ||
                record.transcript.any { it.text.contains(text, ignoreCase = true) } ||
                record.task?.let { task ->
                    (listOf(task.goal, task.context, task.constraints) + task.questions).any {
                        it.contains(text, ignoreCase = true)
                    }
                } == true)
        }
    }

    fun change(success: String, action: suspend () -> CallActionResult) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val result = action()
                feedback = if (result.ok) CallActionResult(true, success) else result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                feedback = CallActionResult(false, failure)
            } finally {
                busy = false
            }
        }
    }

    val openCount = remember(history) { history.count { !it.handled } }
    val recordedCount = remember(history) { history.count { it.recording != null || it.recordingPending } }
    CallSectionHeader(
        title = stringResource(R.string.flash_hub_audit),
        badge = if (history.isNotEmpty()) "${results.size} / ${history.size}" else null,
        badgeTone = if (openCount > 0) TileTone.Warn else TileTone.Neutral,
    )
    if (historyWriteFailed) {
        CallFeedback(CallActionResult(false, stringResource(R.string.calls_history_write_failed)))
    }
    feedback?.let { result ->
        CallFeedback(result)
    }
    if (history.isEmpty()) {
        CallSurfaceCard(Modifier.padding(bottom = 12.dp)) {
            ListRow(
                icon = MikaIcons.Sound,
                title = stringResource(R.string.calls_history_empty),
                subtitle = stringResource(R.string.flash_privacy_recording_note),
                first = true,
            )
        }
    } else {
        CallSurfaceCard(Modifier.padding(bottom = 12.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CallMetaChip(
                            text = stringResource(R.string.flash_audit_summary_total, history.size),
                            icon = MikaIcons.Phone,
                        )
                        CallMetaChip(
                            text = stringResource(R.string.flash_audit_summary_open, openCount),
                            tone = if (openCount > 0) TileTone.Warn else TileTone.Ok,
                        )
                        CallMetaChip(
                            text = stringResource(R.string.flash_audit_summary_recorded, recordedCount),
                            icon = MikaIcons.Sound,
                            tone = if (recordedCount > 0) TileTone.Accent else TileTone.Neutral,
                        )
                    }
                    if (onClearAll != null) {
                        TextButton(
                            onClick = { pendingClearAll = true },
                            enabled = !busy && playbackEnabled,
                            modifier = Modifier.testTag("call-history-clear"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.flash_audit_clear_all),
                                style = MikaType.Caption,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(stringResource(R.string.calls_history_search)) },
                    leadingIcon = { Icon(MikaIcons.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-history-search"),
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HistoryFilter.entries.forEach { choice ->
                        FilterChip(
                            selected = filter == choice,
                            onClick = { filter = choice },
                            label = { Text(stringResource(choice.label)) },
                            modifier = Modifier.testTag("call-history-filter-${choice.name.lowercase()}"),
                        )
                    }
                }
            }
        }
        if (results.isEmpty()) {
            CallSurfaceCard(Modifier.padding(bottom = 12.dp)) {
                ListRow(MikaIcons.Search, stringResource(R.string.calls_history_no_match), first = true)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEachIndexed { index, record ->
                    key(record.id) {
                        CallHistoryItem(
                            record = record,
                            expanded = expanded == record.id,
                            first = index == 0,
                            busy = busy,
                            onExpand = { expanded = if (expanded == record.id) null else record.id },
                            onMark = {
                                val next = !record.handled
                                change(if (next) handled else unhandled) { onMark(record.id, next) }
                            },
                            onDelete = { pendingDelete = record.id },
                            onReuse = if (record.number != null && record.task != null && onReuseTask != null) {
                                { onReuseTask(record.number, record.task) }
                            } else null,
                            onOpenRecording = onOpenRecording,
                            onCloseRecording = onCloseRecording,
                            playbackEnabled = playbackEnabled && pendingDelete != record.id && !pendingClearAll,
                        )
                    }
                }
            }
        }
    }

    if (pendingClearAll && onClearAll != null) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            title = { Text(stringResource(R.string.flash_audit_clear_confirm_title)) },
            text = { Text(stringResource(R.string.flash_audit_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearAll = false
                        expanded = null
                        change(cleared) { onClearAll() }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("call-history-clear-confirm"),
                ) {
                    Text(stringResource(R.string.flash_audit_clear_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) { Text(stringResource(R.string.calls_cancel)) }
            },
        )
    }

    val toDelete = history.firstOrNull { it.id == pendingDelete }
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.calls_history_delete)) },
            text = {
                val locale = LocalContext.current.resources.configuration.locales[0]
                val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
                val whenCalled = remember(toDelete.startedAtMs, formatter) { formatter.format(Date(toDelete.startedAtMs)) }
                val number = toDelete.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$number · $whenCalled", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.calls_history_delete_confirm))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        change(deleted) { onDelete(toDelete.id) }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("call-history-delete-confirm"),
                ) {
                    Text(stringResource(R.string.calls_history_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.calls_cancel)) }
            },
        )
    }
}

@Composable
private fun CallHistoryItem(
    record: CallRecord,
    expanded: Boolean,
    first: Boolean,
    busy: Boolean,
    onExpand: () -> Unit,
    onMark: () -> Unit,
    onDelete: () -> Unit,
    onReuse: (() -> Unit)?,
    onOpenRecording: suspend (String) -> File?,
    onCloseRecording: (File) -> Unit,
    playbackEnabled: Boolean,
) {
    val c = MikaTheme.colors
    // Default expanded sections to true so the owner sees Recording + Task Brief + Transcript in 1 tap!
    var showTask by rememberSaveable(record.id) { mutableStateOf(true) }
    var showTranscript by rememberSaveable(record.id) { mutableStateOf(true) }
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    val start = remember(record.startedAtMs, formatter) { formatter.format(Date(record.startedAtMs)) }
    val end = remember(record.endedAtMs, formatter) { formatter.format(Date(record.endedAtMs)) }
    val direction = directionLabel(record.direction)
    val status = stringResource(if (record.handled) R.string.calls_history_done else R.string.calls_history_open)
    val name = record.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number)
    val expansion = stringResource(if (expanded) R.string.calls_collapse else R.string.calls_transcript)
    val rowTone = when {
        expanded -> TileTone.Accent
        !record.handled -> TileTone.Warn
        else -> TileTone.Ok
    }
    val cardTone = when {
        expanded -> TileTone.Accent
        !record.handled -> TileTone.Warn
        else -> TileTone.Neutral
    }

    CallSurfaceCard(
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansion },
        tone = cardTone,
    ) {
        ListRow(
            icon = if (record.recording != null) MikaIcons.Sound else MikaIcons.Clock,
            title = record.task?.goal ?: name,
            subtitle = if (record.task != null) "$name · $direction · $start" else "$direction · $start",
            first = true,
            tone = rowTone,
            detail = "$status · ${record.result}",
            detailColor = if (!record.handled) c.warn else c.ink3,
            onClick = onExpand,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(if (expanded) c.accentSoft else c.fill, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CallExpandIcon(expanded)
            }
        }

        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CallCardDivider()

                // 1. Primary Audio Recording Review Panel (shown first when reviewing calls)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, RoundedCornerShape(12.dp))
                        .border(0.5.dp, c.edge, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(MikaIcons.Clock, null, Modifier.size(14.dp), tint = c.ink3)
                        Text(
                            text = stringResource(R.string.calls_history_period, start, end),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.ink2,
                        )
                    }
                    record.recording?.let { recording ->
                        CallRecordingPlayer(recording, onOpenRecording, onCloseRecording, playbackEnabled)
                    } ?: Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(if (record.recordingPending) MikaIcons.Clock else MikaIcons.Mute, null, Modifier.size(16.dp), tint = c.ink3)
                        Text(
                            text = stringResource(if (record.recordingPending) R.string.calls_recording_saving else R.string.calls_recording_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.ink3,
                        )
                    }
                }

                // 2. Outgoing Task Brief & Reuse
                record.task?.let { task ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(c.fill, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.calls_record_result_unconfirmed),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.ink2,
                        )
                        TextButton(
                            onClick = { showTask = !showTask },
                            modifier = Modifier.fillMaxWidth().testTag("call-record-task-toggle"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(MikaIcons.Doc, null, Modifier.size(16.dp), tint = c.accent)
                                    Text(
                                        text = stringResource(R.string.calls_record_brief),
                                        style = MikaType.Body.copy(fontWeight = FontWeight.Medium),
                                        color = c.ink,
                                    )
                                }
                                CallExpandIcon(showTask)
                            }
                        }
                        if (showTask) {
                            SelectionContainer {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(c.surface, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(stringResource(R.string.calls_record_goal), style = MaterialTheme.typography.labelLarge, color = c.ink2)
                                    Text(task.goal, style = MikaType.Body, color = c.ink)
                                    if (task.questions.isNotEmpty()) {
                                        CallCardDivider()
                                        Text(stringResource(R.string.calls_record_questions), style = MaterialTheme.typography.labelLarge, color = c.ink2)
                                        task.questions.forEachIndexed { index, question ->
                                            Text("${index + 1}. $question", style = MikaType.Body, color = c.ink)
                                        }
                                    }
                                    if (task.context.isNotBlank()) {
                                        CallCardDivider()
                                        Text(stringResource(R.string.calls_record_context), style = MaterialTheme.typography.labelLarge, color = c.ink2)
                                        Text(task.context, style = MikaType.Body, color = c.ink)
                                    }
                                    if (task.constraints.isNotBlank()) {
                                        CallCardDivider()
                                        Text(stringResource(R.string.calls_record_constraints), style = MaterialTheme.typography.labelLarge, color = c.ink2)
                                        Text(task.constraints, style = MikaType.Body, color = c.ink)
                                    }
                                }
                            }
                        }
                        if (onReuse != null) {
                            OutlinedButton(
                                onClick = onReuse,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("call-record-reuse"),
                                shape = MikaShapes.Button,
                            ) {
                                Text(stringResource(R.string.calls_record_reuse))
                            }
                        }
                    }
                }

                // 3. Original Transcript Section
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(
                        onClick = { showTranscript = !showTranscript },
                        modifier = Modifier.fillMaxWidth().testTag("call-record-transcript-toggle"),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(MikaIcons.Message, null, Modifier.size(16.dp), tint = c.accent)
                                Text(
                                    text = stringResource(R.string.calls_original_transcript),
                                    style = MikaType.Body.copy(fontWeight = FontWeight.Medium),
                                    color = c.ink,
                                )
                                if (record.transcript.isNotEmpty()) {
                                    CallMetaChip(text = "${record.transcript.size}", tone = TileTone.Accent)
                                }
                            }
                            CallExpandIcon(showTranscript)
                        }
                    }
                    if (showTranscript) {
                        if (record.transcript.isEmpty()) {
                            Text(
                                text = stringResource(R.string.calls_no_transcript),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.ink3,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        } else {
                            SelectionContainer {
                                Column(
                                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    record.transcript.forEachIndexed { idx, line ->
                                        CallTranscriptText(line, turnNumber = idx + 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Audio Diagnostics Copy & Record Actions
                record.audioReport?.let { report ->
                    val label = stringResource(R.string.diagnostics_copy)
                    val copied = stringResource(R.string.diagnostics_copied)
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText(label, report.supportSummary()))
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("call-record-diagnostics-copy"),
                    ) {
                        Icon(MikaIcons.Copy, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onMark,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-history-mark"),
                        shape = MikaShapes.Button,
                    ) {
                        Text(stringResource(if (record.handled) R.string.calls_history_reopen else R.string.calls_history_mark_done))
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .background(if (busy) c.fill else c.badSoft, MikaShapes.Button)
                            .testTag("call-history-delete"),
                        shape = MikaShapes.Button,
                    ) {
                        Text(
                            text = stringResource(R.string.calls_history_delete),
                            color = if (busy) c.ink3 else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
