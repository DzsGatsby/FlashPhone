package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The current call and receiving controls come first; the rest expands without changing pages. */
@Composable
fun CallSettingsScreen(
    runtime: CallRuntime,
    onInstructions: (() -> Unit)? = null,
    onBack: () -> Unit,
    standalone: Boolean = false,
    onHelperSetup: () -> Unit = {},
    onAbout: () -> Unit = {},
    updateAvailable: Boolean = false,
    focusRequest: Int = 0,
) {
    var voiceCloneOpen by rememberSaveable { mutableStateOf(false) }
    var instructionsOpen by rememberSaveable { mutableStateOf(false) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    val workspaceState = rememberSaveableStateHolder()
    val taskDraft = rememberSaveable(saver = CallTaskDraft.Saver) { CallTaskDraft() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            voiceCloneOpen = false
            instructionsOpen = false
            handledFocusRequest = focusRequest
        }
    }
    when {
        voiceCloneOpen -> VoiceCloneScreen(runtime) { voiceCloneOpen = false }
        instructionsOpen -> CallInstructionsScreen(runtime) { instructionsOpen = false }
        else -> workspaceState.SaveableStateProvider("call-workspace") {
            CallSettingsWorkspace(
                runtime = runtime,
                taskDraft = taskDraft,
                onBack = onBack,
                onInstructions = onInstructions ?: { instructionsOpen = true },
                standalone = standalone,
                onHelperSetup = onHelperSetup,
                onAbout = onAbout,
                updateAvailable = updateAvailable,
                focusRequest = focusRequest,
            ) {
                voiceCloneOpen = true
            }
        }
    }
}

@Composable
private fun CallSettingsWorkspace(
    runtime: CallRuntime,
    taskDraft: CallTaskDraft,
    onBack: () -> Unit,
    onInstructions: () -> Unit,
    standalone: Boolean,
    onHelperSetup: () -> Unit,
    onAbout: () -> Unit,
    updateAvailable: Boolean,
    focusRequest: Int,
    onVoiceClone: () -> Unit,
) {
    val config by runtime.settings.state.collectAsState()
    val state by runtime.state.collectAsState()
    val history by runtime.history.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var controlBusy by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var lastAction by remember { mutableIntStateOf(0) }
    var lastActionWasLockedDial by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var tab by rememberSaveable { mutableStateOf(CallWorkspaceTab.Receive) }
    val tabStates = rememberSaveableStateHolder()
    var liveNavigation by remember { mutableIntStateOf(0) }
    var handledLiveNavigation by remember { mutableIntStateOf(0) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var taskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledTaskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var deviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledDeviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var pendingReuse by remember { mutableStateOf<Pair<String, CallTaskBrief>?>(null) }
    var dialog by rememberSaveable { mutableStateOf<CallDialog?>(null) }
    val idle = state.phase == CallPhase.IDLE
    val pendingLockedDial = state.lockedDialRequestId
    val failureText = stringResource(R.string.calls_action_failed)
    val settingsErrorText = stringResource(R.string.calls_device_system_settings_failed)
    val device = rememberCallDeviceControls(runtime) { feedback = CallActionResult(false, settingsErrorText) }
    val ready = config.enabled && state.prepared && state.shellConnected && device.readiness.phoneAllowed &&
        device.readiness.callNotificationsAllowed && state.notificationsAvailable && config.credentialsConfigured && state.audioReady
    val canStartStandby = ready && config.audioVerified && config.autoAnswer && idle && pendingLockedDial == null &&
        device.readiness.standbyNotificationsAllowed && !busy && !controlBusy && !settingsBusy

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            tab = CallWorkspaceTab.Receive
            liveNavigation++
            handledFocusRequest = focusRequest
        }
    }

    fun perform(priority: Boolean = false, lockedDial: Boolean = false, action: suspend () -> CallActionResult) {
        if (if (priority) controlBusy else busy || controlBusy) return
        if (priority) controlBusy = true else busy = true
        val sequence = ++lastAction
        lastActionWasLockedDial = lockedDial
        feedback = null
        scope.launch {
            try {
                val result = action()
                if (sequence == lastAction) feedback = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (sequence == lastAction) feedback = CallActionResult(false, failureText)
            } finally {
                if (priority) controlBusy = false else busy = false
            }
        }
    }

    suspend fun save(change: (CallConfig) -> CallConfig): Boolean {
        if (settingsBusy) return false
        settingsBusy = true
        return try {
            withContext(Dispatchers.IO) {
                synchronized(runtime.settings) { runtime.settings.save(change(runtime.settings.state.value)) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = CallActionResult(false, failureText)
            false
        } finally {
            settingsBusy = false
        }
    }

    val prepare: () -> Unit = {
        perform {
            if (!runtime.settings.state.value.enabled && !save { it.copy(enabled = true) }) {
                CallActionResult(false, failureText)
            } else runtime.prepare()
        }
    }
    val startStandby: () -> Unit = { perform { runtime.startBackgroundStandby() } }
    fun reuse(number: String, brief: CallTaskBrief) {
        taskDraft.fill(number, brief)
        tab = CallWorkspaceTab.Task
        taskNavigation++
    }

    fun openDeviceSettings() {
        tab = CallWorkspaceTab.Settings
        deviceNavigation++
    }

    val receiveDot = when (state.autoAnswerStage) {
        CallAutoAnswerStage.STANDBY -> TileTone.Ok
        CallAutoAnswerStage.PAUSED, CallAutoAnswerStage.BLOCKED -> TileTone.Warn
        CallAutoAnswerStage.COUNTDOWN, CallAutoAnswerStage.PREPARING, CallAutoAnswerStage.ANSWERING -> TileTone.Accent
        else -> if (state.aiActive) TileTone.Accent else null
    }
    val taskDot = if (pendingLockedDial != null || taskDraft.hasContent) TileTone.Accent else null
    val historyDot = if (history.any { !it.handled }) TileTone.Warn else null
    val settingsDot = if (!ready || !config.audioVerified) TileTone.Warn else null

    KeyboardDismissArea {
        PageBackground(Modifier.testTag("call-settings")) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                if (standalone) {
                    TopBar(
                        title = stringResource(R.string.app_name),
                        left = MikaIcons.Gear,
                        onLeft = ::openDeviceSettings,
                        leftLabel = stringResource(R.string.calls_tab_settings),
                        leftBadge = !ready || !config.audioVerified,
                        subtitle = stringResource(readinessSummary(config, ready)),
                        subtitleColor = when {
                            !idle || state.aiActive -> MikaTheme.colors.accent
                            ready && config.audioVerified && config.autoAnswer -> MikaTheme.colors.ok
                            !ready || !config.audioVerified -> MikaTheme.colors.warn
                            else -> MikaTheme.colors.ink3
                        },
                        right = {
                            BarIconButton(MikaIcons.Info, stringResource(R.string.about_check), onAbout, badge = updateAvailable)
                        },
                    )
                } else {
                    TopBar(stringResource(R.string.calls_title), MikaIcons.Back, onBack, stringResource(R.string.back))
                }

                if (!idle) {
                    val callId = state.callId
                    CurrentCallCard(
                        state = state,
                        feedback = feedback,
                        canAnswer = ready && !busy && !controlBusy && callId != null && state.canAnswer,
                        canControl = !controlBusy && callId != null,
                        modifier = Modifier.padding(horizontal = MikaSpace.Page),
                        onAnswer = { callId?.let { perform { runtime.answer(it) } } },
                        onHangup = { callId?.let { perform(priority = true) { runtime.hangup(it) } } },
                        onTakeover = {
                            if (callId != null) perform(priority = true) {
                                withContext(Dispatchers.Main.immediate) {
                                    val current = runtime.state.value
                                    if (current.callId == callId && current.canTakeOver) runtime.takeover()
                                    else CallActionResult(false, failureText)
                                }
                            }
                        },
                        onCancelAuto = { callId?.let { perform(priority = true) { runtime.cancelAutoAnswer(it) } } },
                        onTranscript = {
                            tab = CallWorkspaceTab.Receive
                            liveNavigation++
                        },
                    )
                }
                if (pendingLockedDial != null) {
                    CallLockedDialPendingRow(state, enabled = !controlBusy) {
                        perform(priority = true, lockedDial = true) { runtime.cancelLockedDial(pendingLockedDial) }
                    }
                }
                CallWorkspaceTabs(
                    selected = tab,
                    receiveDot = receiveDot,
                    taskDot = taskDot,
                    historyDot = historyDot,
                    settingsDot = settingsDot,
                    onSelect = { tab = it },
                )
                tabStates.SaveableStateProvider(tab.name) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(tab, liveNavigation, taskNavigation, deviceNavigation) {
                        when (tab) {
                            CallWorkspaceTab.Receive -> if (liveNavigation != handledLiveNavigation) {
                                scroll.scrollTo(0)
                                handledLiveNavigation = liveNavigation
                            }
                            CallWorkspaceTab.Task -> if (taskNavigation != handledTaskNavigation) {
                                scroll.scrollTo(0)
                                handledTaskNavigation = taskNavigation
                            }
                            CallWorkspaceTab.Settings -> if (deviceNavigation != handledDeviceNavigation) {
                                scroll.scrollTo(0)
                                handledDeviceNavigation = deviceNavigation
                            }
                            CallWorkspaceTab.History -> Unit
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(horizontal = MikaSpace.Page)
                            .padding(top = 8.dp, bottom = 24.dp)
                            .testTag("call-page-" + tab.name.lowercase()),
                    ) {
                        val visibleFeedback = feedback?.takeIf {
                            idle && !(lastActionWasLockedDial && it.ok && state.lockedDialDetail != null)
                        }
                        if (tab == CallWorkspaceTab.Task && idle && pendingLockedDial == null && visibleFeedback == null) {
                            state.lockedDialDetail?.let { CallFeedback(CallActionResult(true, it)) }
                        }
                        if (tab != CallWorkspaceTab.Task) visibleFeedback?.let { CallFeedback(it) }
                        when (tab) {
                            CallWorkspaceTab.Receive -> CallReceivePage(
                                config = config,
                                state = state,
                                ready = ready,
                                controlsEnabled = !settingsBusy && !controlBusy && (!busy || config.autoAnswer),
                                canStartStandby = canStartStandby,
                                standbyNeedsSetup = ready && !device.readiness.standbyNotificationsAllowed,
                                onSetup = ::openDeviceSettings,
                                onToggle = { checked ->
                                    scope.launch {
                                        if (save { it.copy(autoAnswer = checked) } && checked &&
                                            device.readiness.standbyNotificationsAllowed
                                        ) startStandby()
                                    }
                                },
                                onStartStandby = startStandby,
                                onRules = { dialog = CallDialog.Rules },
                                onBypass = { dialog = CallDialog.Bypass },
                                onPause = { minutes -> perform(priority = true) { runtime.pauseAutoAnswer(minutes) } },
                                onResume = { perform(priority = true) { runtime.resumeAutoAnswer() } },
                                onInstructions = onInstructions,
                            )
                            CallWorkspaceTab.Task -> CallTaskScreen(
                                draft = taskDraft,
                                config = config,
                                state = state,
                                ready = ready,
                                editable = pendingLockedDial == null && !busy && !controlBusy,
                                canDial = idle && pendingLockedDial == null && !busy && !controlBusy,
                                feedback = visibleFeedback,
                                onSetup = ::openDeviceSettings,
                                onConfirm = { submission ->
                                    perform {
                                        runtime.dial(submission.number, simId = submission.simId, task = submission.brief)
                                    }
                                },
                                onConfirmLocked = { submission ->
                                    perform(lockedDial = true) {
                                        runtime.queueLockedDial(submission.number, simId = submission.simId, task = submission.brief)
                                    }
                                },
                            )
                            CallWorkspaceTab.History -> CallHistorySection(
                                history = history,
                                historyWriteFailed = state.historyWriteFailed,
                                onMark = runtime::setHistoryHandled,
                                onDelete = runtime::deleteHistory,
                                onOpenRecording = runtime::openRecording,
                                onCloseRecording = runtime::closeRecording,
                                playbackEnabled = state.historyPlaybackAllowed && idle && pendingLockedDial == null,
                                onReuseTask = if (pendingLockedDial != null || busy || controlBusy) null else { number, brief ->
                                    if (taskDraft.hasContent) pendingReuse = number to brief else reuse(number, brief)
                                },
                            )
                            CallWorkspaceTab.Settings -> CallPreferencesPage(
                                config = config,
                                state = state,
                                ready = ready,
                                openDeviceRequest = deviceNavigation,
                                canConfigure = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy,
                                stopBusy = controlBusy || settingsBusy,
                                device = device,
                                canStartStandby = canStartStandby,
                                onPrepare = prepare,
                                onStop = { perform(priority = true) { runtime.stop() } },
                                onHelperSetup = onHelperSetup,
                                onStartStandby = startStandby,
                                onCredentials = { dialog = CallDialog.Credentials },
                                onProfile = { dialog = CallDialog.Profile },
                                onVoiceClone = onVoiceClone,
                                onVerifyAudio = { verified ->
                                    scope.launch { save { it.copy(audioVerified = verified, autoAnswer = verified && it.autoAnswer) } }
                                },
                                onDefaultSim = { id -> scope.launch { save { it.copy(defaultOutgoingSimId = id) } } },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingReuse?.let { (number, brief) ->
        AlertDialog(
            onDismissRequest = { pendingReuse = null },
            title = { Text(stringResource(R.string.calls_task_replace_title)) },
            text = { Text(stringResource(R.string.calls_task_replace_note)) },
            confirmButton = {
                TextButton(enabled = !busy && !controlBusy, onClick = { pendingReuse = null; reuse(number, brief) }) {
                    Text(stringResource(R.string.calls_task_replace_action))
                }
            },
            dismissButton = { TextButton(onClick = { pendingReuse = null }) { Text(stringResource(R.string.calls_cancel)) } },
        )
    }

    when (dialog) {
        CallDialog.Rules -> CallRulesDialog(config, onDismiss = { dialog = null }, onSave = { all, numbers, delay ->
            save { it.copy(allowAllIncoming = all, allowedIncomingNumbers = numbers, autoAnswerDelaySeconds = delay) }
        })
        CallDialog.Bypass -> CallBypassDialog(config.bypassIncomingNumbers, onDismiss = { dialog = null }) { numbers ->
            save { it.copy(bypassIncomingNumbers = numbers) }
        }
        CallDialog.Credentials -> CallCredentialsDialog(runtime, onDismiss = { dialog = null }) {
            lastActionWasLockedDial = false
            feedback = CallActionResult(true, it)
        }
        CallDialog.Profile -> CallProfileDialog(config, onDismiss = { dialog = null }, onSave = { edits ->
            save { latest ->
                require(canEditCallSpeaker(edits.speaker, latest.speaker))
                latest.copy(
                    greeting = edits.greeting,
                    systemPrompt = edits.systemPrompt,
                    speaker = edits.speaker,
                    audioMode = edits.audioMode,
                    maxCallSeconds = edits.maxCallSeconds,
                )
            }
        })
        null -> Unit
    }
}

private enum class CallDialog { Rules, Bypass, Credentials, Profile }

private enum class CallWorkspaceTab(val label: Int, val icon: ImageVector) {
    Receive(R.string.calls_nav_receive, MikaIcons.Bell),
    Task(R.string.calls_nav_task, MikaIcons.Phone),
    History(R.string.calls_tab_history, MikaIcons.Clock),
    Settings(R.string.calls_tab_settings, MikaIcons.Gear),
}

@Composable
private fun CallWorkspaceTabs(
    selected: CallWorkspaceTab,
    receiveDot: TileTone?,
    taskDot: TileTone?,
    historyDot: TileTone?,
    settingsDot: TileTone?,
    onSelect: (CallWorkspaceTab) -> Unit,
) {
    val c = MikaTheme.colors
    val outerShape = RoundedCornerShape(14.dp)
    val innerShape = RoundedCornerShape(11.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MikaSpace.Page, vertical = 4.dp)
            .background(c.fill, outerShape)
            .border(0.5.dp, c.edge, outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallWorkspaceTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val dot = when (tab) {
                CallWorkspaceTab.Receive -> receiveDot
                CallWorkspaceTab.Task -> taskDot
                CallWorkspaceTab.History -> historyDot
                CallWorkspaceTab.Settings -> settingsDot
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .cardSurface(c, innerShape)
                                .border(0.5.dp, c.accent.copy(alpha = 0.25f), innerShape)
                        } else {
                            Modifier
                        },
                    )
                    .clip(innerShape)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                    .testTag("call-tab-" + tab.name.lowercase())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (isSelected) c.accent else c.ink3,
                    )
                    Text(
                        text = stringResource(tab.label),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (isSelected) c.accent else c.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dot != null) {
                        val dotColor = when (dot) {
                            TileTone.Ok -> c.ok
                            TileTone.Warn -> c.warnSolid
                            TileTone.Accent -> c.accent
                            TileTone.Bad -> c.bad
                            TileTone.Neutral -> c.ink3
                        }
                        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentCallCard(
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
) {
    val c = MikaTheme.colors
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
    val cardTone = when {
        state.aiActive || cancelAuto -> TileTone.Accent
        ringing -> TileTone.Warn
        else -> TileTone.Ok
    }
    CallSurfaceCard(
        modifier = modifier.padding(bottom = 8.dp).testTag("call-current"),
        tone = cardTone,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconTile(MikaIcons.Phone, cardTone)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CallStatusBadge(text = phase, tone = cardTone)
                        state.direction?.let { dir ->
                            CallMetaChip(text = directionLabel(dir), tone = TileTone.Neutral)
                        }
                    }
                    val party = state.number?.let { "\u2066$it\u2069" }
                        ?: stringResource(R.string.calls_unknown_number).takeIf { state.direction == CallDirection.INCOMING }
                    if (!party.isNullOrBlank()) {
                        Text(
                            text = party,
                            style = MikaType.BodyStrong.copy(fontSize = 17.sp),
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.detail?.takeIf { it.isNotBlank() && it != phase }?.let { detail ->
                        Text(
                            text = detail,
                            style = MikaType.Caption,
                            color = c.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.aiActive || state.liveTranscript.isNotEmpty()) {
                    IconButton(
                        onClick = onTranscript,
                        modifier = Modifier
                            .background(c.accentSoft, CircleShape)
                            .testTag("call-current-transcript"),
                    ) {
                        Icon(
                            MikaIcons.Message,
                            contentDescription = stringResource(R.string.calls_live_transcript),
                            tint = c.accent,
                        )
                    }
                }
            }

            state.liveTranscript.lastOrNull()?.let { latest ->
                val speaker = stringResource(if (latest.role == "assistant") R.string.app_name else R.string.calls_caller)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "$speaker:",
                        style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold),
                        color = if (latest.role == "assistant") c.accent else c.ink2,
                    )
                    Text(
                        text = latest.text,
                        style = MikaType.Caption,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (ringing || showTakeover || cancelAuto || state.canHangUp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ringing || showTakeover) {
                        val action = when {
                            cancelManual -> Triple(R.string.calls_cancel_ai_answer, onTakeover, "call-cancel-ai-answer")
                            showTakeover -> Triple(R.string.calls_takeover, onTakeover, "call-takeover")
                            else -> Triple(R.string.calls_answer, onAnswer, "call-answer")
                        }
                        Button(
                            onClick = action.second,
                            enabled = if (cancelManual || showTakeover) canControl else canAnswer,
                            modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag(action.third),
                            shape = MikaShapes.Button,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.accent,
                                contentColor = c.onAccent,
                            ),
                        ) {
                            Text(
                                text = stringResource(action.first),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                    if (cancelAuto) {
                        OutlinedButton(
                            onClick = onCancelAuto,
                            enabled = canControl,
                            modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag("call-cancel-auto"),
                            shape = MikaShapes.Button,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.calls_auto_cancel), textAlign = TextAlign.Center)
                        }
                    }
                    if (state.canHangUp) {
                        TextButton(
                            onClick = onHangup,
                            enabled = canControl,
                            modifier = Modifier
                                .weight(0.95f)
                                .heightIn(min = 48.dp)
                                .background(if (canControl) c.badSoft else c.fill, MikaShapes.Button)
                                .testTag("call-hangup"),
                            shape = MikaShapes.Button,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.calls_hangup),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = if (canControl) MaterialTheme.colorScheme.error else c.ink3,
                            )
                        }
                    }
                }
            }
            feedback?.let {
                Text(
                    text = it.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (it.ok) c.ink3 else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LiveCallTranscript(callId: String?, lines: List<CallTranscriptLine>) {
    var expanded by rememberSaveable(callId) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTile(MikaIcons.Message, TileTone.Accent)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.calls_live_transcript), style = MikaType.BodyStrong)
            Text(
                text = if (lines.isEmpty()) stringResource(R.string.calls_live_waiting)
                else "${lines.size}",
                style = MikaType.Hint,
                color = MikaTheme.colors.ink3,
            )
        }
        if (lines.size > 3) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand))
            }
        }
    }
    if (lines.isEmpty()) {
        Text(
            text = stringResource(R.string.calls_live_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MikaTheme.colors.ink3,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else {
        Column(
            Modifier.padding(top = 6.dp).testTag("call-live-transcript"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (if (expanded) lines else lines.takeLast(3)).forEach { line ->
                CallTranscriptText(line)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallReceivePage(
    config: CallConfig,
    state: CallRuntimeState,
    ready: Boolean,
    controlsEnabled: Boolean,
    canStartStandby: Boolean,
    standbyNeedsSetup: Boolean,
    onSetup: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRules: () -> Unit,
    onBypass: () -> Unit,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
    onInstructions: () -> Unit,
    onStartStandby: () -> Unit,
) {
    val c = MikaTheme.colors
    var scopeOpen by rememberSaveable { mutableStateOf(false) }
    var importantOpen by rememberSaveable { mutableStateOf(false) }
    var pauseOpen by rememberSaveable { mutableStateOf(false) }

    if (state.aiActive || state.liveTranscript.isNotEmpty()) {
        CallSurfaceCard(
            modifier = Modifier.padding(bottom = 12.dp),
            tone = TileTone.Accent,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveCallTranscript(state.callId, state.liveTranscript)
            }
        }
    }

    val autoLabel = stringResource(R.string.calls_auto_answer)
    val canTurnOn = ready && config.audioVerified && state.phase == CallPhase.IDLE &&
        (config.allowAllIncoming || config.allowedIncomingNumbers.isNotEmpty())
    val heroTone = when (state.autoAnswerStage) {
        CallAutoAnswerStage.STANDBY -> TileTone.Ok
        CallAutoAnswerStage.PAUSED, CallAutoAnswerStage.BLOCKED -> TileTone.Warn
        CallAutoAnswerStage.COUNTDOWN, CallAutoAnswerStage.PREPARING, CallAutoAnswerStage.ANSWERING -> TileTone.Accent
        else -> if (!ready || !config.audioVerified) TileTone.Warn else TileTone.Neutral
    }

    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp),
        tone = heroTone,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(MikaIcons.Bell, heroTone)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(autoLabel, style = MikaType.BodyStrong.copy(fontSize = 16.5.sp), color = c.ink)
                    }
                    CallStatusBadge(
                        text = stringResource(readinessSummary(config, ready)),
                        tone = heroTone,
                    )
                }
                MikaSwitch(
                    checked = config.autoAnswer,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("call-auto-answer").semantics { contentDescription = autoLabel },
                    enabled = controlsEnabled && (config.autoAnswer || canTurnOn),
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.fill, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = autoAnswerSummary(config, state),
                    style = MikaType.Caption.copy(lineHeight = 19.sp),
                    color = if (state.autoAnswerStage == CallAutoAnswerStage.BLOCKED ||
                        state.autoAnswerStage == CallAutoAnswerStage.PAUSED
                    ) c.warn else c.ink2,
                )
            }

            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CallMetaChip(
                    text = rulesSummary(config),
                    icon = MikaIcons.Phone,
                    tone = if (config.allowAllIncoming || config.allowedIncomingNumbers.isNotEmpty()) TileTone.Neutral else TileTone.Warn,
                )
                CallMetaChip(
                    text = stringResource(R.string.calls_rule_delay_summary, config.autoAnswerDelaySeconds),
                    icon = MikaIcons.Clock,
                )
                if (config.bypassIncomingNumbers.isNotEmpty()) {
                    CallMetaChip(
                        text = stringResource(R.string.calls_bypass_count, config.bypassIncomingNumbers.size),
                        icon = MikaIcons.Heart,
                    )
                }
            }
        }

        if (!ready || !config.audioVerified) {
            CallCardDivider()
            ListRow(
                icon = MikaIcons.Gear,
                title = stringResource(R.string.calls_device_and_permissions),
                subtitle = stringResource(if (!ready) R.string.calls_needs_setup else R.string.calls_verify_note),
                first = true,
                tone = TileTone.Warn,
                onClick = onSetup,
            ) { RowChevron() }
        }
        if (config.autoAnswer) {
            CallCardDivider()
            CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby, onSetup.takeIf { standbyNeedsSetup })
        }
    }

    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp),
        tone = TileTone.Neutral,
    ) {
        ListRow(
            icon = MikaIcons.Doc,
            title = stringResource(R.string.calls_instructions_entry),
            subtitle = stringResource(R.string.call_instructions_empty),
            first = true,
            tone = TileTone.Accent,
            onClick = onInstructions,
        ) {
            RowChevron()
        }
    }

    CallSectionHeader(title = stringResource(R.string.calls_rules))
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp).testTag("call-incoming-rules"),
    ) {
        val hasScope = config.allowAllIncoming || config.allowedIncomingNumbers.isNotEmpty()
        CallFoldRow(
            icon = MikaIcons.Phone,
            title = stringResource(R.string.calls_rule_scope),
            expanded = scopeOpen,
            testTag = "call-rules-scope",
            subtitle = rulesSummary(config),
            tone = if (hasScope) TileTone.Neutral else TileTone.Warn,
        ) { scopeOpen = !scopeOpen }
        if (scopeOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .background(c.fill, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.calls_rule_delay_summary, config.autoAnswerDelaySeconds),
                    style = MikaType.Caption,
                    color = c.ink2,
                )
                OutlinedButton(
                    onClick = onRules,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("call-edit-rules"),
                    shape = MikaShapes.Button,
                ) {
                    Text(stringResource(R.string.calls_edit_rule))
                }
            }
        }
        CallCardDivider()
        CallFoldRow(
            icon = MikaIcons.Heart,
            title = stringResource(R.string.calls_bypass_title),
            expanded = importantOpen,
            testTag = "call-rules-important",
            subtitle = if (config.bypassIncomingNumbers.isEmpty()) stringResource(R.string.calls_bypass_empty)
            else stringResource(R.string.calls_bypass_count, config.bypassIncomingNumbers.size),
        ) { importantOpen = !importantOpen }
        if (importantOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .background(c.fill, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.calls_bypass_note),
                    style = MikaType.Caption,
                    color = c.ink2,
                )
                OutlinedButton(
                    onClick = onBypass,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("call-edit-bypass"),
                    shape = MikaShapes.Button,
                ) {
                    Text(stringResource(R.string.calls_edit_rule))
                }
            }
        }
        CallCardDivider()
        val paused = state.autoAnswerStage == CallAutoAnswerStage.PAUSED
        CallFoldRow(
            icon = MikaIcons.Clock,
            title = stringResource(R.string.calls_rule_pause),
            expanded = pauseOpen,
            testTag = "call-rules-pause",
            subtitle = if (paused) autoAnswerSummary(config, state) else null,
            tone = if (paused) TileTone.Warn else TileTone.Neutral,
        ) { pauseOpen = !pauseOpen }
        if (pauseOpen) {
            if (!config.autoAnswer && !paused) {
                FooterNote(stringResource(R.string.calls_pause_enable_first))
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (paused) {
                        Button(
                            onClick = onResume,
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("call-resume-auto"),
                            shape = MikaShapes.Button,
                        ) {
                            Text(stringResource(R.string.calls_auto_resume))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPause(30) },
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-pause-30"),
                            shape = MikaShapes.Button,
                        ) {
                            Text(stringResource(R.string.calls_auto_pause_30), textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = { onPause(120) },
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-pause-120"),
                            shape = MikaShapes.Button,
                        ) {
                            Text(stringResource(R.string.calls_auto_pause_120), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    FooterNote(stringResource(R.string.calls_privacy_note))
}

@Composable
private fun CallPreferencesPage(
    config: CallConfig,
    state: CallRuntimeState,
    ready: Boolean,
    openDeviceRequest: Int,
    canConfigure: Boolean,
    stopBusy: Boolean,
    device: CallDeviceControls,
    canStartStandby: Boolean,
    onPrepare: () -> Unit,
    onStop: () -> Unit,
    onCredentials: () -> Unit,
    onProfile: () -> Unit,
    onVoiceClone: () -> Unit,
    onVerifyAudio: (Boolean) -> Unit,
    onDefaultSim: (String?) -> Unit,
    onHelperSetup: () -> Unit,
    onStartStandby: () -> Unit,
) {
    val c = MikaTheme.colors
    var deviceOpen by rememberSaveable { mutableStateOf(!ready || !config.audioVerified) }
    var backgroundOpen by rememberSaveable { mutableStateOf(false) }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    var simOpen by rememberSaveable { mutableStateOf(false) }
    var lastDeviceRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openDeviceRequest) {
        if (openDeviceRequest != lastDeviceRequest) {
            deviceOpen = true
            lastDeviceRequest = openDeviceRequest
        }
    }

    val deviceTone = when {
        !config.enabled -> TileTone.Neutral
        !ready || !config.audioVerified -> TileTone.Warn
        else -> TileTone.Ok
    }
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp),
        tone = deviceTone,
    ) {
        CallFoldRow(
            icon = MikaIcons.Gear,
            title = stringResource(R.string.calls_device_and_permissions),
            expanded = deviceOpen,
            testTag = "call-settings-device",
            subtitle = stringResource(readinessSummary(config, ready)),
            tone = deviceTone,
        ) { deviceOpen = !deviceOpen }
        if (deviceOpen) {
            CallCardDivider()
            CallPermissionRows(device, state.shellConnected, canConfigure, onHelperSetup)
            CallCardDivider()
            CallReadinessActions(
                config = config,
                state = state,
                ready = ready,
                enabled = canConfigure,
                stopBusy = stopBusy,
                device = device.readiness,
                onPrepare = onPrepare,
                onStop = onStop,
                onCredentials = onCredentials,
            )
            val canVerify = canConfigure && (ready || config.audioVerified)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(c.fill, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = config.audioVerified,
                        enabled = canVerify,
                        role = Role.Checkbox,
                        onValueChange = onVerifyAudio,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(config.audioVerified, onCheckedChange = null, enabled = canVerify)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.calls_audio_verified),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    if (!config.audioVerified) {
                        Text(
                            text = stringResource(R.string.calls_verify_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.ink3,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            state.audioDetail?.let { FooterNote(it) }
        }
    }

    val bgTone = when {
        state.backgroundStandbyRunning -> TileTone.Ok
        config.autoAnswer -> TileTone.Warn
        else -> TileTone.Neutral
    }
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp),
        tone = TileTone.Neutral,
    ) {
        CallFoldRow(
            icon = MikaIcons.Bell,
            title = stringResource(R.string.calls_background_settings),
            expanded = backgroundOpen,
            testTag = "call-settings-background",
            subtitle = stringResource(backgroundStandbyStatus(config, state)),
            tone = bgTone,
        ) { backgroundOpen = !backgroundOpen }
        if (backgroundOpen) {
            CallCardDivider()
            if (config.autoAnswer) {
                CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby)
                if (!state.backgroundStandbyStarting) {
                    state.backgroundStandbyBlockedReason?.let { FooterNote(it) }
                }
            }
            CallBatterySettingsRow(device, canConfigure)
            FooterNote(stringResource(R.string.calls_background_lock_screen_note))
            if (device.readiness.callNotificationsAllowed) {
                TextButton(
                    onClick = device.callChannelAction,
                    enabled = canConfigure,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.calls_notification_settings))
                }
            }
        }
    }

    val voiceTone = if (config.credentialsConfigured) TileTone.Ok else TileTone.Warn
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp),
        tone = TileTone.Neutral,
    ) {
        CallFoldRow(
            icon = MikaIcons.Mic,
            title = stringResource(R.string.calls_voice_settings),
            expanded = voiceOpen,
            testTag = "call-settings-voice",
            subtitle = stringResource(R.string.calls_provider),
            tone = voiceTone,
        ) { voiceOpen = !voiceOpen }
        if (voiceOpen) {
            CallCardDivider()
            ListRow(
                icon = MikaIcons.Key,
                title = stringResource(R.string.calls_credentials),
                subtitle = stringResource(if (config.credentialsConfigured) R.string.calls_key_saved else R.string.calls_key_missing),
                first = true,
                tone = voiceTone,
                onClick = onCredentials.takeIf { canConfigure },
            ) { RowChevron() }
            ListRow(
                icon = MikaIcons.Message,
                title = stringResource(R.string.calls_profile),
                subtitle = stringResource(R.string.calls_model_label, config.model),
                detail = config.greeting.takeIf { it.isNotBlank() },
                onClick = onProfile.takeIf { canConfigure },
            ) { RowChevron() }
            ListRow(
                icon = MikaIcons.Mic,
                title = stringResource(R.string.voice_clone_title),
                subtitle = stringResource(R.string.voice_clone_entry),
                onClick = onVoiceClone.takeIf { canConfigure },
            ) { RowChevron() }
        }
    }

    val preferred = config.defaultOutgoingSimId
    val selected = state.simOptions.firstOrNull { it.id == preferred }
    val simUnavailable = preferred != null && selected == null
    val simSummary = when {
        selected != null -> stringResource(R.string.calls_sim_preferred, selected.label)
        preferred != null -> stringResource(R.string.calls_sim_unavailable)
        else -> stringResource(R.string.calls_sim_ask_each_time)
    }
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp),
        tone = TileTone.Neutral,
    ) {
        CallFoldRow(
            icon = MikaIcons.Phone,
            title = stringResource(R.string.calls_default_sim_settings),
            expanded = simOpen,
            testTag = "call-settings-sim",
            subtitle = simSummary,
            tone = if (simUnavailable) TileTone.Warn else TileTone.Neutral,
        ) { simOpen = !simOpen }
        if (simOpen) {
            CallCardDivider()
            RadioRow(
                title = stringResource(R.string.calls_sim_ask_each_time),
                selected = preferred == null,
                onClick = { onDefaultSim(null) },
                first = true,
                enabled = canConfigure,
            )
            state.simOptions.forEach { sim ->
                RadioRow(
                    title = sim.label,
                    selected = preferred == sim.id,
                    onClick = { onDefaultSim(sim.id) },
                    enabled = canConfigure,
                    subtitle = if (sim.isDefault) stringResource(R.string.calls_default) else null,
                )
            }
        }
    }
}

@Composable
private fun CallReadinessActions(
    config: CallConfig,
    state: CallRuntimeState,
    ready: Boolean,
    enabled: Boolean,
    stopBusy: Boolean,
    device: CallDeviceReadiness,
    onPrepare: () -> Unit,
    onStop: () -> Unit,
    onCredentials: () -> Unit,
) {
    val canPrepare = state.shellConnected && device.phoneAllowed && device.callNotificationsAllowed
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("call-readiness")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when {
                !config.enabled -> stringResource(R.string.calls_system_default)
                ready -> stringResource(R.string.calls_limits, config.maxCallSeconds)
                else -> stringResource(R.string.calls_needs_setup)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MikaTheme.colors.ink3,
        )
        if (canPrepare && state.prepared && !config.credentialsConfigured) {
            CallPrimaryButton(stringResource(R.string.calls_credentials), enabled, onCredentials)
        } else if (canPrepare && !ready) {
            CallPrimaryButton(
                stringResource(if (config.enabled) R.string.calls_prepare else R.string.calls_enable),
                enabled,
                onPrepare,
                Modifier.testTag("call-enable"),
            )
        }
        if (config.enabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready) {
                    OutlinedButton(
                        onClick = onPrepare,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = MikaShapes.Button,
                    ) {
                        Text(stringResource(R.string.calls_prepare))
                    }
                }
                TextButton(
                    onClick = onStop,
                    enabled = !stopBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-disable"),
                    shape = MikaShapes.Button,
                ) {
                    Text(
                        text = stringResource(R.string.calls_disable_short),
                        color = if (!stopBusy) MaterialTheme.colorScheme.error else MikaTheme.colors.ink3,
                    )
                }
            }
        }
    }
}

private fun readinessSummary(config: CallConfig, ready: Boolean): Int = when {
    !config.enabled -> R.string.calls_disabled
    !ready -> R.string.calls_needs_setup
    !config.audioVerified -> R.string.calls_device_ready_to_test
    else -> R.string.calls_ready
}

@Composable
private fun autoAnswerSummary(config: CallConfig, state: CallRuntimeState): String = when (state.autoAnswerStage) {
    CallAutoAnswerStage.OFF -> stringResource(if (config.enabled) R.string.calls_auto_off else R.string.calls_system_default)
    CallAutoAnswerStage.BLOCKED -> state.autoAnswerBlockedReason ?: stringResource(R.string.calls_needs_setup)
    CallAutoAnswerStage.STANDBY -> stringResource(R.string.calls_auto_standby, config.autoAnswerDelaySeconds)
    CallAutoAnswerStage.BUSY -> stringResource(R.string.calls_auto_busy)
    CallAutoAnswerStage.PAUSED -> {
        val until = config.autoAnswerPausedUntilMs
        if (until == null) stringResource(R.string.calls_auto_paused)
        else {
            val locale = LocalContext.current.resources.configuration.locales[0]
            val formatter = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
            stringResource(R.string.calls_auto_paused_until, formatter.format(Date(until)))
        }
    }
    CallAutoAnswerStage.COUNTDOWN, CallAutoAnswerStage.PREPARING, CallAutoAnswerStage.ANSWERING ->
        stringResource(R.string.calls_answer_pending)
}

@Composable
private fun rulesSummary(config: CallConfig): String = when {
    config.allowAllIncoming -> stringResource(R.string.calls_all_note)
    config.allowedIncomingNumbers.isEmpty() -> stringResource(R.string.calls_allowlist_empty)
    else -> stringResource(R.string.calls_list_count, config.allowedIncomingNumbers.size)
}

@Composable
internal fun CallPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = MikaShapes.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = MikaTheme.colors.accent,
            contentColor = MikaTheme.colors.onAccent,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
internal fun directionLabel(direction: CallDirection): String = stringResource(
    if (direction == CallDirection.INCOMING) R.string.calls_direction_in else R.string.calls_direction_out,
)

@Composable
private fun phaseLabel(phase: CallPhase): String = stringResource(
    when (phase) {
        CallPhase.DIALING -> R.string.calls_dialing
        CallPhase.RINGING -> R.string.calls_ringing
        CallPhase.CONNECTING -> R.string.calls_connecting
        CallPhase.ACTIVE -> R.string.calls_connected
        CallPhase.HOLDING -> R.string.calls_holding
        CallPhase.DISCONNECTING -> R.string.calls_disconnecting
        CallPhase.UNKNOWN -> R.string.calls_system_active
        CallPhase.IDLE -> R.string.calls_no_current
    },
)

@Composable
private fun CallRulesDialog(
    config: CallConfig,
    onDismiss: () -> Unit,
    onSave: suspend (Boolean, Set<String>, Int) -> Boolean,
) {
    var all by rememberSaveable { mutableStateOf(config.allowAllIncoming) }
    var numbers by rememberSaveable { mutableStateOf(config.allowedIncomingNumbers.sorted().joinToString("\n")) }
    var delay by rememberSaveable { mutableStateOf(config.autoAnswerDelaySeconds.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalized = parseCallNumberList(numbers)
    val validNumbers = normalized != null
    val minDelay = CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS
    val maxDelay = CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS
    val seconds = delay.toIntOrNull()?.takeIf { it in minDelay..maxDelay }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_rules)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RadioRow(stringResource(R.string.calls_only_list), !all, { all = false }, first = true, enabled = !busy)
                RadioRow(
                    stringResource(R.string.calls_all_incoming),
                    all,
                    { all = true },
                    subtitle = stringResource(R.string.calls_all_note),
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = numbers,
                    onValueChange = { numbers = it; error = false },
                    modifier = Modifier.fillMaxWidth().testTag("call-allowlist"),
                    label = { Text(stringResource(R.string.calls_allowlist)) },
                    supportingText = {
                        Text(
                            if (validNumbers) stringResource(R.string.calls_allowlist_hint)
                            else stringResource(R.string.calls_allowlist_invalid, CallPolicy.MAX_ALLOWED_NUMBERS),
                        )
                    },
                    textStyle = TextStyle(textDirection = TextDirection.Ltr),
                    isError = !validNumbers,
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = delay,
                    onValueChange = { delay = it.filter(Char::isDigit).take(2); error = false },
                    modifier = Modifier.fillMaxWidth().testTag("call-answer-delay"),
                    label = { Text(stringResource(R.string.calls_delay_field, minDelay, maxDelay)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = delay.isNotEmpty() && seconds == null,
                    enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && validNumbers && seconds != null,
                onClick = {
                    val duration = seconds ?: return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            if (onSave(all, normalized ?: return@launch, duration)) onDismiss() else error = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) }
        },
    )
}

@Composable
private fun CallCredentialsDialog(runtime: CallRuntime, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var legacy by remember { mutableStateOf(false) }
    // Sensitive fields never enter saved instance state.
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.calls_key_saved)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_credentials)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.calls_credentials_note))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(legacy, enabled = !busy, role = Role.Checkbox) {
                            legacy = it; key = ""; appId = ""; error = false
                        }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(legacy, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.calls_legacy_credentials))
                }
                if (legacy) {
                    OutlinedTextField(
                        value = appId,
                        onValueChange = { appId = it; error = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("App ID") },
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (legacy) "Access Key" else "API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && key.isNotBlank() && (!legacy || appId.isNotBlank()),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                runtime.settings.saveCredentials(
                                    if (legacy) appId else "",
                                    if (legacy) key else "",
                                    if (legacy) "" else key,
                                )
                            }
                            key = ""; appId = ""
                            onResult(saved)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) }
        },
    )
}

@Composable
private fun CallProfileDialog(config: CallConfig, onDismiss: () -> Unit, onSave: suspend (CallConfig) -> Boolean) {
    var greeting by rememberSaveable { mutableStateOf(config.greeting) }
    var prompt by rememberSaveable { mutableStateOf(config.systemPrompt) }
    var seconds by rememberSaveable { mutableStateOf(config.maxCallSeconds.toString()) }
    var speaker by rememberSaveable { mutableStateOf(config.speaker) }
    var mode by rememberSaveable { mutableStateOf(config.audioMode) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duration = seconds.toIntOrNull()?.takeIf { it in 15..CallPolicy.MAX_SECONDS }
    val allowedVoice = canEditCallSpeaker(speaker, config.speaker)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_profile)) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_greeting)) },
                    maxLines = 3,
                    enabled = !busy,
                    supportingText = { Text(stringResource(R.string.calls_greeting_hint)) },
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_instructions)) },
                    maxLines = 5,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_duration)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = speaker,
                    onValueChange = { speaker = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_speaker)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = !allowedVoice,
                    supportingText = { Text(stringResource(R.string.calls_speaker_edit_note)) },
                )
                Text(stringResource(R.string.calls_audio_route), style = MaterialTheme.typography.labelLarge)
                CallAudioMode.entries.forEachIndexed { i, option ->
                    RadioRow(
                        title = stringResource(
                            if (option == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect,
                        ),
                        selected = mode == option,
                        onClick = { mode = option },
                        first = i == 0,
                        enabled = !busy,
                    )
                }
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = duration != null && allowedVoice && !busy,
                onClick = {
                    val maxSeconds = duration ?: return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            if (
                                onSave(
                                    config.copy(
                                        greeting = greeting,
                                        systemPrompt = prompt,
                                        speaker = speaker,
                                        audioMode = mode,
                                        maxCallSeconds = maxSeconds,
                                    ),
                                )
                            ) onDismiss() else error = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) }
        },
    )
}

private fun canEditCallSpeaker(candidate: String, current: String): Boolean {
    val speaker = candidate.trim()
    return !speaker.startsWith("S_", ignoreCase = true) || speaker == current
}

private fun parseCallNumberList(input: String): Set<String>? {
    val entries = input.split(',', '，', '،', ';', '；', '\n').map(String::trim).filter(String::isNotEmpty)
    val normalized = entries.map { CallPolicy.incomingNumber(it) ?: return null }.toSet()
    return normalized.takeIf { it.size <= CallPolicy.MAX_ALLOWED_NUMBERS }
}

@Composable
private fun CallBypassDialog(existing: Set<String>, onDismiss: () -> Unit, onSave: suspend (Set<String>) -> Boolean) {
    var numbers by rememberSaveable { mutableStateOf(existing.sorted().joinToString("\n")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalized = parseCallNumberList(numbers)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_bypass_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.calls_bypass_note))
                OutlinedTextField(
                    value = numbers,
                    onValueChange = { numbers = it; error = false },
                    label = { Text(stringResource(R.string.calls_number)) },
                    modifier = Modifier.fillMaxWidth().testTag("call-bypass-numbers"),
                    supportingText = {
                        Text(
                            if (normalized != null) stringResource(R.string.calls_allowlist_hint)
                            else stringResource(R.string.calls_allowlist_invalid, CallPolicy.MAX_ALLOWED_NUMBERS),
                        )
                    },
                    textStyle = TextStyle(textDirection = TextDirection.Ltr),
                    isError = normalized == null,
                    minLines = 2,
                    maxLines = 6,
                    enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && normalized != null,
                onClick = {
                    val next = normalized ?: return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            if (onSave(next)) onDismiss() else error = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) }
        },
    )
}
