package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
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

/** Native phone workspace. Runtime state owns every call and readiness indicator. */
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
    val liveState by runtime.state.collectAsState()
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    var voiceCloneOpen by rememberSaveable { mutableStateOf(false) }
    var instructionsOpen by rememberSaveable { mutableStateOf(false) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    val workspaceState = rememberSaveableStateHolder()
    val taskDraft = rememberPersistentCallTaskDraft()
    LaunchedEffect(liveState.phase, liveState.callId) {
        if (liveState.phase != CallPhase.IDLE) {
            remoteOpen = false
            voiceCloneOpen = false
            instructionsOpen = false
        }
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            remoteOpen = false
            voiceCloneOpen = false
            instructionsOpen = false
            handledFocusRequest = focusRequest
        }
    }
    when {
        remoteOpen -> com.wangzan101.mika.calls.remote.CallDeviceSettingsScreen { remoteOpen = false }
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
                onRemote = { remoteOpen = true },
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
    onRemote: () -> Unit,
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
    var tab by rememberSaveable { mutableStateOf(CallWorkspaceTab.Home) }
    var lastDailyTab by rememberSaveable { mutableStateOf(CallWorkspaceTab.Home) }
    val tabStates = rememberSaveableStateHolder()
    var liveNavigation by remember { mutableIntStateOf(0) }
    var handledLiveNavigation by remember { mutableIntStateOf(0) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var taskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledTaskNavigation by remember { mutableIntStateOf(0) }
    var deviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledDeviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var requestedHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
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
            tab = CallWorkspaceTab.Home
            lastDailyTab = CallWorkspaceTab.Home
            liveNavigation++
            handledFocusRequest = focusRequest
        }
    }

    BackHandler(enabled = tab != CallWorkspaceTab.Home) {
        tab = if (tab == CallWorkspaceTab.Settings && lastDailyTab != CallWorkspaceTab.Settings) lastDailyTab else CallWorkspaceTab.Home
    }

    fun selectTab(target: CallWorkspaceTab) {
        if (target != CallWorkspaceTab.Settings) {
            lastDailyTab = target
        }
        tab = target
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
        selectTab(CallWorkspaceTab.Task)
        taskNavigation++
    }

    fun openDeviceSettings() {
        tab = CallWorkspaceTab.Settings
        deviceNavigation++
    }

    val openHistoryCount = remember(history) { history.count { !it.handled } }
    val controlsEnabled = !settingsBusy && !controlBusy && (!busy || config.autoAnswer)
    val toggleAutoAnswer: (Boolean) -> Unit = { checked ->
        scope.launch {
            if (save { it.copy(autoAnswer = checked) } && checked && device.readiness.standbyNotificationsAllowed) {
                startStandby()
            }
        }
    }

    KeyboardDismissArea {
        PageBackground(Modifier.testTag("call-settings")) {
            BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                val railNavigation = maxWidth >= 600.dp
                Row(Modifier.fillMaxSize()) {
                    if (railNavigation) {
                        FlashWorkspaceNavigation(
                            selected = tab,
                            pendingCount = openHistoryCount,
                            taskHasDraft = taskDraft.hasContent || pendingLockedDial != null,
                            needsSetup = !ready || !config.audioVerified,
                            rail = true,
                            onSelect = ::selectTab,
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        FlashWorkspaceHeader(
                            title = tab.title(),
                            standalone = standalone,
                            onBack = onBack,
                            updateAvailable = updateAvailable,
                            onAbout = onAbout,
                        )
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
                            selectTab(CallWorkspaceTab.Receive)
                            liveNavigation++
                        },
                    )
                }
                if (pendingLockedDial != null) {
                    CallLockedDialPendingRow(state, enabled = !controlBusy) {
                        perform(priority = true, lockedDial = true) { runtime.cancelLockedDial(pendingLockedDial) }
                    }
                }

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
                            CallWorkspaceTab.Home, CallWorkspaceTab.History -> Unit
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
                            CallWorkspaceTab.Home -> FlashHomeWorkspace(
                                config = config,
                                state = state,
                                ready = ready,
                                controlsEnabled = controlsEnabled,
                                history = history,
                                historyWriteFailed = state.historyWriteFailed,
                                onToggle = toggleAutoAnswer,
                                onSetup = ::openDeviceSettings,
                                onReceive = { selectTab(CallWorkspaceTab.Receive) },
                                onNewTask = { selectTab(CallWorkspaceTab.Task) },
                                onHistory = { requestedHistoryId = null; selectTab(CallWorkspaceTab.History) },
                                onRecord = { id -> requestedHistoryId = id; selectTab(CallWorkspaceTab.History) },
                            )
                            CallWorkspaceTab.Receive -> FlashGuardWorkspace(
                                runtime = runtime,
                                config = config,
                                state = state,
                                ready = ready,
                                device = device,
                                canConfigure = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy,
                                controlsEnabled = controlsEnabled,
                                canStartStandby = canStartStandby,
                                standbyNeedsSetup = ready && !device.readiness.standbyNotificationsAllowed,
                                onSetup = ::openDeviceSettings,
                                onPrepare = prepare,
                                onHelperSetup = onHelperSetup,
                                onCredentials = { dialog = CallDialog.Credentials },
                                onVerifyAudio = { verified ->
                                    scope.launch { save { it.copy(audioVerified = verified, autoAnswer = verified && it.autoAnswer) } }
                                },
                                onToggle = toggleAutoAnswer,
                                onStartStandby = startStandby,
                                onRules = { dialog = CallDialog.Rules },
                                onBypass = { dialog = CallDialog.Bypass },
                                onPause = { minutes -> perform(priority = true) { runtime.pauseAutoAnswer(minutes) } },
                                onResume = { perform(priority = true) { runtime.resumeAutoAnswer() } },
                                onProfile = { dialog = CallDialog.Profile },
                                onVoiceClone = onVoiceClone,
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
                                onDefaultSim = { id -> scope.launch { save { it.copy(defaultOutgoingSimId = id) } } },
                            )
                            CallWorkspaceTab.History -> CallHistorySection(
                                history = history,
                                initialRecordId = requestedHistoryId,
                                onInitialRecordOpened = { requestedHistoryId = null },
                                historyWriteFailed = state.historyWriteFailed,
                                onMark = runtime::setHistoryHandled,
                                onDelete = runtime::deleteHistory,
                                onOpenRecording = runtime::openRecording,
                                onCloseRecording = runtime::closeRecording,
                                playbackEnabled = state.historyPlaybackAllowed && idle && pendingLockedDial == null,
                                onClearAll = runtime::clearHistory,
                                onReuseTask = if (!taskDraft.persistenceLoaded || pendingLockedDial != null || busy || controlBusy) null else { number, brief ->
                                    if (taskDraft.hasContent) pendingReuse = number to brief else reuse(number, brief)
                                },
                            )
                            CallWorkspaceTab.Settings -> {
                                FlashPageIntro(stringResource(R.string.flash_workspace_settings), stringResource(R.string.flash_workspace_device_voice_and_background_operation))
                                CallSurfaceCard(modifier = Modifier.padding(bottom = 20.dp)) {
                                    ListRow(icon = MikaIcons.Link, title = stringResource(R.string.flash_workspace_devices_remote_tasks),
                                        subtitle = stringResource(R.string.flash_workspace_account_device_connection_and_phone_access),
                                        first = true, onClick = onRemote.takeIf { idle && pendingLockedDial == null }, modifier = Modifier.testTag("call-open-remote")) { RowChevron() }
                                    CallCardDivider()
                                    ListRow(icon = MikaIcons.Info, title = stringResource(R.string.flash_workspace_about_updates),
                                        subtitle = if (updateAvailable) stringResource(R.string.flash_workspace_an_update_is_available) else "Flash phone",
                                        onClick = onAbout, modifier = Modifier.testTag("call-open-about")) { RowChevron() }
                                }
                                FlashSystemCenter(
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
                        if (!railNavigation) {
                            FlashWorkspaceNavigation(
                                selected = tab,
                                pendingCount = openHistoryCount,
                                taskHasDraft = taskDraft.hasContent || pendingLockedDial != null,
                                needsSetup = !ready || !config.audioVerified,
                                rail = false,
                                onSelect = ::selectTab,
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
) {
    val c = MikaTheme.colors
    val screen = LocalConfiguration.current
    val compact = screen.screenHeightDp < 500 || screen.fontScale >= 1.5f
    var transcriptRequest by rememberSaveable(state.callId) { mutableIntStateOf(0) }
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
                if (!compact) IconTile(MikaIcons.Phone, cardTone)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(listOfNotNull(phase, state.direction?.let { directionLabel(it) }).joinToString(" · "), style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    state.detail?.takeIf { !compact && it.isNotBlank() && it != phase }?.let { detail ->
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
                        onClick = {
                            transcriptRequest++
                            onTranscript()
                        },
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
            if (state.aiActive || state.liveTranscript.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.fill, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveCallTranscript(state.callId, state.liveTranscript, transcriptRequest, compact = compact)
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
private fun LiveCallTranscript(callId: String?, lines: List<CallTranscriptLine>, expandRequest: Int = 0, compact: Boolean = false) {
    var expanded by rememberSaveable(callId) { mutableStateOf(false) }
    LaunchedEffect(callId, expandRequest) {
        if (expandRequest > 0) expanded = true
    }
    if (!compact) Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Message, null, Modifier.size(16.dp), tint = MikaTheme.colors.accent)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.calls_live_transcript), style = MikaType.BodyStrong)
            Text(
                text = if (lines.isEmpty()) stringResource(R.string.calls_live_waiting)
                else stringResource(R.string.flash_audit_turns_count, lines.size),
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
            modifier = Modifier.padding(top = 2.dp),
        )
    } else {
        val visibleLines = if (expanded) lines else lines.takeLast(if (compact) 1 else 3)
        val offset = if (expanded) 0 else (lines.size - visibleLines.size).coerceAtLeast(0)
        Column(
            Modifier
                .heightIn(max = if (compact) 56.dp else 120.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp)
                .testTag("call-live-transcript"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visibleLines.forEachIndexed { idx, line ->
                CallTranscriptText(line, turnNumber = offset + idx + 1)
            }
        }
    }
}

@Composable
private fun FlashPageIntro(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold), color = c.ink)
            action?.invoke()
        }
        Text(subtitle, style = MikaType.Caption, color = c.ink3)
    }
}

@Composable
private fun FlashStandbySummary(config: CallConfig, state: CallRuntimeState, ready: Boolean,
    controlsEnabled: Boolean, onToggle: (Boolean) -> Unit, onSetup: () -> Unit) {
    val c = MikaTheme.colors
    val actuallyStandingBy = ready && config.audioVerified && config.autoAnswer && state.backgroundStandbyRunning &&
        state.autoAnswerStage == CallAutoAnswerStage.STANDBY
    val title = when {
        state.phase != CallPhase.IDLE -> stringResource(R.string.flash_workspace_call_in_progress)
        actuallyStandingBy -> stringResource(R.string.flash_workspace_ai_is_standing_by)
        state.autoAnswerStage == CallAutoAnswerStage.PAUSED -> stringResource(R.string.flash_workspace_answering_paused)
        !ready || !config.audioVerified -> stringResource(R.string.flash_workspace_finish_setup_to_stand_by)
        !config.autoAnswer -> stringResource(R.string.flash_workspace_automatic_answering_is_off)
        state.backgroundStandbyStarting -> stringResource(R.string.flash_workspace_starting_standby)
        else -> stringResource(R.string.flash_workspace_background_standby_is_stopped)
    }
    val autoLabel = stringResource(R.string.calls_auto_answer)
    val canTurnOn = ready && config.audioVerified && state.phase == CallPhase.IDLE && state.lockedDialRequestId == null &&
        (config.allowAllIncoming || config.allowedIncomingNumbers.isNotEmpty())
    Column(Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(8.dp)).border(0.5.dp, c.edge, RoundedCornerShape(8.dp))
        .padding(14.dp).testTag("call-home-standby"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink)
                Text(if (actuallyStandingBy) "${rulesSummary(config)} · ${stringResource(R.string.calls_rule_delay_summary, config.autoAnswerDelaySeconds)}"
                    else autoAnswerSummary(config, state), style = MikaType.Caption, color = c.ink3)
            }
            MikaSwitch(checked = config.autoAnswer, onCheckedChange = onToggle,
                enabled = controlsEnabled && (config.autoAnswer || canTurnOn),
                modifier = Modifier.testTag("call-auto-answer").semantics { contentDescription = autoLabel })
        }
        if (!ready || !config.audioVerified || (config.autoAnswer && !state.backgroundStandbyRunning && !state.backgroundStandbyStarting)) {
            TextButton(onSetup, modifier = Modifier.testTag("call-open-system"), contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.flash_workspace_review_device_standby_setup))
                Icon(MikaIcons.Chevron, null, Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashHomeWorkspace(
    config: CallConfig,
    state: CallRuntimeState,
    ready: Boolean,
    controlsEnabled: Boolean,
    history: List<CallRecord>,
    historyWriteFailed: Boolean,
    onToggle: (Boolean) -> Unit,
    onSetup: () -> Unit,
    onReceive: () -> Unit,
    onNewTask: () -> Unit,
    onHistory: () -> Unit,
    onRecord: (String) -> Unit,
) {
    val c = MikaTheme.colors
    FlashPageIntro(stringResource(R.string.flash_workspace_workspace), stringResource(R.string.flash_workspace_keep_up_with_every_important_call)) {
        Button(onClick = onNewTask, shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            modifier = Modifier.heightIn(min = 44.dp).testTag("call-home-new-task")) {
            Text(stringResource(R.string.flash_workspace_new_call), fontSize = 12.sp)
        }
    }
    FlashStandbySummary(config, state, ready, controlsEnabled, onToggle, onSetup)
    TextButton(onClick = onReceive, modifier = Modifier.fillMaxWidth().testTag("call-home-receive")) {
        Text(stringResource(R.string.flash_workspace_answering_rules_instructions), fontSize = 12.sp)
        Icon(MikaIcons.Chevron, null, Modifier.size(14.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.flash_workspace_needs_attention), Modifier.weight(1f), style = MikaType.BodyStrong, color = c.ink)
        TextButton(onClick = onHistory, modifier = Modifier.testTag("call-home-history")) {
            Text(stringResource(R.string.flash_workspace_view_all), style = MikaType.Caption, color = c.ink3)
            Icon(MikaIcons.Chevron, null, Modifier.size(14.dp), tint = c.ink3)
        }
    }
    if (historyWriteFailed) {
        CallFeedback(CallActionResult(false, stringResource(R.string.calls_history_write_failed)))
    }
    val pending = remember(history) { history.filterNot { it.handled }.sortedByDescending { it.startedAtMs } }
    if (pending.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MikaIcons.Check, null, tint = c.ink3, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.flash_workspace_no_calls_need_attention), style = MikaType.BodyStrong, color = c.ink2)
            Text(stringResource(R.string.flash_workspace_completed_calls_transcripts_and_available_recordings_appear_he),
                style = MikaType.Caption, color = c.ink3)
        }
    } else {
        val locale = LocalContext.current.resources.configuration.locales[0]
        val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
        pending.take(8).forEach { record ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { onRecord(record.id) })
                .testTag("call-home-record-${record.id}").padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(c.fill, RoundedCornerShape(7.dp)).border(0.5.dp, c.edge, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center) { Icon(MikaIcons.Phone, null, Modifier.size(17.dp), tint = c.ink3) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(record.number?.let { "\u2066$it\u2069" } ?: stringResource(R.string.calls_unknown_number),
                        style = MikaType.BodyStrong, color = c.ink)
                    Text(record.task?.goal?.takeIf { it.isNotBlank() } ?: record.result,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, style = MikaType.Caption, color = c.ink2)
                    Text(formatter.format(Date(record.startedAtMs)), style = MikaType.Hint, color = c.ink3)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(directionLabel(record.direction), style = MikaType.Hint, color = c.ink3)
                        Text(when {
                            record.recording != null -> stringResource(R.string.flash_workspace_recording)
                            record.recordingPending -> stringResource(R.string.flash_workspace_saving_recording)
                            else -> stringResource(R.string.flash_workspace_no_recording)
                        }, style = MikaType.Hint, color = c.ink3)
                        Text(stringResource(R.string.flash_workspace_to_review), style = MikaType.Hint, color = c.warn)
                    }
                }
                Icon(MikaIcons.Chevron, null, Modifier.size(14.dp), tint = c.ink3)
            }
            HorizontalDivider(color = c.edge, thickness = 0.5.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashGuardWorkspace(
    runtime: CallRuntime,
    config: CallConfig,
    state: CallRuntimeState,
    ready: Boolean,
    device: CallDeviceControls,
    canConfigure: Boolean,
    controlsEnabled: Boolean,
    canStartStandby: Boolean,
    standbyNeedsSetup: Boolean,
    onSetup: () -> Unit,
    onPrepare: () -> Unit,
    onHelperSetup: () -> Unit,
    onCredentials: () -> Unit,
    onVerifyAudio: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onRules: () -> Unit,
    onBypass: () -> Unit,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
    onProfile: () -> Unit,
    onVoiceClone: () -> Unit,
    onInstructions: () -> Unit,
    onStartStandby: () -> Unit,
) {
    val c = MikaTheme.colors
    FlashPageIntro(stringResource(R.string.flash_workspace_answering), stringResource(R.string.flash_workspace_choose_which_calls_ai_answers_and_what_it_needs_to_know))
    FlashStandbySummary(config, state, ready, controlsEnabled, onToggle, onSetup)
    Spacer(Modifier.height(16.dp))
    if (state.phase == CallPhase.IDLE && (state.aiActive || state.liveTranscript.isNotEmpty())) {
        CallSurfaceCard(modifier = Modifier.padding(bottom = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) { LiveCallTranscript(state.callId, state.liveTranscript) }
        }
    }
    if (config.autoAnswer || state.autoAnswerStage == CallAutoAnswerStage.PAUSED) {
        FlowRow(Modifier.fillMaxWidth().testTag("call-rules-pause"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.autoAnswerStage == CallAutoAnswerStage.PAUSED) {
                OutlinedButton(onResume, enabled = controlsEnabled, modifier = Modifier.testTag("call-rules-resume")) {
                    Text(stringResource(R.string.calls_auto_resume))
                }
            } else {
                OutlinedButton({ onPause(15) }, enabled = controlsEnabled) {
                    Text(stringResource(R.string.flash_workspace_pause_15_min))
                }
                OutlinedButton({ onPause(60) }, enabled = controlsEnabled) {
                    Text(stringResource(R.string.flash_workspace_pause_1_hour))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    CallSectionHeader(title = stringResource(R.string.flash_workspace_who_ai_answers))
    CallSurfaceCard(modifier = Modifier.padding(bottom = 20.dp).testTag("call-incoming-rules")) {
        ListRow(icon = MikaIcons.Phone, title = stringResource(R.string.calls_rule_scope),
            subtitle = "${rulesSummary(config)} · ${stringResource(R.string.calls_rule_delay_summary, config.autoAnswerDelaySeconds)}",
            first = true, onClick = onRules.takeIf { canConfigure }, modifier = Modifier.testTag("call-rules-scope")) { RowChevron() }
        CallCardDivider()
        ListRow(icon = MikaIcons.Heart, title = stringResource(R.string.calls_bypass_title),
            subtitle = if (config.bypassIncomingNumbers.isEmpty()) stringResource(R.string.calls_bypass_empty)
                else stringResource(R.string.calls_bypass_count, config.bypassIncomingNumbers.size),
            onClick = onBypass.takeIf { canConfigure }, modifier = Modifier.testTag("call-rules-important")) { RowChevron() }
    }
    CallSectionHeader(title = stringResource(R.string.flash_workspace_how_ai_responds))
    CallSurfaceCard(modifier = Modifier.padding(bottom = 20.dp)) {
        ListRow(icon = MikaIcons.Doc, title = stringResource(R.string.flash_workspace_answering_instructions),
            subtitle = stringResource(R.string.flash_workspace_manage_context_questions_and_limits_for_each_situation),
            first = true, onClick = onInstructions.takeIf { canConfigure }, modifier = Modifier.testTag("call-open-instructions")) { RowChevron() }
        CallCardDivider()
        ListRow(icon = MikaIcons.Message, title = stringResource(R.string.calls_profile),
            subtitle = config.greeting.takeIf { it.isNotBlank() } ?: stringResource(R.string.flash_guard_greeting_blank),
            onClick = onProfile.takeIf { canConfigure }, modifier = Modifier.testTag("call-open-profile")) { RowChevron() }
        CallCardDivider()
        ListRow(icon = MikaIcons.Mic, title = stringResource(R.string.voice_clone_title),
            subtitle = if (config.speaker != CallConfig().speaker) stringResource(R.string.flash_guard_voice_custom, config.speaker)
                else stringResource(R.string.flash_guard_voice_default),
            onClick = onVoiceClone.takeIf { canConfigure }) { RowChevron() }
    }
    if (config.autoAnswer) {
        CallSurfaceCard(modifier = Modifier.padding(bottom = 16.dp)) {
            CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby, onSetup.takeIf { standbyNeedsSetup })
        }
    }
    if (!ready || !config.audioVerified) {
        CallSurfaceCard(modifier = Modifier.padding(bottom = 16.dp)) {
            ListRow(icon = MikaIcons.Gear, title = stringResource(R.string.flash_workspace_complete_device_setup),
                subtitle = stringResource(R.string.calls_verify_note), first = true, onClick = onSetup,
                modifier = Modifier.testTag("call-receive-device")) { RowChevron() }
        }
    }
    FooterNote(stringResource(R.string.flash_privacy_recording_note))

}

@Composable
private fun FlashSystemCenter(
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

    // Pillar 1: Call Companion & 2-Way Audio Verification (Always expanded & actionable)
    val deviceTone = when {
        !config.enabled -> TileTone.Neutral
        !ready || !config.audioVerified -> TileTone.Warn
        else -> TileTone.Ok
    }
    CallSectionHeader(
        title = stringResource(R.string.flash_system_section_core),
        badge = stringResource(readinessSummary(config, ready)),
        badgeTone = deviceTone,
    )
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 16.dp).testTag("call-settings-device"),
        tone = deviceTone,
    ) {
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

    // Pillar 2: Wireless Debugging & Background Auto-Recovery (First-class visibility!)
    CallSectionHeader(title = stringResource(R.string.flash_system_section_recovery))
    PhoneCallRecoverySection()

    // Pillar 3: Background Standby & Lock-Screen Reliability
    val bgTone = when {
        state.backgroundStandbyRunning -> TileTone.Ok
        config.autoAnswer -> TileTone.Warn
        else -> TileTone.Neutral
    }
    CallSectionHeader(
        title = stringResource(R.string.flash_system_section_permissions),
        badge = stringResource(backgroundStandbyStatus(config, state)),
        badgeTone = bgTone,
    )
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 16.dp).testTag("call-settings-background"),
        tone = bgTone,
    ) {
        CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby)
        if (!state.backgroundStandbyStarting) {
            state.backgroundStandbyBlockedReason?.let { FooterNote(it) }
        }
        CallCardDivider()
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

    // Pillar 4: Realtime Voice Model Credentials & Default Outgoing SIM
    val voiceTone = if (config.credentialsConfigured) TileTone.Ok else TileTone.Warn
    CallSectionHeader(
        title = stringResource(R.string.flash_system_section_cloud),
        badge = stringResource(if (config.credentialsConfigured) R.string.calls_key_saved else R.string.calls_key_missing),
        badgeTone = voiceTone,
    )
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 16.dp).testTag("call-settings-voice"),
        tone = voiceTone,
    ) {
        ListRow(
            icon = MikaIcons.Key,
            title = stringResource(R.string.calls_credentials),
            subtitle = stringResource(if (config.credentialsConfigured) R.string.calls_key_saved else R.string.calls_key_missing),
            first = true,
            tone = voiceTone,
            onClick = onCredentials.takeIf { canConfigure },
        ) { RowChevron() }
        CallCardDivider()
        ListRow(
            icon = MikaIcons.Message,
            title = stringResource(R.string.calls_profile),
            subtitle = stringResource(R.string.calls_model_label, config.model),
            detail = config.greeting.takeIf { it.isNotBlank() },
            onClick = onProfile.takeIf { canConfigure },
        ) { RowChevron() }
        CallCardDivider()
        ListRow(
            icon = MikaIcons.Mic,
            title = stringResource(R.string.voice_clone_title),
            subtitle = stringResource(R.string.voice_clone_entry),
            onClick = onVoiceClone.takeIf { canConfigure },
        ) { RowChevron() }
    }

    val preferred = config.defaultOutgoingSimId
    val selected = state.simOptions.firstOrNull { it.id == preferred }
    val simUnavailable = preferred != null && selected == null
    val simSummary = when {
        selected != null -> stringResource(R.string.calls_sim_preferred, selected.label)
        preferred != null -> stringResource(R.string.calls_sim_unavailable)
        else -> stringResource(R.string.calls_sim_ask_each_time)
    }
    CallSectionHeader(
        title = stringResource(R.string.calls_default_sim_settings),
        badge = simSummary,
        badgeTone = if (simUnavailable) TileTone.Warn else TileTone.Neutral,
    )
    CallSurfaceCard(
        modifier = Modifier.padding(bottom = 12.dp).testTag("call-settings-sim"),
        tone = if (simUnavailable) TileTone.Warn else TileTone.Neutral,
    ) {
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
