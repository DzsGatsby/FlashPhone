package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class HistoryFilter(@StringRes val label: Int) {
    ALL(R.string.calls_history_all),
    OPEN(R.string.calls_history_open),
    DONE(R.string.calls_history_done),
    RECORDED(R.string.flash_audit_filter_recorded),
}

/** Review is entirely local: selecting a record never opens its audio or repeats its call. */
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
    initialRecordId: String? = null,
    onInitialRecordOpened: () -> Unit = {},
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listScroll = rememberScrollState()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingClearAll by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var exportPayload by rememberSaveable { mutableStateOf<String?>(null) }
    val failure = stringResource(R.string.calls_action_failed)
    val deleted = stringResource(R.string.calls_history_deleted)
    val cleared = stringResource(R.string.flash_audit_cleared)
    val handled = stringResource(R.string.calls_history_done)
    val unhandled = stringResource(R.string.calls_history_open)
    val incoming = directionLabel(CallDirection.INCOMING)
    val outgoing = directionLabel(CallDirection.OUTGOING)
    val exportFailed = stringResource(R.string.flash_history_export_failed)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = CallActionResult(false, exportFailed) }
        }
    }
    // Home links name a real record. A new explicit target takes priority over saved review filters.
    LaunchedEffect(initialRecordId) {
        if (initialRecordId != null) {
            filter = HistoryFilter.ALL
            query = ""
            selectedId = initialRecordId
            onInitialRecordOpened()
        }
    }
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
                HistoryFilter.RECORDED -> record.recording != null
            }
            val direction = if (record.direction == CallDirection.INCOMING) incoming else outgoing
            matchesFilter && (text.isEmpty() || record.number.orEmpty().contains(text, ignoreCase = true) ||
                (digits != null && record.number.orEmpty().filter(Char::isDigit).contains(digits)) ||
                direction.contains(text, ignoreCase = true) || record.result.contains(text, ignoreCase = true) ||
                record.transcript.any { it.text.contains(text, ignoreCase = true) } ||
                record.task?.let { task ->
                    (listOf(task.goal, task.context, task.constraints) + task.questions).any { it.contains(text, ignoreCase = true) }
                } == true)
        }
    }
    // A mark can move a call out of the active filter; a deleted call cannot remain selected.
    val selectedRecord = results.firstOrNull { it.id == selectedId }
    fun change(success: String, action: suspend () -> CallActionResult) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val result = action()
                feedback = if (result.ok) CallActionResult(true, success) else result
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = CallActionResult(false, failure) }
            finally { busy = false }
        }
    }
    CallSectionHeader(stringResource(R.string.flash_hub_audit), badge = "${results.size} / ${history.size}")
    if (historyWriteFailed) CallFeedback(CallActionResult(false, stringResource(R.string.calls_history_write_failed)))
    feedback?.let { CallFeedback(it) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.flash_audit_summary_total, history.size), style = MikaType.Caption, color = c.ink3)
            Text(stringResource(R.string.flash_audit_summary_open, history.count { !it.handled }), style = MikaType.Caption, color = c.ink3)
            Text(stringResource(R.string.flash_audit_summary_recorded, history.count { it.recording != null }), style = MikaType.Caption, color = c.ink3)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; selectedId = null },
            singleLine = true,
            shape = RoundedCornerShape(7.dp),
            label = { Text(stringResource(R.string.calls_history_search)) },
            leadingIcon = { Icon(MikaIcons.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-history-search"),
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HistoryFilter.entries.forEach { choice ->
                FilterChip(
                    selected = filter == choice,
                    onClick = { filter = choice; selectedId = null },
                    label = { Text(stringResource(choice.label)) },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-filter-${choice.name.lowercase()}"),
                )
            }
            if (onClearAll != null && history.isNotEmpty()) TextButton(
                onClick = { pendingClearAll = true },
                enabled = !busy && playbackEnabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-clear"),
            ) { Text(stringResource(R.string.flash_audit_clear_all), color = MaterialTheme.colorScheme.error) }
        }
        CallCardDivider()
        when {
            history.isEmpty() -> HistoryEmpty(stringResource(R.string.calls_history_empty), stringResource(R.string.flash_privacy_recording_note))
            results.isEmpty() -> HistoryEmpty(stringResource(R.string.calls_history_no_match))
            else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Large text uses the single-column flow even on a wide device.
                val split = maxWidth >= 760.dp && LocalDensity.current.fontScale < 1.5f
                BackHandler(enabled = selectedRecord != null) { selectedId = null }
                val list: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(listScroll).testTag("call-history-list")) {
                        results.forEach { record ->
                            key(record.id) {
                                HistoryRow(record, selectedId == record.id) { selectedId = record.id }
                                CallCardDivider()
                            }
                        }
                    }
                }
                val detail: @Composable (CallRecord) -> Unit = { record ->
                    key(record.id) {
                        val index = results.indexOfFirst { it.id == record.id }
                        HistoryDetail(
                            record = record, index = index, count = results.size, busy = busy,
                            onBack = { selectedId = null },
                            onPrevious = { results.getOrNull(index - 1)?.let { selectedId = it.id } },
                            onNext = { results.getOrNull(index + 1)?.let { selectedId = it.id } },
                            onMark = { change(if (!record.handled) handled else unhandled) { onMark(record.id, !record.handled) } },
                            onDelete = { pendingDelete = record.id },
                            onReuse = if (record.number != null && record.task != null && onReuseTask != null) {
                                { onReuseTask(record.number, record.task) }
                            } else null,
                            onCopy = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.flash_history_detail), historyExport(context, record)))
                                if (clipboard != null) Toast.makeText(context, R.string.flash_history_copied, Toast.LENGTH_SHORT).show()
                                else feedback = CallActionResult(false, failure)
                            },
                            onExport = {
                                exportPayload = historyExport(context, record)
                                try { export.launch("Flash-phone-${record.startedAtMs}.txt") }
                                catch (_: Exception) { exportPayload = null; feedback = CallActionResult(false, exportFailed) }
                            },
                            onOpenRecording = onOpenRecording, onCloseRecording = onCloseRecording,
                            playbackEnabled = playbackEnabled && pendingDelete != record.id && !pendingClearAll,
                        )
                    }
                }
                if (split) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { list() }
                    Column(Modifier.weight(1.15f).border(0.5.dp, c.edge, RoundedCornerShape(7.dp)).padding(16.dp)) {
                        if (selectedRecord != null) detail(selectedRecord) else HistoryEmpty(stringResource(R.string.flash_history_select))
                    }
                } else if (selectedRecord != null) detail(selectedRecord) else list()
            }
        }
    }
    if (pendingClearAll && onClearAll != null) AlertDialog(
        onDismissRequest = { pendingClearAll = false },
        title = { Text(stringResource(R.string.flash_audit_clear_confirm_title)) },
        text = { Text(stringResource(R.string.flash_audit_clear_confirm_body)) },
        confirmButton = {
            TextButton(onClick = {
                pendingClearAll = false
                change(cleared) { onClearAll() }
            }, enabled = !busy, modifier = Modifier.testTag("call-history-clear-confirm")) {
                Text(stringResource(R.string.flash_audit_clear_all), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { pendingClearAll = false }) { Text(stringResource(R.string.calls_cancel)) } },
    )
    val toDelete = history.firstOrNull { it.id == pendingDelete }
    if (toDelete != null) AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.calls_history_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(toDelete.number ?: stringResource(R.string.calls_unknown_number))
                Text(stringResource(R.string.calls_history_delete_confirm))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                pendingDelete = null
                change(deleted) { onDelete(toDelete.id) }
            }, enabled = !busy, modifier = Modifier.testTag("call-history-delete-confirm")) {
                Text(stringResource(R.string.calls_history_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun HistoryEmpty(title: String, subtitle: String? = null) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(MikaIcons.Doc, null, Modifier.size(24.dp), tint = c.ink3)
        Text(title, style = MikaType.Body, color = c.ink2)
        subtitle?.let { Text(it, style = MikaType.Caption, color = c.ink3) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(record: CallRecord, selected: Boolean, onSelect: () -> Unit) {
    val c = MikaTheme.colors
    val locale = LocalContext.current.resources.configuration.locales[0]
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    val name = record.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number)
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.fill else c.surface)
            .clickable(role = Role.Button, onClick = onSelect).semantics { this.selected = selected }
            .heightIn(min = 92.dp).testTag("call-history-row-${record.id}").padding(horizontal = 10.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(c.fill, RoundedCornerShape(6.dp)).border(0.5.dp, c.edge, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(if (record.direction == CallDirection.INCOMING) MikaIcons.Phone else MikaIcons.Doc, null, Modifier.size(17.dp), tint = c.ink2) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.task?.goal?.takeIf { it.isNotBlank() } ?: name, style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (record.task != null) "$name · ${record.result}" else record.result, style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${directionLabel(record.direction)} · ${date.format(Date(record.startedAtMs))}", style = MikaType.Hint, color = c.ink3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CallStatusBadge(stringResource(if (record.handled) R.string.calls_history_done else R.string.calls_history_open), if (record.handled) TileTone.Neutral else TileTone.Warn)
                Text(
                    stringResource(when {
                        record.recording != null -> R.string.flash_history_recorded
                        record.recordingPending -> R.string.calls_recording_saving
                        else -> R.string.calls_recording_missing
                    }), style = MikaType.Hint, color = c.ink3, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Icon(MikaIcons.Chevron, null, Modifier.padding(top = 2.dp).size(16.dp), tint = c.ink3)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryDetail(
    record: CallRecord, index: Int, count: Int, busy: Boolean,
    onBack: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onMark: () -> Unit, onDelete: () -> Unit, onReuse: (() -> Unit)?,
    onCopy: () -> Unit, onExport: () -> Unit,
    onOpenRecording: suspend (String) -> File?, onCloseRecording: (File) -> Unit,
    playbackEnabled: Boolean,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val header = remember(record.id) { BringIntoViewRequester() }
    LaunchedEffect(record.id) {
        withFrameNanos { }
        header.bringIntoView()
    }
    var showTask by rememberSaveable(record.id) { mutableStateOf(false) }
    val locale = context.resources.configuration.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    Column(Modifier.fillMaxWidth().testTag("call-history-detail"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(header), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-back")) {
                Icon(MikaIcons.Close, stringResource(R.string.flash_history_back), tint = c.ink2)
            }
            Text(stringResource(R.string.flash_history_detail), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
            Text("${index + 1} / $count", style = MikaType.Hint, color = c.ink3)
            IconButton(onClick = onPrevious, enabled = index > 0, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-previous")) {
                Icon(MikaIcons.Back, stringResource(R.string.flash_history_previous))
            }
            IconButton(onClick = onNext, enabled = index < count - 1, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-next")) {
                Icon(MikaIcons.Chevron, stringResource(R.string.flash_history_next))
            }
        }
        CallCardDivider()
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.task?.goal?.takeIf { it.isNotBlank() } ?: record.number ?: stringResource(R.string.calls_unknown_number), style = MaterialTheme.typography.titleLarge, color = c.ink)
                if (record.task != null) Text(record.number ?: stringResource(R.string.calls_unknown_number), style = MikaType.Caption, color = c.ink3)
            }
        }
        CallStatusBadge(stringResource(if (record.handled) R.string.calls_history_done else R.string.calls_history_open), if (record.handled) TileTone.Neutral else TileTone.Warn)
        HistoryProperty(stringResource(R.string.flash_history_direction), directionLabel(record.direction))
        HistoryProperty(stringResource(R.string.flash_history_duration), historyDuration(record))
        Text(
            stringResource(R.string.calls_history_period, formatter.format(Date(record.startedAtMs)), formatter.format(Date(record.endedAtMs))),
            style = MikaType.Caption, color = c.ink3,
        )
        Column(Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(7.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.flash_history_result), style = MikaType.Caption.copy(fontWeight = FontWeight.Medium), color = c.ink2)
            SelectionContainer { Text(record.result, style = MikaType.Body, color = c.ink) }
            if (record.task != null) Text(stringResource(R.string.calls_record_result_unconfirmed), style = MikaType.Caption, color = c.ink3)
        }
        record.recording?.let { recording ->
            CallRecordingPlayer(recording, onOpenRecording, onCloseRecording, playbackEnabled)
        } ?: Row(
            Modifier.fillMaxWidth().border(0.5.dp, c.edge, RoundedCornerShape(7.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (record.recordingPending) MikaIcons.Clock else MikaIcons.Mute, null, Modifier.size(22.dp), tint = c.ink3)
            Text(stringResource(if (record.recordingPending) R.string.calls_recording_saving else R.string.calls_recording_missing), style = MikaType.Caption, color = c.ink3)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = !showTask, onClick = { showTask = false }, label = { Text(stringResource(R.string.calls_original_transcript)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("call-record-transcript-toggle"))
            if (record.task != null) FilterChip(selected = showTask, onClick = { showTask = true }, label = { Text(stringResource(R.string.calls_record_brief)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("call-record-task-toggle"))
        }
        CallCardDivider()
        if (showTask && record.task != null) HistoryTask(record.task) else {
            if (record.transcript.isEmpty()) Text(stringResource(R.string.calls_no_transcript), style = MikaType.Body, color = c.ink3)
            else SelectionContainer {
                // Bound long transcripts; actions remain reachable without traversing every turn.
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("call-history-transcript")) {
                    record.transcript.forEachIndexed { i, line ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("${i + 1} · ${stringResource(if (line.role == "assistant") R.string.app_name else R.string.calls_caller)}", style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = if (line.role == "assistant") c.ink2 else c.accent)
                            Text(line.text, style = MikaType.Body, color = c.ink)
                        }
                        CallCardDivider()
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-copy")) { Icon(MikaIcons.Copy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.flash_history_copy)) }
            TextButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-export")) { Icon(MikaIcons.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.flash_history_export)) }
            record.audioReport?.let { report ->
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostics_copy), report.supportSummary()))
                        Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("call-record-diagnostics-copy")) { Text(stringResource(R.string.diagnostics_copy)) }
            }
        }
        CallCardDivider()
        // Flow rather than fixed equal-width buttons also works at 200% font size.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMark, enabled = !busy, shape = MikaShapes.Button, modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-mark")) {
                Text(stringResource(if (record.handled) R.string.calls_history_reopen else R.string.calls_history_mark_done))
            }
            if (onReuse != null) OutlinedButton(onClick = onReuse, enabled = !busy, shape = MikaShapes.Button, modifier = Modifier.heightIn(min = 48.dp).testTag("call-record-reuse")) {
                Text(stringResource(R.string.calls_record_reuse))
            }
            TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-delete")) {
                Text(stringResource(R.string.calls_history_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HistoryProperty(label: String, value: String) {
    val c = MikaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MikaType.Hint, color = c.ink3)
        Text(value, style = MikaType.Caption, color = c.ink2)
    }
}

@Composable
private fun HistoryTask(task: CallTaskBrief) {
    val c = MikaTheme.colors
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.calls_record_goal), style = MikaType.Caption, color = c.ink3)
            Text(task.goal, style = MikaType.Body, color = c.ink)
            if (task.questions.isNotEmpty()) {
                Text(stringResource(R.string.calls_record_questions), style = MikaType.Caption, color = c.ink3)
                task.questions.forEachIndexed { index, question -> Text("${index + 1}. $question", style = MikaType.Body, color = c.ink) }
            }
            if (task.context.isNotBlank()) { Text(stringResource(R.string.calls_record_context), style = MikaType.Caption, color = c.ink3); Text(task.context, style = MikaType.Body, color = c.ink) }
            if (task.constraints.isNotBlank()) { Text(stringResource(R.string.calls_record_constraints), style = MikaType.Caption, color = c.ink3); Text(task.constraints, style = MikaType.Body, color = c.ink) }
        }
    }
}

private fun historyDuration(record: CallRecord): String {
    val seconds = ((record.endedAtMs - record.startedAtMs).coerceAtLeast(0L) / 1000L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** A shareable text snapshot contains stored facts only, without inventing a summary or turn times. */
private fun historyExport(context: Context, record: CallRecord): String = buildString {
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0])
    appendLine(context.getString(R.string.flash_history_detail))
    appendLine(record.number ?: context.getString(R.string.calls_unknown_number))
    appendLine(context.getString(R.string.calls_history_period, date.format(Date(record.startedAtMs)), date.format(Date(record.endedAtMs))))
    appendLine(context.getString(if (record.handled) R.string.calls_history_done else R.string.calls_history_open))
    appendLine(record.result)
    record.task?.let { task ->
        appendLine(); appendLine(context.getString(R.string.calls_record_brief)); appendLine(task.goal)
        task.questions.forEachIndexed { i, q -> appendLine("${i + 1}. $q") }
        if (task.context.isNotBlank()) { appendLine(context.getString(R.string.calls_record_context)); appendLine(task.context) }
        if (task.constraints.isNotBlank()) { appendLine(context.getString(R.string.calls_record_constraints)); appendLine(task.constraints) }
    }
    appendLine(); appendLine(context.getString(R.string.calls_original_transcript))
    if (record.transcript.isEmpty()) appendLine(context.getString(R.string.calls_no_transcript))
    record.transcript.forEach { line ->
        appendLine(context.getString(if (line.role == "assistant") R.string.app_name else R.string.calls_caller))
        appendLine(line.text); appendLine()
    }
}
