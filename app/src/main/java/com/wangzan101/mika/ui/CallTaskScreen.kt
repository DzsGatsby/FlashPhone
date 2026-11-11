package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
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

    companion object {
        val Saver = listSaver<CallTaskDraft, String>(
            save = { draft ->
                listOf(
                    draft.number,
                    draft.goal,
                    draft.questions,
                    draft.context,
                    draft.constraints,
                    draft.simOverride.orEmpty(),
                )
            },
            restore = { fields ->
                fields.takeIf { it.size == 6 }?.let {
                    CallTaskDraft().apply {
                        number = it[0]
                        goal = it[1]
                        questions = it[2]
                        context = it[3]
                        constraints = it[4]
                        simOverride = it[5].takeIf(String::isNotEmpty)
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
) {
    val c = MikaTheme.colors
    var contextOpen by rememberSaveable { mutableStateOf(false) }
    var constraintsOpen by rememberSaveable { mutableStateOf(false) }
    var showSims by rememberSaveable { mutableStateOf(false) }
    var review by remember { mutableStateOf<CallTaskSubmission?>(null) }
    val number = CallPolicy.outgoingNumber(draft.number)
    val goalLength = draft.goal.trim().length
    val maxGoal = CallTaskBrief.MAX_GOAL_CHARS
    val questions = remember(draft.questions) {
        draft.questions.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }
    val badQuestions = questions.size > CallTaskBrief.MAX_QUESTIONS ||
        questions.any { it.length > CallTaskBrief.MAX_QUESTION_CHARS }
    val contextLength = draft.context.trim().length
    val constraintsLength = draft.constraints.trim().length
    val preferred = state.simOptions.firstOrNull { it.id == config.defaultOutgoingSimId }
    val sim = CallPolicy.chooseOutgoingSim(state.simOptions, draft.simOverride, config.defaultOutgoingSimId)
    val missingSim = stringResource(R.string.calls_task_pick_sim)
    val brief = remember(draft.goal, questions, draft.context, draft.constraints) {
        runCatching { CallTaskBrief(draft.goal, questions, draft.context, draft.constraints).validatedSnapshot() }.getOrNull()
    }

    feedback?.let { CallFeedback(it) }

    if (!ready) {
        CallSurfaceCard(
            modifier = Modifier.padding(bottom = 12.dp),
            tone = TileTone.Warn,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconTile(MikaIcons.Gear, TileTone.Warn)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.calls_needs_setup), style = MikaType.BodyStrong, color = c.ink)
                        Text(stringResource(R.string.calls_task_review_blocked), style = MikaType.Caption, color = c.ink3)
                    }
                }
                OutlinedButton(
                    onClick = onSetup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    shape = MikaShapes.Button,
                ) {
                    Text(stringResource(R.string.calls_device_and_permissions))
                }
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().testTag("call-task-fields"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Card 1: Dial Target & Outgoing SIM
        CallSurfaceCard(
            tone = if (number != null && sim != null) TileTone.Accent else TileTone.Neutral,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconTile(MikaIcons.Phone, if (number != null) TileTone.Ok else TileTone.Accent)
                        Text(stringResource(R.string.calls_outgoing_title), style = MikaType.BodyStrong)
                    }
                    if (number != null) {
                        CallStatusBadge(text = "\u2066$number\u2069", tone = TileTone.Ok)
                    }
                }
                OutlinedTextField(
                    value = draft.number,
                    onValueChange = { draft.number = it },
                    enabled = editable,
                    label = { Text(stringResource(R.string.calls_number)) },
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-number"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(
                        textDirection = TextDirection.Ltr,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = draft.number.isNotBlank() && number == null,
                    supportingText = if (draft.number.isNotBlank() && number == null) {
                        { Text(stringResource(R.string.calls_task_number_hint)) }
                    } else null,
                )
            }
            CallCardDivider()
            val simSummary = when {
                sim != null && draft.simOverride == null && sim.id == config.defaultOutgoingSimId ->
                    stringResource(R.string.calls_sim_preferred, sim.label)
                sim != null && preferred != null && sim.id != preferred.id ->
                    stringResource(R.string.calls_task_sim_override_summary, sim.label, preferred.label)
                sim != null -> sim.label
                config.defaultOutgoingSimId != null && draft.simOverride == null ->
                    stringResource(R.string.calls_sim_unavailable)
                else -> missingSim
            }
            ListRow(
                icon = MikaIcons.Phone,
                title = stringResource(R.string.calls_sim),
                subtitle = simSummary,
                first = true,
                tone = if (sim != null) TileTone.Ok else TileTone.Warn,
                onClick = if (editable) ({ showSims = true }) else null,
            ) { RowChevron() }
        }

        // Card 2: Core Mission Brief (Goal & Questions)
        CallSurfaceCard(
            tone = if (brief != null) TileTone.Ok else TileTone.Neutral,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconTile(MikaIcons.Doc, if (brief != null) TileTone.Ok else TileTone.Accent)
                        Text(stringResource(R.string.calls_record_brief), style = MikaType.BodyStrong)
                    }
                    CallMetaChip(
                        text = stringResource(R.string.calls_task_limit, goalLength, maxGoal),
                        tone = if (goalLength > maxGoal) TileTone.Bad else if (goalLength > 0) TileTone.Accent else TileTone.Neutral,
                    )
                }

                OutlinedTextField(
                    value = draft.goal,
                    onValueChange = { draft.goal = it },
                    enabled = editable,
                    label = { Text(stringResource(R.string.calls_task_goal)) },
                    placeholder = { Text(stringResource(R.string.calls_task_goal_example)) },
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-task-goal"),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2,
                    maxLines = 4,
                    isError = goalLength > maxGoal,
                    supportingText = if (goalLength > maxGoal) {
                        { Text(stringResource(R.string.calls_task_limit, goalLength, maxGoal)) }
                    } else null,
                )

                OutlinedTextField(
                    value = draft.questions,
                    onValueChange = { draft.questions = it },
                    enabled = editable,
                    label = { Text(stringResource(R.string.calls_task_questions)) },
                    placeholder = { Text(stringResource(R.string.calls_task_questions_hint)) },
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-task-questions"),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2,
                    maxLines = 5,
                    isError = badQuestions,
                    supportingText = if (badQuestions) {
                        {
                            Text(
                                stringResource(
                                    R.string.calls_task_questions_limit,
                                    CallTaskBrief.MAX_QUESTIONS,
                                    CallTaskBrief.MAX_QUESTION_CHARS,
                                ),
                            )
                        }
                    } else if (questions.isNotEmpty()) {
                        { Text("${questions.size} / ${CallTaskBrief.MAX_QUESTIONS}") }
                    } else null,
                )
            }
        }

        // Card 3: Optional Context & Constraints
        CallSurfaceCard {
            val hasContext = draft.context.isNotBlank()
            CallFoldRow(
                icon = MikaIcons.Info,
                title = stringResource(R.string.calls_task_context),
                expanded = contextOpen,
                testTag = "call-task-context-fold",
                subtitle = stringResource(if (hasContext) R.string.calls_task_filled else R.string.calls_task_optional),
                tone = if (hasContext) TileTone.Accent else TileTone.Neutral,
            ) {
                contextOpen = !contextOpen
            }
            if (contextOpen) {
                CallTaskLongField(
                    value = draft.context,
                    onValue = { draft.context = it },
                    label = stringResource(R.string.calls_task_context),
                    editable = editable,
                    length = contextLength,
                    max = CallTaskBrief.MAX_CONTEXT_CHARS,
                    tag = "call-task-context",
                )
            }
            CallCardDivider()
            val hasConstraints = draft.constraints.isNotBlank()
            CallFoldRow(
                icon = MikaIcons.Shield,
                title = stringResource(R.string.calls_task_constraints),
                expanded = constraintsOpen,
                testTag = "call-task-constraints-fold",
                subtitle = stringResource(if (hasConstraints) R.string.calls_task_filled else R.string.calls_task_optional),
                tone = if (hasConstraints) TileTone.Warn else TileTone.Neutral,
            ) {
                constraintsOpen = !constraintsOpen
            }
            if (constraintsOpen) {
                CallTaskLongField(
                    value = draft.constraints,
                    onValue = { draft.constraints = it },
                    label = stringResource(R.string.calls_task_constraints),
                    editable = editable,
                    length = constraintsLength,
                    max = CallTaskBrief.MAX_CONSTRAINT_CHARS,
                    tag = "call-task-constraints",
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    CallPrimaryButton(
        text = stringResource(R.string.calls_task_review),
        enabled = editable && number != null && brief != null,
        onClick = {
            if (number != null && brief != null) {
                review = CallTaskSubmission(number, sim?.id, sim?.label ?: missingSim, brief)
            }
        },
        modifier = Modifier.testTag("call-task-review"),
    )
    Spacer(Modifier.height(8.dp))
    FooterNote(stringResource(R.string.calls_task_one_off))

    if (showSims) {
        CallTaskSimDialog(
            options = state.simOptions,
            preferred = config.defaultOutgoingSimId,
            selectedOverride = draft.simOverride,
            onChoose = { draft.simOverride = it; showSims = false },
            onDismiss = { showSims = false },
        )
    }
    review?.let { submission ->
        val allowed = ready && canDial && state.simOptions.any { it.id == submission.simId }
        CallTaskReviewDialog(
            submission = submission,
            canDial = allowed,
            onDismiss = { review = null },
            onDial = { locked ->
                if (allowed) {
                    review = null
                    if (locked) onConfirmLocked(submission) else onConfirm(submission)
                }
            },
        )
    }
}

@Composable
private fun CallTaskLongField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    editable: Boolean,
    length: Int,
    max: Int,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = editable,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .keepsKeyboard()
            .testTag(tag),
        shape = RoundedCornerShape(12.dp),
        minLines = 2,
        maxLines = 5,
        isError = length > max,
        supportingText = {
            Text(stringResource(R.string.calls_task_limit, length, max))
        },
    )
}

@Composable
private fun CallTaskSimDialog(
    options: List<CallSim>,
    preferred: String?,
    selectedOverride: String?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val remembered = options.firstOrNull { it.id == preferred }
    val platformDefault = options.singleOrNull { it.isDefault }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_sim)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
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
                        .background(c.fill, RoundedCornerShape(12.dp))
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
                        .background(c.fill, RoundedCornerShape(12.dp))
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
                            val shape = RoundedCornerShape(12.dp)
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
