package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallSummaryStatus
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.FlashPhoneSheetSystemBars
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    summaryStates: Map<String, CallSummaryStatus> = emptyMap(),
    onSummarize: (String) -> Unit = {},
    onCancelSummary: (String) -> Unit = {},
    onConfigureSummary: () -> Unit = {},
    exportStore: HistoryExportStore? = null,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listScroll = rememberScrollState()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var selectedDirection by rememberSaveable { mutableStateOf<CallDirection?>(CallDirection.INCOMING) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingClearAll by rememberSaveable { mutableStateOf(false) }
    var showManagement by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    // Saved state contains no transcript: the encrypted snapshot survives the document picker.
    var exportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var exportTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var exportPreparing by remember { mutableStateOf(false) }
    val exports = remember(context.applicationContext, exportStore) { exportStore ?: HistoryExportStore.forContext(context) }
    val canExport = !exportPreparing && exportToken == null
    val failure = stringResource(R.string.calls_action_failed)
    val deleted = stringResource(R.string.calls_history_deleted)
    val cleared = stringResource(R.string.flash_audit_cleared)
    val handled = stringResource(R.string.calls_history_done)
    val unhandled = stringResource(R.string.calls_history_open)
    val incoming = directionLabel(CallDirection.INCOMING)
    val outgoing = directionLabel(CallDirection.OUTGOING)
    val exportFailed = stringResource(R.string.flash_history_export_failed)
    val searchHint = stringResource(R.string.flash_history_search_hint)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val token = exportToken
        if (uri == null) {
            exportToken = null
            exportTarget = null
            if (token != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable + Dispatchers.IO) { exports.discard(token) }
            }
        } else if (token == null) {
            feedback = CallActionResult(false, exportFailed)
        } else {
            // A provider may support a grant that survives process death while writing resumes.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            exportTarget = uri.toString()
        }
    }
    LaunchedEffect(exports, exportToken) {
        withContext(Dispatchers.IO) { exports.cleanup(setOfNotNull(exportToken)) }
    }
    LaunchedEffect(exports, exportToken, exportTarget) {
        val token = exportToken ?: return@LaunchedEffect
        val target = exportTarget ?: return@LaunchedEffect
        val uri = Uri.parse(target)
        val ok = withContext(NonCancellable + Dispatchers.IO) {
            try {
                exports.deliver(token) { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) }
            } catch (_: Exception) { false }
            finally {
                runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
        }
        if (!ok) feedback = CallActionResult(false, exportFailed)
        // If recreation canceled this effect, the receipt remains for its replacement to observe.
        if (exportToken == token && exportTarget == target) {
            exportToken = null
            exportTarget = null
            withContext(NonCancellable + Dispatchers.IO) { exports.discard(token) }
        }
    }
    fun beginExport(name: String, text: () -> String) {
        if (exportPreparing || exportToken != null) return
        exportPreparing = true
        scope.launch {
            var staged: String? = null
            var awaitingResult = false
            try {
                withContext(NonCancellable + Dispatchers.IO) { staged = exports.create(text()) }
                ensureActive()
                exportToken = staged
                exportTarget = null
                export.launch(name)
                awaitingResult = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = CallActionResult(false, exportFailed) }
            finally {
                exportPreparing = false
                if (!awaitingResult) {
                    if (exportToken == staged) exportToken = null
                    staged?.let { token -> withContext(NonCancellable + Dispatchers.IO) { exports.discard(token) } }
                }
            }
        }
    }
    // Home links name a real record. A new explicit target takes priority over saved review filters.
    LaunchedEffect(initialRecordId) {
        if (initialRecordId != null) {
            filter = HistoryFilter.ALL
            selectedDirection = null
            query = ""
            selectedId = initialRecordId
            onInitialRecordOpened()
        }
    }
    val results = remember(history, filter, selectedDirection, query, incoming, outgoing) {
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
            (selectedDirection == null || record.direction == selectedDirection) && matchesFilter && (text.isEmpty() || record.number.orEmpty().contains(text, ignoreCase = true) ||
                (digits != null && record.number.orEmpty().filter(Char::isDigit).contains(digits)) ||
                direction.contains(text, ignoreCase = true) || record.result.contains(text, ignoreCase = true) ||
                record.transcript.any { it.text.contains(text, ignoreCase = true) } ||
                record.summary?.let { summary ->
                    (summary.highlights + summary.confirmed + summary.followUps + summary.unknowns).any { item ->
                        item.text.contains(text, ignoreCase = true) || item.evidence.any { it.quote.contains(text, ignoreCase = true) }
                    }
                } == true ||
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
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.flash_workspace_call_history), style = MikaType.Title.copy(fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold), color = c.ink)
            Text(stringResource(R.string.flash_audit_summary_total, history.size), style = MikaType.Caption, color = c.ink3)
        }
        Box {
            IconButton(onClick = { showManagement = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-manage")) {
                Icon(MikaIcons.More, stringResource(R.string.flash_history_manage), tint = c.ink2)
            }
            DropdownMenu(expanded = showManagement, onDismissRequest = { showManagement = false }) {
                Text(stringResource(R.string.flash_audit_summary_total, history.size), style = MikaType.Caption, color = c.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text(stringResource(R.string.flash_audit_summary_open, history.count { !it.handled }), style = MikaType.Caption, color = c.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text(stringResource(R.string.flash_audit_summary_recorded, history.count { it.recording != null }), style = MikaType.Caption, color = c.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.flash_history_export)) },
                    onClick = {
                        showManagement = false
                        beginExport("Flash-phone-history.txt") { history.joinToString("\n\n────────\n\n") { historyExport(context, it) } }
                    }, enabled = history.isNotEmpty() && canExport, modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-export-all"),
                )
                if (onClearAll != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.flash_audit_clear_all), color = MaterialTheme.colorScheme.error) },
                    onClick = { showManagement = false; pendingClearAll = true },
                    enabled = history.isNotEmpty() && !busy && playbackEnabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-clear"),
                )
            }
        }
    }
    if (historyWriteFailed) CallFeedback(CallActionResult(false, stringResource(R.string.calls_history_write_failed)))
    feedback?.let { CallFeedback(it) }
    Column(Modifier.fillMaxWidth()) {
        TextField(
            value = query,
            onValueChange = { query = it; selectedId = null },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text(searchHint, style = MikaType.Caption) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = ""; selectedId = null },
                        modifier = Modifier.testTag("call-history-clear-search")) {
                        Icon(MikaIcons.Close, stringResource(R.string.browser_clear_input))
                    }
                }
            } else null,
            leadingIcon = { Icon(MikaIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.fill, unfocusedContainerColor = c.fill,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                focusedPlaceholderColor = c.ink3, unfocusedPlaceholderColor = c.ink3,
            ),
            textStyle = MikaType.Body,
            modifier = Modifier.fillMaxWidth().keepsKeyboard().semantics { contentDescription = searchHint }.testTag("call-history-search"),
        )
        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(CallDirection.INCOMING to incoming, CallDirection.OUTGOING to outgoing, null to stringResource(R.string.calls_history_all)).forEach { (direction, label) ->
                HistoryTab(label, selectedDirection == direction, "call-history-direction-${direction?.name?.lowercase() ?: "all"}") {
                    selectedDirection = direction; selectedId = null
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.flash_history_recent), style = MikaType.Hint, color = c.ink3, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { showStatus = true }, shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = c.fill, contentColor = c.ink2),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-history-status")) {
                    Text(stringResource(if (filter == HistoryFilter.ALL) R.string.flash_history_all_status else filter.label), style = MikaType.Caption)
                    Spacer(Modifier.width(6.dp))
                    Icon(MikaIcons.Chevron, null, Modifier.size(14.dp).rotate(90f))
                }
                DropdownMenu(expanded = showStatus, onDismissRequest = { showStatus = false }) {
                    HistoryFilter.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (choice == HistoryFilter.ALL) R.string.flash_history_all_status else choice.label)) },
                            trailingIcon = { if (filter == choice) Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.accent) },
                            onClick = { filter = choice; selectedId = null; showStatus = false },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { selected = filter == choice }.testTag("call-history-filter-${choice.name.lowercase()}"),
                        )
                    }
                }
            }
        }
        when {
            history.isEmpty() -> HistoryEmpty(stringResource(R.string.calls_history_empty), stringResource(R.string.flash_privacy_recording_note))
            results.isEmpty() -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                HistoryEmpty(stringResource(R.string.calls_history_no_match))
                TextButton(
                    onClick = {
                        query = ""
                        filter = HistoryFilter.ALL
                        selectedDirection = null
                        selectedId = null
                        focus.clearFocus()
                    },
                    modifier = Modifier.testTag("call-history-reset-filters"),
                ) { Text(stringResource(R.string.flash_reset_filters)) }
            }
            else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Large text uses the single-column flow even on a wide device.
                val split = maxWidth >= 760.dp && LocalDensity.current.fontScale < 1.5f
                BackHandler(enabled = selectedRecord != null) { selectedId = null }
                val list: @Composable () -> Unit = {
                    // A phone page has one scroll surface. A second tall viewport can strand
                    // rows below the fold in short landscapes and makes returning to them awkward.
                    // Only the desktop master pane needs an independently bounded record list.
                    val listModifier = if (split) Modifier.heightIn(max = 560.dp).verticalScroll(listScroll) else Modifier
                    Column(Modifier.fillMaxWidth().then(listModifier).testTag("call-history-list")) {
                        results.forEach { record ->
                            key(record.id) {
                                HistoryRow(record, selectedId == record.id) { focus.clearFocus(); selectedId = record.id }
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
                            summaryStatus = summaryStates[record.id],
                            onSummarize = { onSummarize(record.id) },
                            onCancelSummary = { onCancelSummary(record.id) },
                            onConfigureSummary = onConfigureSummary,
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
                                beginExport("Flash-phone-${record.startedAtMs}.txt") { historyExport(context, record) }
                            },
                            onOpenRecording = onOpenRecording, onCloseRecording = onCloseRecording,
                            playbackEnabled = playbackEnabled && pendingDelete != record.id && !pendingClearAll,
                        )
                    }
                }
                if (split) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { list() }
                    Column(Modifier.weight(1.15f).background(c.surface, RoundedCornerShape(22.dp)).border(0.5.dp, c.edge, RoundedCornerShape(22.dp)).padding(20.dp)) {
                        if (selectedRecord != null) detail(selectedRecord) else HistoryEmpty(stringResource(R.string.flash_history_select))
                    }
                } else {
                    list()
                    if (selectedRecord != null) ModalBottomSheet(
                        onDismissRequest = { selectedId = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = c.surface,
                        contentColor = c.ink,
                        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    ) {
                        FlashPhoneSheetSystemBars()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            // Action failures must stay visible above the review scroll, inside the modal.
                            Column(Modifier.fillMaxWidth().testTag("call-history-sheet-feedback")) {
                                if (historyWriteFailed) CallFeedback(CallActionResult(false, stringResource(R.string.calls_history_write_failed)))
                                feedback?.let { CallFeedback(it) }
                            }
                            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                                .padding(bottom = 24.dp)) { detail(selectedRecord) }
                        }
                    }
                }
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

@Composable
private fun HistoryTab(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.width(IntrinsicSize.Min).widthIn(min = 48.dp).clickable(role = Role.Tab, onClick = onClick)
        .semantics { selected = active }.testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.heightIn(min = 46.dp).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MikaType.Caption.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal), color = if (active) c.ink else c.ink3)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) c.accent else Color.Transparent))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(record: CallRecord, selected: Boolean, onSelect: () -> Unit) {
    val c = MikaTheme.colors
    val locale = LocalContext.current.resources.configuration.locales[0]
    val date = remember(locale) { DateFormat.getDateInstance(DateFormat.SHORT, locale) }
    val time = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    val name = record.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number)
    val title = record.task?.goal?.takeIf { it.isNotBlank() }
        ?: record.summary?.highlights?.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: name
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.4f
        val metadata: @Composable () -> Unit = {
            Text(time.format(Date(record.startedAtMs)), style = MikaType.Label, color = c.ink2)
            Text(date.format(Date(record.startedAtMs)), style = MikaType.Caption, color = c.ink3)
            Text(stringResource(if (record.handled) R.string.calls_history_done else R.string.calls_history_open),
                style = MikaType.Label, color = if (record.handled) c.ink3 else c.warn)
        }
        Row(
            Modifier.fillMaxWidth().background(if (selected) c.accentSoft else c.surface)
                .clickable(role = Role.Button, onClick = onSelect).semantics { this.selected = selected }
                .heightIn(min = 76.dp).testTag("call-history-row-${record.id}").padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!stacked) {
                Box(Modifier.size(32.dp).background(c.fill, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(MikaIcons.Up, null, Modifier.size(18.dp).rotate(if (record.direction == CallDirection.INCOMING) 225f else 45f), tint = c.ink2)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(name.takeIf { title != name }, directionLabel(record.direction), record.result).joinToString(" · "),
                    style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (record.recording != null) Text(stringResource(R.string.flash_history_recorded), style = MikaType.Label, color = c.accent)
                else if (record.recordingPending) Icon(MikaIcons.Clock, stringResource(R.string.calls_recording_saving), Modifier.size(16.dp), tint = c.ink3)
                if (stacked) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { metadata() }
            }
            if (!stacked) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) { metadata() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryDetail(
    record: CallRecord, index: Int, count: Int, busy: Boolean,
    summaryStatus: CallSummaryStatus?,
    onSummarize: () -> Unit, onCancelSummary: () -> Unit, onConfigureSummary: () -> Unit,
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
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(header)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flash_history_detail), style = MikaType.Title.copy(fontSize = 21.sp, lineHeight = 28.sp), color = c.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-back")) {
                    Icon(MikaIcons.Close, stringResource(R.string.flash_history_back), tint = c.ink2)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} / $count", style = MikaType.Hint, color = c.ink3, modifier = Modifier.weight(1f))
                IconButton(onClick = onPrevious, enabled = index > 0, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-previous")) {
                    Icon(MikaIcons.Back, stringResource(R.string.flash_history_previous))
                }
                IconButton(onClick = onNext, enabled = index < count - 1, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("call-history-next")) {
                    Icon(MikaIcons.Chevron, stringResource(R.string.flash_history_next))
                }
            }
        }
        CallCardDivider()
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(record.task?.goal?.takeIf { it.isNotBlank() } ?: record.number ?: stringResource(R.string.calls_unknown_number), style = MikaType.Title.copy(fontSize = 22.sp, lineHeight = 30.sp), color = c.ink)
                if (record.task != null) Text(record.number ?: stringResource(R.string.calls_unknown_number), style = MikaType.Caption, color = c.ink3)
                Text("${directionLabel(record.direction)} · ${historyDuration(record)}", style = MikaType.Caption, color = c.ink3)
                Text(stringResource(if (record.handled) R.string.calls_history_done else R.string.calls_history_open), style = MikaType.Caption, color = if (record.handled) c.ink3 else c.warn)
            }
        }
        record.recording?.let { recording ->
            CallRecordingPlayer(recording, onOpenRecording, onCloseRecording, playbackEnabled)
        } ?: Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (record.recordingPending) MikaIcons.Clock else MikaIcons.Mute, null, Modifier.size(22.dp), tint = c.ink3)
            Text(stringResource(if (record.recordingPending) R.string.calls_recording_saving else R.string.calls_recording_missing), style = MikaType.Caption, color = c.ink3)
        }
        CallSummaryCard(record, summaryStatus, onSummarize, onCancelSummary, onConfigureSummary)
        Text(
            stringResource(R.string.calls_history_period, formatter.format(Date(record.startedAtMs)), formatter.format(Date(record.endedAtMs))),
            style = MikaType.Caption, color = c.ink3,
        )
        Column(Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.flash_history_result), style = MikaType.Caption.copy(fontWeight = FontWeight.Medium), color = c.ink2)
            SelectionContainer { Text(record.result, style = MikaType.Body, color = c.ink) }
            if (record.task != null) Text(stringResource(R.string.calls_record_result_unconfirmed), style = MikaType.Caption, color = c.ink3)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HistoryTab(stringResource(R.string.calls_original_transcript), !showTask, "call-record-transcript-toggle") { showTask = false }
            if (record.task != null) HistoryTab(stringResource(R.string.calls_record_brief), showTask, "call-record-task-toggle") { showTask = true }
        }
        CallCardDivider()
        if (showTask && record.task != null) HistoryTask(record.task) else {
            if (record.transcript.isEmpty()) Text(stringResource(R.string.calls_no_transcript), style = MikaType.Body, color = c.ink3)
            else SelectionContainer {
                // Bound long transcripts; actions remain reachable without traversing every turn.
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .background(c.fill, RoundedCornerShape(17.dp)).padding(12.dp).testTag("call-history-transcript"),
                    verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    record.transcript.forEachIndexed { i, line -> CallTranscriptText(line, turnNumber = i + 1) }
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
        HistoryActionRow(stringResource(if (record.handled) R.string.calls_history_reopen else R.string.calls_history_mark_done), "call-history-mark", enabled = !busy, onClick = onMark)
        if (onReuse != null) HistoryActionRow(stringResource(R.string.calls_record_reuse), "call-record-reuse", enabled = !busy, onClick = onReuse)
        HistoryActionRow(stringResource(R.string.calls_history_delete), "call-history-delete", enabled = !busy, destructive = true, onClick = onDelete)
    }
}

@Composable
private fun HistoryActionRow(label: String, tag: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(vertical = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MikaType.Body, color = when {
                !enabled -> c.ink3
                destructive -> MaterialTheme.colorScheme.error
                else -> c.ink
            }, modifier = Modifier.weight(1f))
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = c.ink3)
        }
        CallCardDivider()
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
internal fun historyExport(context: Context, record: CallRecord): String = buildString {
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0])
    appendLine(context.getString(R.string.flash_history_detail))
    appendLine(record.number ?: context.getString(R.string.calls_unknown_number))
    appendLine(context.getString(R.string.calls_history_period, date.format(Date(record.startedAtMs)), date.format(Date(record.endedAtMs))))
    appendLine(context.getString(if (record.handled) R.string.calls_history_done else R.string.calls_history_open))
    appendLine(record.result)
    record.summary?.let { summary ->
        appendLine(); appendLine(context.getString(R.string.call_summary_title))
        appendLine(context.getString(R.string.call_summary_scope))
        appendLine(context.getString(R.string.call_summary_caution))
        appendLine(context.getString(R.string.call_summary_generated, date.format(Date(summary.generatedAtMs)), summary.model))
        listOf(
            R.string.call_summary_highlights to summary.highlights,
            R.string.call_summary_confirmed to summary.confirmed,
            R.string.call_summary_follow_ups to summary.followUps,
            R.string.call_summary_unknowns to summary.unknowns,
        ).forEach { (title, items) ->
            appendLine(context.getString(title))
            if (items.isEmpty()) appendLine(context.getString(R.string.call_summary_none))
            items.forEach { item ->
                appendLine("• ${item.text}")
                item.evidence.forEach { evidence ->
                    appendLine("  ${context.getString(R.string.call_summary_source_line, evidence.lineId)}: ${evidence.quote}")
                }
            }
        }
    }
    record.task?.let { task ->
        appendLine(); appendLine(context.getString(R.string.calls_record_brief)); appendLine(task.goal)
        task.questions.forEachIndexed { i, q -> appendLine("${i + 1}. $q") }
        if (task.context.isNotBlank()) { appendLine(context.getString(R.string.calls_record_context)); appendLine(task.context) }
        if (task.constraints.isNotBlank()) { appendLine(context.getString(R.string.calls_record_constraints)); appendLine(task.constraints) }
    }
    appendLine(); appendLine(context.getString(R.string.calls_original_transcript))
    if (record.transcript.isEmpty()) appendLine(context.getString(R.string.calls_no_transcript))
    record.transcript.forEach { line ->
        appendLine(context.getString(callTranscriptSpeakerLabel(line.role)))
        appendLine(line.text); appendLine()
    }
}
