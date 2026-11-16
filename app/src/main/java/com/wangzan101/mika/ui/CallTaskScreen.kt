package com.wangzan101.mika.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallSim
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** A private draft in the workspace, never a saved call profile or background request. */
@Stable
internal class CallTaskDraft {
    var number by mutableStateOf("")
    var goal by mutableStateOf("")
    var questions by mutableStateOf("")
    var context by mutableStateOf("")
    var constraints by mutableStateOf("")
    var simOverride by mutableStateOf<String?>(null)
    var persistenceLoaded by mutableStateOf(true)
    var persistenceFailed by mutableStateOf(false)
    var persistedFields by mutableStateOf<List<String>?>(null)
    var persistenceGeneration = 0L
    var blockedInitialFields: List<String>? = null

    fun needsPersistence(fields: List<String> = snapshotFields()): Boolean =
        fields != persistedFields && fields != blockedInitialFields

    fun beginPersistenceWrite(revision: Long) {
        persistenceGeneration = revision
        // A cancelled IO write may still commit. Until its completion is acknowledged,
        // no in-memory baseline can prove what is currently on disk.
        persistedFields = null
        blockedInitialFields = null
    }

    fun completePersistenceWrite(fields: List<String>, revision: Long, success: Boolean) {
        if (persistenceGeneration != revision) return
        persistenceFailed = !success
        if (success) persistedFields = fields
    }

    fun snapshotFields(): List<String> = listOf(number, goal, questions, context, constraints, simOverride.orEmpty())
    fun restoreFields(fields: List<String>) {
        require(fields.size == 6)
        number = fields[0]; goal = fields[1]; questions = fields[2]
        context = fields[3]; constraints = fields[4]; simOverride = fields[5].takeIf(String::isNotEmpty)
    }

    val hasContent: Boolean
        get() = number.isNotBlank() || goal.isNotBlank() || questions.isNotBlank() ||
            context.isNotBlank() || constraints.isNotBlank()

    fun fill(number: String, brief: CallTaskBrief) {
        this.number = number
        goal = brief.goal
        questions = brief.questions.joinToString("\n")
        context = brief.context
        constraints = brief.constraints
        simOverride = null
    }

    fun clear() {
        number = ""
        goal = ""
        questions = ""
        context = ""
        constraints = ""
        simOverride = null
    }

    companion object {
        val Saver = listSaver<CallTaskDraft, String>(
            save = { draft ->
                draft.snapshotFields() + listOf(
                    draft.persistenceLoaded.toString(),
                    draft.persistenceFailed.toString(),
                    if (draft.blockedInitialFields == null) "unblocked" else "blocked",
                ) + draft.blockedInitialFields.orEmpty()
            },
            restore = { saved ->
                saved.takeIf { it.size == 6 || it.size == 9 || it.size == 15 }?.let {
                    CallTaskDraft().apply {
                        restoreFields(it.take(6))
                        // Six-field state predates durable drafts; those are real editor
                        // fields. New state explicitly distinguishes loading placeholders.
                        persistenceLoaded = it.getOrNull(6)?.toBooleanStrictOrNull() ?: true
                        persistenceFailed = it.getOrNull(7)?.toBooleanStrictOrNull() ?: false
                        blockedInitialFields = if (it.size == 15 && it[8] == "blocked") it.drop(9) else null
                    }
                }
            },
        )
    }
}

internal data class CallTaskSubmission(
    val number: String,
    val simId: String?,
    val simLabel: String,
    val brief: CallTaskBrief,
) {
    override fun toString(): String = "CallTaskSubmission(redacted)"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CallTaskScreen(
    draft: CallTaskDraft,
    config: CallConfig,
    state: CallRuntimeState,
    ready: Boolean,
    editable: Boolean,
    canDial: Boolean,
    feedback: CallActionResult?,
    onSetup: () -> Unit,
    onConfirm: (CallTaskSubmission) -> Unit,
    onConfirmLocked: (CallTaskSubmission) -> Unit,
    onDefaultSim: ((String?) -> Unit)? = null,
    onStartVoiceBriefing: ((number: String, simId: String?, simLabel: String) -> Unit)? = null,
    voiceBusy: Boolean = false,
) {
    // The selected dialer reference is deliberately white, including when the rest of the app is dark.
    MikaTheme(dark = false, reduceMotion = MikaTheme.reduceMotion) {
        val c = MikaTheme.colors
        val palette = dialerPalette()
        val compactDialer = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 700
        var showSims by rememberSaveable { mutableStateOf(false) }
        var textOpen by rememberSaveable { mutableStateOf(false) }
        var textBuffer by rememberSaveable { mutableStateOf("") }
        var requiresSections by rememberSaveable { mutableStateOf(false) }
        var review by remember { mutableStateOf<CallTaskSubmission?>(null) }
        var tapMessage by remember { mutableStateOf<String?>(null) }
        val number = CallPolicy.outgoingNumber(draft.number)
        val sim = CallPolicy.chooseOutgoingSim(state.simOptions, draft.simOverride, config.defaultOutgoingSimId)
        val missingSim = stringResource(R.string.calls_task_pick_sim)
        val currentSim = sim?.takeIf { selected -> state.simOptions.any { it.id == selected.id } }
        val canEdit = editable && !voiceBusy
        val canStartVoice = canEdit && ready && canDial && number != null && currentSim != null && onStartVoiceBriefing != null
        val hasTextBrief = draft.goal.isNotBlank() || draft.questions.isNotBlank() ||
            draft.context.isNotBlank() || draft.constraints.isNotBlank()
        val openText = {
            if (canEdit) {
                val fields = CallTaskTextFields.fromDraft(draft)
                textBuffer = fields.editorText()
                requiresSections = fields.hasSections
                textOpen = true
            }
        }

        if (!draft.persistenceLoaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("call-draft-loading"))
            return@MikaTheme
        }

        Column(
            Modifier.fillMaxWidth().background(palette.background).padding(horizontal = 20.dp).testTag("call-task-fields"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { showSims = true },
                    enabled = canEdit,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-task-sim"),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (currentSim != null) palette.blue else androidx.compose.ui.graphics.Color.Transparent),
                ) {
                    Text(currentSim?.label ?: missingSim, color = if (currentSim != null) androidx.compose.ui.graphics.Color.White else if (canEdit) palette.blue else c.ink3,
                        style = MaterialTheme.typography.titleSmall)
                    Icon(MikaIcons.Chevron, null, Modifier.padding(start = 4.dp).size(14.dp), tint = if (currentSim != null) androidx.compose.ui.graphics.Color.White else c.ink3)
                }
                Spacer(Modifier.weight(1f))
                if (draft.hasContent) {
                    TextButton(onClick = { draft.clear() }, enabled = canEdit,
                        modifier = Modifier.testTag("call-task-clear")) {
                        Text(stringResource(R.string.flash_dial_clear_draft), style = MikaType.Caption, color = c.ink3)
                    }
                }
            }
            feedback?.let { CallFeedback(it) }
            if (!ready) {
                TextButton(onClick = onSetup, modifier = Modifier.testTag("call-task-setup")) {
                    Icon(MikaIcons.Gear, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.calls_device_and_permissions), style = MikaType.Caption)
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = if (compactDialer) 52.dp else 84.dp).padding(vertical = if (compactDialer) 6.dp else 12.dp), contentAlignment = Alignment.Center) {
                BasicTextField(
                    value = draft.number,
                    onValueChange = { draft.number = it },
                    enabled = canEdit,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = TextStyle(color = c.ink, fontSize = 34.sp, fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center, textDirection = TextDirection.Ltr),
                    cursorBrush = SolidColor(palette.blue),
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-number")
                        .semantics { contentDescription = "电话号码" },
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (draft.number.isEmpty()) Text("输入号码", color = c.ink3, fontSize = 27.sp)
                            inner()
                        }
                    },
                )
            }
            if (draft.number.isNotBlank() && number == null) {
                Text(stringResource(R.string.calls_task_number_hint), color = c.bad, style = MikaType.Caption,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("call-number-error"))
            }
            DialerKeypad(
                enabled = canEdit,
                onKey = { draft.number += it },
                onPlus = { if (draft.number.isEmpty()) draft.number = "+" },
            )
            Spacer(Modifier.height(if (compactDialer) 12.dp else 18.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val buttonSize = dialerKeySize(maxWidth)
                Box(
                    Modifier.size(buttonSize).clip(CircleShape)
                        .background(if (canStartVoice) palette.callGreen else palette.callGreen.copy(alpha = 0.42f))
                        .testTag("call-start-voice")
                        .combinedClickable(
                            enabled = canEdit,
                            role = Role.Button,
                            onClickLabel = "语音交代",
                            onLongClickLabel = "文字交代",
                            onLongClick = openText,
                            onClick = {
                                when {
                                    number == null -> tapMessage = "请先输入有效的电话号码"
                                    !ready -> onSetup()
                                    currentSim == null -> showSims = true
                                    !canDial -> tapMessage = "当前有电话任务正在处理，请稍后再试"
                                    onStartVoiceBriefing == null -> tapMessage = "语音交代暂不可用，可长按使用文字交代"
                                    canStartVoice -> {
                                        tapMessage = null
                                        onStartVoiceBriefing?.invoke(number, currentSim.id, currentSim.label)
                                    }
                                }
                            },
                        )
                        .semantics {
                            contentDescription = if (voiceBusy) "正在语音交代" else "语音交代，长按文字交代"
                            val descriptions = buildList {
                                if (hasTextBrief) add("已有交代草稿")
                                if (!canStartVoice && canEdit) add("请先完成号码、SIM 和通话准备；仍可长按文字交代")
                            }
                            if (descriptions.isNotEmpty()) stateDescription = descriptions.joinToString("；")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (voiceBusy) CircularProgressIndicator(Modifier.size(28.dp), color = palette.onGreen, strokeWidth = 2.dp)
                    else Icon(DialerIcons.Handset, null, Modifier.size(32.dp), tint = palette.onGreen)
                }
                IconButton(
                    onClick = { draft.number = draft.number.dropLast(1) },
                    enabled = canEdit && draft.number.isNotEmpty(),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).testTag("call-number-delete"),
                ) {
                    Icon(DialerIcons.Delete, "删除一位号码", Modifier.size(25.dp), tint = c.ink2)
                }
            }
            tapMessage?.let { Text(it, style = MikaType.Caption, color = c.warn,
                modifier = Modifier.padding(top = 10.dp).testTag("call-task-action-hint")) }
            Text(if (hasTextBrief) "轻点语音交代 · 长按编辑草稿" else "轻点语音交代 · 长按文字交代",
                style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (compactDialer) 8.dp else 12.dp).testTag("call-task-brief-hint"))
            if (draft.persistenceFailed) {
                Text(stringResource(R.string.flash_draft_save_failed), color = c.bad, style = MikaType.Caption,
                    modifier = Modifier.testTag("call-draft-save-state"))
            }
        }

        if (textOpen) {
            val parsed = remember(textBuffer, requiresSections) { CallTaskTextFields.parse(textBuffer, requiresSections) }
            val validation = remember(parsed) { parsed.mapCatching { it.brief().validatedSnapshot() } }
            val textError = validation.exceptionOrNull()?.message?.takeIf { textBuffer.isNotBlank() || requiresSections }
            ModalBottomSheet(
                onDismissRequest = { textOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = c.bg,
                modifier = Modifier.testTag("call-text-brief-sheet"),
            ) {
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("文字交代", style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Text("${number ?: draft.number.ifBlank { "请先输入号码" }} · ${currentSim?.label ?: missingSim}",
                        style = MikaType.Caption, color = c.ink3)
                    OutlinedTextField(
                        value = textBuffer,
                        onValueChange = { value ->
                            textBuffer = value
                            // Keep the same durable six-field draft; partial section edits never erase hidden legacy fields.
                            CallTaskTextFields.parse(value, requiresSections).getOrNull()?.applyTo(draft)
                        },
                        enabled = canEdit,
                        label = { Text("本次交代") },
                        placeholder = { Text("想让 AI 办什么？需要问清哪些问题？\n补充背景，以及不能承诺或不能做的事。") },
                        minLines = 5, maxLines = 10,
                        isError = textError != null,
                        supportingText = if (textError != null) ({ Text(textError) }) else null,
                        modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-task-text-brief"),
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (requiresSections) Text("原有目标、问题、背景和边界已保留在同一个框内。", style = MikaType.Caption, color = c.ink3)
                    Button(
                        enabled = canEdit && number != null && validation.isSuccess,
                        onClick = {
                            val snapshot = validation.getOrNull()
                            if (number != null && snapshot != null) {
                                parsed.getOrNull()?.applyTo(draft)
                                review = CallTaskSubmission(number, currentSim?.id, currentSim?.label ?: missingSim, snapshot)
                                textOpen = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("call-task-review"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.callGreen, contentColor = palette.onGreen),
                    ) { Text(stringResource(R.string.calls_task_review)) }
                    TextButton(onClick = { textOpen = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("返回拨号键盘")
                    }
                }
            }
        }
        if (showSims) {
            CallTaskSimDialog(
                options = state.simOptions,
                preferred = config.defaultOutgoingSimId,
                selectedOverride = draft.simOverride,
                onChoose = { draft.simOverride = it; showSims = false },
                onDefaultSim = onDefaultSim,
                onDismiss = { showSims = false },
            )
        }
        review?.let { submission ->
            val allowed = ready && canDial && !voiceBusy && state.simOptions.any { it.id == submission.simId }
            CallTaskReviewDialog(
                submission = submission,
                canDial = allowed,
                onDismiss = { review = null; openText() },
                onDial = { locked ->
                    if (allowed) {
                        review = null
                        if (locked) onConfirmLocked(submission) else onConfirm(submission)
                    }
                },
            )
        }
    }
}

@Composable
private fun CallTaskSimDialog(
    options: List<CallSim>,
    preferred: String?,
    selectedOverride: String?,
    onChoose: (String?) -> Unit,
    onDefaultSim: ((String?) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val remembered = options.firstOrNull { it.id == preferred }
    val platformDefault = options.singleOrNull { it.isDefault }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_sim)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (remembered != null) {
                    RadioRow(
                        title = stringResource(R.string.calls_sim_preferred, remembered.label),
                        selected = selectedOverride == null,
                        onClick = { onChoose(null) },
                        first = true,
                    )
                }
                if (platformDefault != null && options.size > 1) {
                    RadioRow(
                        title = stringResource(R.string.calls_default),
                        selected = selectedOverride == "default",
                        onClick = { onChoose("default") },
                        first = remembered == null,
                        subtitle = platformDefault.label,
                    )
                }
                options.forEachIndexed { index, option ->
                    RadioRow(
                        title = option.label,
                        selected = selectedOverride == option.id,
                        onClick = { onChoose(option.id) },
                        first = index == 0 && remembered == null && platformDefault == null,
                    )
                }
                if (onDefaultSim != null && options.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    CallCardDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calls_default_sim_settings),
                        style = MaterialTheme.typography.labelMedium,
                        color = MikaTheme.colors.ink3,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    RadioRow(
                        title = stringResource(R.string.calls_sim_ask_each_time),
                        selected = preferred == null,
                        onClick = { onDefaultSim(null) },
                        first = true,
                    )
                    options.forEach { sim ->
                        RadioRow(
                            title = sim.label,
                            selected = preferred == sim.id,
                            onClick = { onDefaultSim(sim.id) },
                            subtitle = if (sim.isDefault) stringResource(R.string.calls_default) else null,
                        )
                    }
                }
                if (options.isEmpty()) Text(stringResource(R.string.calls_task_pick_sim))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun CallTaskReviewDialog(
    submission: CallTaskSubmission,
    canDial: Boolean,
    onDismiss: () -> Unit,
    onDial: (Boolean) -> Unit,
) {
    val c = MikaTheme.colors
    var locked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_task_review)) },
        modifier = Modifier.testTag("call-task-review-dialog"),
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskReviewField(stringResource(R.string.calls_number), "\u2066${submission.number}\u2069")
                    CallCardDivider()
                    TaskReviewField(stringResource(R.string.calls_sim), submission.simLabel)
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskReviewField(stringResource(R.string.calls_task_goal), submission.brief.goal)
                    if (submission.brief.questions.isNotEmpty()) {
                        CallCardDivider()
                        TaskReviewField(
                            stringResource(R.string.calls_task_questions),
                            submission.brief.questions.mapIndexed { index, question -> "${index + 1}. $question" }
                                .joinToString("\n"),
                        )
                    }
                    if (submission.brief.context.isNotBlank()) {
                        CallCardDivider()
                        TaskReviewField(stringResource(R.string.calls_task_context), submission.brief.context)
                    }
                    if (submission.brief.constraints.isNotBlank()) {
                        CallCardDivider()
                        TaskReviewField(stringResource(R.string.calls_task_constraints), submission.brief.constraints)
                    }
                }

                if (canDial) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (mode in listOf(false, true)) {
                            val selected = locked == mode
                            val shape = RoundedCornerShape(6.dp)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .background(if (selected) c.accentSoft else c.fill, shape)
                                    .border(
                                        width = if (selected) 1.dp else 0.5.dp,
                                        color = if (selected) c.accent else c.edge,
                                        shape = shape,
                                    )
                                    .clip(shape)
                                    .testTag(if (mode) "call-mode-lock" else "call-mode-now")
                                    .selectable(selected = selected, role = Role.RadioButton) { locked = mode }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    text = stringResource(if (mode) R.string.calls_task_dial_locked else R.string.calls_task_dial_now),
                                    style = MikaType.Body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (selected) c.accent else c.ink,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(
                        when {
                            !canDial -> R.string.calls_task_review_blocked
                            locked -> R.string.calls_task_dial_locked_note
                            else -> R.string.calls_task_confirm_note
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!canDial) c.warn else c.ink3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDial(locked) },
                enabled = canDial,
                shape = MikaShapes.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.onAccent,
                ),
                modifier = Modifier.testTag(if (locked) "call-dial-locked" else "call-dial"),
            ) {
                Text(stringResource(if (locked) R.string.calls_task_dial_locked_confirm else R.string.calls_task_confirm_dial))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("call-task-review-cancel")) {
                Text(stringResource(R.string.calls_task_back_to_edit))
            }
        },
    )
}

@Composable
private fun TaskReviewField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MikaTheme.colors.ink3)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MikaTheme.colors.ink)
    }
}
