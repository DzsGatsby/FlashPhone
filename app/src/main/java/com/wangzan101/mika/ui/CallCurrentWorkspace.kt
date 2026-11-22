package com.wangzan101.mika.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.theme.FlashPhoneColors
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.flow.collect

private val LiveWash = FlashPhoneColors.ConversationBackdrop
private val LiveInk = FlashPhoneColors.Ink
private val LiveMuted = FlashPhoneColors.Secondary
private val LiveGreen = FlashPhoneColors.ConversationGreen
private val LiveGreenInk = FlashPhoneColors.ConversationInk
private val LiveBorder = FlashPhoneColors.Line
private val LiveAction = FlashPhoneColors.Green
private val LiveDanger = Color(0xFF942D27)
private val LiveDangerSoft = Color(0xFFFFEEEB)

/** The expanded call owns its remaining viewport; controls never belong to the transcript scroller. */
@Composable
internal fun CurrentCallCard(
    state: CallRuntimeState,
    feedback: CallActionResult?,
    canAnswer: Boolean,
    canControl: Boolean,
    modifier: Modifier = Modifier,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onTakeover: () -> Unit,
    onCancelAuto: () -> Unit,
    onTranscript: () -> Unit,
    onSendDtmf: (suspend (String, String) -> CallActionResult)? = null,
    expanded: Boolean = true,
    onCollapse: (() -> Unit)? = null,
    task: CallTaskBrief? = null,
) {
    val screen = LocalConfiguration.current
    val compact = screen.screenHeightDp < 500 || screen.fontScale >= 1.5f
    var transcriptRequest by rememberSaveable(state.callId) { mutableIntStateOf(0) }
    // Keep the same viewport while the live detail is temporarily collapsed.
    val transcriptViewport = rememberLiveTranscriptViewport(state.callId)
    var keypadCallId by remember { mutableStateOf<String?>(null) }
    var showTask by rememberSaveable(state.callId) { mutableStateOf(false) }
    val canUseKeypad = state.phase == CallPhase.ACTIVE && state.canSendDtmf && state.callId != null && onSendDtmf != null
    LaunchedEffect(state.callId, state.phase, canUseKeypad) {
        if (!canUseKeypad || keypadCallId != state.callId) keypadCallId = null
    }
    if (canUseKeypad && keypadCallId == state.callId) {
        // Changing calls removes the dialog and cancels any in-flight input for the old identity.
        key(keypadCallId) {
            CallDtmfKeypadDialog(
                callId = checkNotNull(keypadCallId),
                externallyBusy = state.dtmfBusy,
                onDismiss = { keypadCallId = null },
                onSend = checkNotNull(onSendDtmf),
            )
        }
    }
    if (showTask && task != null) {
        AlertDialog(
            onDismissRequest = { showTask = false },
            title = { Text(stringResource(R.string.calls_record_brief)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(task.goal)
                    task.questions.filter(String::isNotBlank).forEach { Text("• $it") }
                    if (task.context.isNotBlank()) Text(task.context)
                    if (task.constraints.isNotBlank()) Text(task.constraints)
                }
            },
            confirmButton = { TextButton(onClick = { showTask = false }) { Text(stringResource(R.string.calls_collapse)) } },
        )
    }
    val cancelAuto = state.autoAnswerStage == CallAutoAnswerStage.COUNTDOWN ||
        state.autoAnswerStage == CallAutoAnswerStage.PREPARING
    val ringing = state.phase == CallPhase.RINGING && state.autoAnswerStage != CallAutoAnswerStage.ANSWERING
    val cancelManual = ringing && state.canTakeOver && !cancelAuto
    val showTakeover = state.canTakeOver && !cancelAuto && !ringing
    val phase = when (state.autoAnswerStage) {
        CallAutoAnswerStage.COUNTDOWN -> state.autoAnswerRemainingSeconds?.let {
            stringResource(R.string.calls_auto_countdown, it)
        } ?: stringResource(R.string.calls_answer_pending)
        CallAutoAnswerStage.PREPARING -> stringResource(R.string.calls_auto_preparing)
        CallAutoAnswerStage.ANSWERING -> stringResource(R.string.calls_auto_answering)
        else -> if (state.aiActive) stringResource(R.string.calls_ai_active) else phaseLabel(state.phase)
    }
    val party = state.number?.takeIf(String::isNotBlank)?.let { "\u2066$it\u2069" }
        ?: stringResource(R.string.calls_unknown_number)
    val openTranscript = { transcriptRequest++; onTranscript() }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!compact) Box(Modifier.size(if (expanded) 38.dp else 32.dp).background(LiveGreen, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(DialerIcons.Handset, null, Modifier.size(20.dp), tint = LiveGreenInk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(party, color = LiveInk, fontSize = if (expanded) 19.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.phase == CallPhase.ACTIVE) Box(Modifier.size(6.dp).background(LiveAction, CircleShape))
                    Text(listOfNotNull(phase, state.direction?.let { directionLabel(it) }).joinToString(" · "),
                        color = if (state.aiActive) LiveAction else LiveMuted, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("call-current-status").semantics {
                            if (state.autoAnswerStage != CallAutoAnswerStage.COUNTDOWN) liveRegion = LiveRegionMode.Polite
                        })
                }
                if (!expanded) state.liveTranscript.lastOrNull { it.text.isNotBlank() }?.let { latest ->
                    Row(Modifier.fillMaxWidth().testTag("call-current-latest"),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(callTranscriptSpeakerLabel(latest.role)), color = LiveMuted, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                        Text(latest.text, color = LiveInk, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                state.detail?.takeIf { (expanded || state.liveTranscript.none { line -> line.text.isNotBlank() }) && !compact && it.isNotBlank() && it != phase }?.let {
                    Text(it, color = LiveMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (canUseKeypad) IconButton(
                onClick = { keypadCallId = state.callId }, enabled = canControl && !state.dtmfBusy,
                modifier = Modifier.size(48.dp).testTag("call-dtmf-open"),
            ) { Icon(MikaIcons.Apps, stringResource(R.string.calls_dtmf_open), Modifier.size(20.dp), tint = LiveMuted) }
            if (!expanded || compact) IconButton(onClick = openTranscript,
                modifier = Modifier.size(48.dp).testTag("call-current-transcript")) {
                Icon(MikaIcons.Message, stringResource(R.string.calls_live_transcript), Modifier.size(20.dp), tint = LiveAction)
            }
        }
    }
    Column(modifier.then(if (expanded) Modifier.fillMaxHeight() else Modifier).testTag("call-current"),
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)) {
        if (expanded) {
            if (onCollapse != null) {
                TextButton(onClick = onCollapse, modifier = Modifier.heightIn(min = 48.dp).testTag("call-current-collapse"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    Icon(MikaIcons.Back, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.calls_collapse), fontSize = 13.sp)
                }
            }
            Surface(color = FlashPhoneColors.Fill, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { header() }
            }
            Column(Modifier.weight(1f).fillMaxWidth().background(LiveWash, RoundedCornerShape(22.dp))
                .padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                task?.takeIf { it.goal.isNotBlank() }?.let { brief ->
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp))
                        .border(1.dp, LiveBorder, RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.calls_record_brief)) { showTask = true }
                        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 10.dp)
                        .testTag("call-current-task"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!compact) Text(stringResource(R.string.calls_record_brief), fontSize = 12.sp, color = LiveMuted)
                        Text(brief.goal, fontSize = 14.sp, color = LiveInk, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().background(Color.White, RoundedCornerShape(23.dp))
                    .border(1.dp, LiveBorder, RoundedCornerShape(23.dp))
                    .padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)) {
                    LiveCallTranscript(state.callId, state.liveTranscript, transcriptRequest, compact,
                        Modifier.weight(1f), showHeading = !compact, fillViewport = true, viewport = transcriptViewport, onExpand = {
                            transcriptRequest++
                            onTranscript()
                        })
                }
            }
        } else {
            Surface(color = FlashPhoneColors.Fill, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.calls_live_transcript),
                    onClick = openTranscript).padding(horizontal = 12.dp, vertical = 6.dp)) { header() }
            }
        }
        // These controls are siblings of the stage, so long text can only scroll inside its own viewport.
        if (ringing || showTakeover || cancelAuto || state.canHangUp) {
            Row(Modifier.fillMaxWidth().testTag("call-current-controls"), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (ringing || showTakeover) {
                    val action = when {
                        cancelManual -> Triple(R.string.calls_cancel_ai_answer, onTakeover, "call-cancel-ai-answer")
                        showTakeover -> Triple(R.string.calls_takeover, onTakeover, "call-takeover")
                        else -> Triple(R.string.calls_answer, onAnswer, "call-answer")
                    }
                    Button(onClick = action.second, enabled = if (cancelManual || showTakeover) canControl else canAnswer,
                        modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag(action.third),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cancelManual) FlashPhoneColors.GreenSoft else LiveAction,
                            contentColor = if (cancelManual) LiveAction else Color.White,
                        )) {
                        Text(stringResource(action.first), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
                if (cancelAuto) Button(onClick = onCancelAuto, enabled = canControl,
                    modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag("call-cancel-auto"),
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FlashPhoneColors.GreenSoft, contentColor = LiveAction)) {
                    Text(stringResource(R.string.calls_auto_cancel), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
                if (state.canHangUp) Button(onClick = onHangup, enabled = canControl,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("call-hangup"),
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LiveDangerSoft, contentColor = LiveDanger)) {
                    Text(stringResource(R.string.calls_hangup), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
        }
        feedback?.let { result ->
            Text(result.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 11.sp,
                color = if (result.ok) LiveMuted else LiveDanger, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Reading position and follow mode belong to a call, including across collapse and recreation. */
@Stable
internal class LiveTranscriptViewport(
    scrollPosition: Int = 0,
    expandedInitially: Boolean = false,
    initializedInitially: Boolean = false,
    followingInitially: Boolean = true,
    contentInitially: Int = 0,
) {
    val scroll = ScrollState(scrollPosition)
    var expanded by mutableStateOf(expandedInitially)
    var initialized by mutableStateOf(initializedInitially)
    var following by mutableStateOf(followingInitially)
    var contentSignature by mutableIntStateOf(contentInitially)

    companion object {
        val Saver = listSaver<LiveTranscriptViewport, Any>(
            save = { listOf(it.scroll.value, it.expanded, it.initialized, it.following, it.contentSignature) },
            restore = { LiveTranscriptViewport(it[0] as Int, it[1] as Boolean, it[2] as Boolean, it[3] as Boolean, it[4] as Int) },
        )
    }
}

@Composable
private fun rememberLiveTranscriptViewport(callId: String?): LiveTranscriptViewport =
    rememberSaveable(callId, saver = LiveTranscriptViewport.Saver) { LiveTranscriptViewport() }

@Composable
internal fun LiveCallTranscript(
    callId: String?,
    lines: List<CallTranscriptLine>,
    expandRequest: Int = 0,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
    onExpand: (() -> Unit)? = null,
    fillViewport: Boolean = false,
    viewport: LiveTranscriptViewport? = null,
) {
    val reading = viewport ?: rememberLiveTranscriptViewport(callId)
    var expanded by reading::expanded
    val scroll = reading.scroll
    LaunchedEffect(callId, expandRequest) { if (expandRequest > 0) expanded = true }
    // A growing transcript must not change the follow decision using the *new* scroll range.
    // Only movement within an unchanged range represents the reader leaving/returning to the end.
    LaunchedEffect(reading) {
        var previousMaximum = scroll.maxValue
        var previousPosition = scroll.value
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (position, maximum) ->
            if (maximum == previousMaximum && position != previousPosition) {
                reading.following = maximum - position <= 24
            }
            previousMaximum = maximum
            previousPosition = position
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showHeading) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calls_live_transcript), color = LiveInk, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            if (lines.size > 3) TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Text(stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand), fontSize = 12.sp)
            }
            if (onExpand != null) IconButton(onClick = onExpand, modifier = Modifier.size(48.dp).testTag("call-current-transcript")) {
                Icon(MikaIcons.Message, stringResource(R.string.calls_live_transcript), Modifier.size(16.dp), tint = LiveAction)
            }
        }
        val visibleLines = if (expanded) lines else lines.takeLast(if (compact) 1 else 3)
        val contentSignature = visibleLines.hashCode()
        LaunchedEffect(reading, contentSignature) {
            val newContent = !reading.initialized || reading.contentSignature != contentSignature
            reading.contentSignature = contentSignature
            reading.initialized = true
            if (newContent && reading.following) {
                // Wait for the new bubbles to be measured. Layout-only changes and restored content
                // never enter this branch, so rotation cannot overwrite a restored reading offset.
                withFrameNanos { }
                withFrameNanos { }
                if (reading.following) scroll.scrollTo(scroll.maxValue)
            }
        }
        Column(Modifier.then(if (fillViewport) Modifier.weight(1f) else Modifier.heightIn(max = if (compact) 80.dp else 160.dp)).fillMaxWidth()
            .background(LiveWash, RoundedCornerShape(17.dp)).verticalScroll(scroll)
            .padding(if (compact) 8.dp else 10.dp).testTag("call-live-transcript"),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (visibleLines.isEmpty()) Text(stringResource(R.string.calls_live_waiting), color = LiveMuted, fontSize = 12.sp,
                modifier = Modifier.padding(4.dp))
            visibleLines.forEach { line ->
                val assistant = line.role == "assistant"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (assistant) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(callTranscriptSpeakerLabel(line.role)), color = LiveMuted, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 3.dp))
                    Box(Modifier.fillMaxWidth(.91f)
                        .background(if (assistant) LiveGreen else Color.White,
                            if (assistant) RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
                            else RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp)) {
                        Text(line.text, color = if (assistant) LiveGreenInk else LiveInk, fontSize = 14.sp, lineHeight = 23.sp)
                    }
                }
            }
        }
    }
}
