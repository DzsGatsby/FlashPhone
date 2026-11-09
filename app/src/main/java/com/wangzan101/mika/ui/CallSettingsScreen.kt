package com.wangzan101.mika.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The current call and receiving controls come first; the rest expands without changing pages. */
@Composable
fun CallSettingsScreen(runtime: CallRuntime, onInstructions: (() -> Unit)? = null, onBack: () -> Unit) {
    var voiceCloneOpen by rememberSaveable { mutableStateOf(false) }
    var instructionsOpen by rememberSaveable { mutableStateOf(false) }
    val workspaceState = rememberSaveableStateHolder()
    when {
        voiceCloneOpen -> VoiceCloneScreen(runtime) { voiceCloneOpen = false }
        instructionsOpen -> CallInstructionsScreen(runtime) { instructionsOpen = false }
        else -> workspaceState.SaveableStateProvider("call-workspace") {
            CallSettingsWorkspace(runtime, onBack, onInstructions ?: { instructionsOpen = true }) {
                voiceCloneOpen = true
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallSettingsWorkspace(
    runtime: CallRuntime, onBack: () -> Unit, onInstructions: () -> Unit, onVoiceClone: () -> Unit,
) {
    val context = LocalContext.current
    val config by runtime.settings.state.collectAsState()
    val state by runtime.state.collectAsState()
    val history by runtime.history.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var controlBusy by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    var notificationRefreshEpoch by remember { mutableIntStateOf(0) }
    var lastAction by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var showDial by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<CallDialog?>(null) }
    val bringCurrentCall = remember { BringIntoViewRequester() }
    val idle = state.phase == CallPhase.IDLE
    val ready = config.enabled && state.prepared && state.shellConnected &&
        state.notificationsAvailable && config.credentialsConfigured && state.audioReady
    val failureText = stringResource(R.string.calls_action_failed)

    fun perform(priority: Boolean = false, action: suspend () -> CallActionResult) {
        if (if (priority) controlBusy else busy || controlBusy) return
        if (priority) controlBusy = true else busy = true
        val sequence = ++lastAction
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

    val repairNotifications = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationRefreshEpoch++
        scope.launch { runtime.refresh() }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (Build.VERSION.SDK_INT >= 33 && Manifest.permission.POST_NOTIFICATIONS in granted) {
            notificationPermissionDenied = granted[Manifest.permission.POST_NOTIFICATIONS] == false
        }
        if (granted.isNotEmpty() && granted.values.all { it }) {
            perform {
                if (!runtime.settings.state.value.enabled && !save { it.copy(enabled = true) }) {
                    CallActionResult(false, failureText)
                } else runtime.prepare()
            }
        } else feedback = CallActionResult(false, context.getString(R.string.calls_permissions_required))
    }
    val notificationsNeedSettings = remember(context, state.notificationsAvailable, notificationPermissionDenied, notificationRefreshEpoch) {
        !state.notificationsAvailable && notificationSettingsIntent(context, notificationPermissionDenied) != null
    }
    val prepare: () -> Unit = {
        val repair = notificationSettingsIntent(context, notificationPermissionDenied)
        if (repair != null) {
            try {
                repairNotifications.launch(repair)
            } catch (_: ActivityNotFoundException) {
                runCatching {
                    repairNotifications.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)))
                }.onFailure { feedback = CallActionResult(false, failureText) }
            }
        } else {
            permissions.launch(buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.CALL_PHONE)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }
    }
    LaunchedEffect(runtime) { runtime.refresh() }
    LaunchedEffect(state.callId, state.phase == CallPhase.RINGING) {
        if (state.callId != null && state.phase == CallPhase.RINGING) {
            withFrameNanos { }
            bringCurrentCall.bringIntoView()
        }
    }

    PageScaffold(stringResource(R.string.calls_title), onBack, Modifier.testTag("call-settings")) {
        if (!idle) {
            val callId = state.callId
            CurrentCallCard(
                state = state,
                canAnswer = ready && !busy && !controlBusy && callId != null && state.canAnswer,
                canControl = !controlBusy && callId != null,
                modifier = Modifier.bringIntoViewRequester(bringCurrentCall),
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
            )
        }

        CallReadinessCard(
            config = config, state = state, ready = ready, enabled = idle && !busy && !controlBusy && !settingsBusy,
            stopBusy = controlBusy || settingsBusy,
            notificationsNeedSettings = notificationsNeedSettings,
            onPrepare = prepare,
            onStop = { perform(priority = true) { runtime.stop() } },
            onCredentials = { showAdvanced = true; dialog = CallDialog.Credentials },
        )
        feedback?.takeIf { it.message != state.detail || idle }?.let {
            FooterNote(
                it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (it.ok) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error,
            )
        }

        GroupLabel(stringResource(R.string.calls_incoming))
        GroupCard(Modifier.testTag("call-incoming-rules")) {
            val automaticLabel = stringResource(R.string.calls_auto_answer)
            val canEnableAuto = ready && config.audioVerified && idle &&
                (config.allowAllIncoming || config.allowedIncomingNumbers.isNotEmpty())
            ListRow(MikaIcons.Bell, automaticLabel, autoAnswerSummary(config, state), first = true) {
                MikaSwitch(
                    config.autoAnswer,
                    { checked -> scope.launch { save { it.copy(autoAnswer = checked) } } },
                    modifier = Modifier.testTag("call-auto-answer").semantics { contentDescription = automaticLabel },
                    enabled = !settingsBusy && !controlBusy && (config.autoAnswer || canEnableAuto),
                )
            }
            if (config.autoAnswer || state.autoAnswerStage == CallAutoAnswerStage.PAUSED) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.autoAnswerStage == CallAutoAnswerStage.PAUSED) {
                        TextButton(
                            onClick = { perform(priority = true) { runtime.resumeAutoAnswer() } },
                            enabled = !controlBusy && !settingsBusy, modifier = Modifier.weight(1f).testTag("call-resume-auto"),
                        ) { Text(stringResource(R.string.calls_auto_resume)) }
                    } else {
                        TextButton(
                            onClick = { perform(priority = true) { runtime.pauseAutoAnswer(30) } },
                            enabled = !controlBusy && !settingsBusy, modifier = Modifier.weight(1f).testTag("call-pause-30"),
                        ) { Text(stringResource(R.string.calls_auto_pause_30)) }
                        TextButton(
                            onClick = { perform(priority = true) { runtime.pauseAutoAnswer(120) } },
                            enabled = !controlBusy && !settingsBusy, modifier = Modifier.weight(1f).testTag("call-pause-120"),
                        ) { Text(stringResource(R.string.calls_auto_pause_120)) }
                    }
                }
            }
            ListRow(
                MikaIcons.Edit, stringResource(R.string.calls_rules), rulesSummary(config),
                onClick = { dialog = CallDialog.Rules },
            ) { RowChevron() }
            ListRow(
                MikaIcons.Heart, stringResource(R.string.calls_bypass_title),
                if (config.bypassIncomingNumbers.isEmpty()) stringResource(R.string.calls_bypass_empty)
                else stringResource(R.string.calls_bypass_count, config.bypassIncomingNumbers.size),
                onClick = { dialog = CallDialog.Bypass },
            ) { RowChevron() }
            val verifiedLabel = stringResource(R.string.calls_audio_verified)
            val canVerify = idle && state.audioReady && !settingsBusy && !controlBusy
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(config.audioVerified, enabled = canVerify, role = Role.Checkbox) { checked ->
                        scope.launch { save { it.copy(audioVerified = checked, autoAnswer = checked && it.autoAnswer) } }
                    }.padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(config.audioVerified, onCheckedChange = null, enabled = canVerify)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(verifiedLabel, style = MaterialTheme.typography.bodyMedium)
                    if (!config.audioVerified) {
                        Text(stringResource(R.string.calls_verify_note), style = MaterialTheme.typography.bodySmall,
                            color = MikaTheme.colors.ink3)
                    }
                }
            }
        }

        GroupCard {
            ListRow(MikaIcons.Doc, stringResource(R.string.calls_instructions_entry), first = true, onClick = onInstructions) {
                RowChevron()
            }
        }

        var number by rememberSaveable { mutableStateOf("") }
        var sim by rememberSaveable { mutableStateOf<String?>(null) }
        GroupCard {
            CallFoldRow(MikaIcons.Phone, stringResource(R.string.calls_outgoing_title), showDial, testTag = "call-dial-toggle") {
                showDial = !showDial
            }
            if (showDial) {
                CallDialForm(
                    state = state, ready = ready, enabled = idle && !busy && !controlBusy,
                    number = number, onNumber = { number = it }, sim = sim, onSim = { sim = it },
                    preferredSim = config.defaultOutgoingSimId,
                    canChangeDefault = !settingsBusy && !busy && !controlBusy && idle,
                    onDefaultSim = { id -> scope.launch { save { it.copy(defaultOutgoingSimId = id) } } },
                    onDial = {
                        val requestedNumber = number
                        val requestedSim = sim
                        perform { runtime.dial(requestedNumber, requestedSim) }
                    },
                )
            }
        }

        CallHistorySection(
            history, state.historyWriteFailed, onMark = runtime::setHistoryHandled, onDelete = runtime::deleteHistory,
        )

        GroupCard {
            CallFoldRow(MikaIcons.Gear, stringResource(R.string.calls_advanced), showAdvanced, testTag = "call-advanced") {
                showAdvanced = !showAdvanced
            }
            if (showAdvanced) {
                ListRow(
                    MikaIcons.Key, stringResource(R.string.calls_credentials),
                    stringResource(if (config.credentialsConfigured) R.string.calls_key_saved else R.string.calls_key_missing),
                    onClick = if (idle && !busy && !controlBusy && !settingsBusy) ({ dialog = CallDialog.Credentials }) else null,
                ) { RowChevron() }
                ListRow(
                    MikaIcons.Message, stringResource(R.string.calls_profile),
                    stringResource(R.string.calls_model_label, config.model),
                    onClick = if (idle && !busy && !controlBusy && !settingsBusy) ({ dialog = CallDialog.Profile }) else null,
                ) { RowChevron() }
                ListRow(
                    MikaIcons.Mic, stringResource(R.string.voice_clone_title), stringResource(R.string.voice_clone_entry),
                    onClick = if (idle && !busy && !controlBusy && !settingsBusy) onVoiceClone else null,
                ) { RowChevron() }
                Text(stringResource(R.string.calls_provider), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                state.audioDetail?.let { FooterNote(it) }
            }
        }
        FooterNote(stringResource(R.string.calls_privacy_note))
    }

    when (dialog) {
        CallDialog.Rules -> CallRulesDialog(config, onDismiss = { dialog = null }, onSave = { all, numbers, delay ->
            save { it.copy(allowAllIncoming = all, allowedIncomingNumbers = numbers, autoAnswerDelaySeconds = delay) }
        })
        CallDialog.Bypass -> CallBypassDialog(config.bypassIncomingNumbers, onDismiss = { dialog = null }) { numbers ->
            save { it.copy(bypassIncomingNumbers = numbers) }
        }
        CallDialog.Credentials -> CallCredentialsDialog(runtime, onDismiss = { dialog = null }) {
            feedback = CallActionResult(true, it)
        }
        CallDialog.Profile -> CallProfileDialog(config, onDismiss = { dialog = null }, onSave = { edits ->
            save { latest ->
                require(canEditCallSpeaker(edits.speaker, latest.speaker))
                latest.copy(greeting = edits.greeting, systemPrompt = edits.systemPrompt,
                    speaker = edits.speaker, audioMode = edits.audioMode, maxCallSeconds = edits.maxCallSeconds)
            }
        })
        null -> Unit
    }
}

private fun notificationSettingsIntent(context: Context, permissionDenied: Boolean): Intent? {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return if (permissionDenied) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) else null
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return null
    return when {
        !manager.areNotificationsEnabled() -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        manager.getNotificationChannel(CallNotifications.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CallNotifications.CHANNEL)
        else -> return null
    }.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

private enum class CallDialog { Rules, Bypass, Credentials, Profile }

@Composable
private fun CurrentCallCard(
    state: CallRuntimeState,
    canAnswer: Boolean,
    canControl: Boolean,
    modifier: Modifier = Modifier,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onTakeover: () -> Unit,
    onCancelAuto: () -> Unit,
) {
    val cancelAuto = state.autoAnswerStage == CallAutoAnswerStage.COUNTDOWN ||
        state.autoAnswerStage == CallAutoAnswerStage.PREPARING
    GroupCard(modifier.testTag("call-current")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(MikaIcons.Phone, if (state.aiActive) TileTone.Accent else TileTone.Neutral)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.aiActive -> stringResource(R.string.calls_ai_active)
                            state.direction != null -> directionLabel(state.direction)
                            else -> phaseLabel(state.phase)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.number?.let { PhoneNumberText(it) }
                    if (state.number == null && state.direction == CallDirection.INCOMING) {
                        Text(stringResource(R.string.calls_unknown_number), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val phase = when (state.autoAnswerStage) {
                CallAutoAnswerStage.COUNTDOWN -> state.autoAnswerRemainingSeconds?.let {
                    stringResource(R.string.calls_auto_countdown, it)
                } ?: stringResource(R.string.calls_answer_pending)
                CallAutoAnswerStage.PREPARING -> stringResource(R.string.calls_auto_preparing)
                CallAutoAnswerStage.ANSWERING -> stringResource(R.string.calls_auto_answering)
                else -> if (state.pendingAutoAnswer) stringResource(R.string.calls_answer_pending) else phaseLabel(state.phase)
            }
            Text(phase, style = MaterialTheme.typography.bodyMedium)
            if (state.phase == CallPhase.RINGING && state.autoAnswerStage != CallAutoAnswerStage.ANSWERING) {
                if (state.canTakeOver && !cancelAuto) {
                    CallPrimaryButton(stringResource(R.string.calls_cancel_ai_answer), canControl, onTakeover,
                        Modifier.testTag("call-cancel-ai-answer"))
                } else {
                    CallPrimaryButton(stringResource(R.string.calls_answer), canAnswer, onAnswer, Modifier.testTag("call-answer"))
                }
            } else if (state.canTakeOver && !cancelAuto) {
                CallPrimaryButton(stringResource(R.string.calls_takeover), canControl, onTakeover, Modifier.testTag("call-takeover"))
            }
            if (cancelAuto || state.canHangUp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cancelAuto) {
                    OutlinedButton(onClick = onCancelAuto, enabled = canControl, modifier = Modifier.weight(1f).testTag("call-cancel-auto")) {
                        Text(stringResource(R.string.calls_auto_cancel))
                    }
                }
                if (state.canHangUp) {
                    TextButton(onClick = onHangup, enabled = canControl, modifier = Modifier.weight(1f).testTag("call-hangup")) {
                        Text(stringResource(R.string.calls_hangup), color = if (canControl) MaterialTheme.colorScheme.error else MikaTheme.colors.ink3)
                    }
                }
            }
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3) }
            if (state.aiActive || state.liveTranscript.isNotEmpty()) LiveCallTranscript(state.callId, state.liveTranscript)
        }
    }
}

@Composable
private fun LiveCallTranscript(callId: String?, lines: List<CallTranscriptLine>) {
    var expanded by rememberSaveable(callId) { mutableStateOf(false) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.calls_live_transcript), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (lines.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand))
        }
    }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.calls_live_waiting), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
    } else Column(Modifier.testTag("call-live-transcript"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (expanded) lines else lines.takeLast(3)).forEach { line ->
            CallTranscriptText(line)
        }
    }
}

@Composable
private fun CallReadinessCard(
    config: CallConfig, state: CallRuntimeState, ready: Boolean, enabled: Boolean, stopBusy: Boolean,
    notificationsNeedSettings: Boolean,
    onPrepare: () -> Unit, onStop: () -> Unit, onCredentials: () -> Unit,
) {
    GroupCard(Modifier.testTag("call-readiness")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(MikaIcons.Phone, if (ready) TileTone.Ok else if (config.enabled) TileTone.Warn else TileTone.Neutral)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(when {
                            ready -> R.string.calls_ready
                            config.enabled -> R.string.calls_needs_setup
                            else -> R.string.calls_disabled
                        }),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            notificationsNeedSettings -> stringResource(R.string.calls_permissions_required)
                            !config.enabled -> stringResource(R.string.calls_system_default)
                            !state.shellConnected -> stringResource(R.string.calls_setup_shell)
                            !state.notificationsAvailable -> stringResource(R.string.calls_permissions_required)
                            !config.credentialsConfigured -> stringResource(R.string.calls_setup_credentials)
                            !ready -> state.audioDetail ?: stringResource(R.string.calls_needs_setup)
                            else -> stringResource(R.string.calls_limits, config.maxCallSeconds)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MikaTheme.colors.ink3,
                    )
                }
            }
            if (!ready && config.enabled && !config.credentialsConfigured && state.prepared && state.shellConnected &&
                state.notificationsAvailable) {
                CallPrimaryButton(stringResource(R.string.calls_credentials), enabled, onCredentials)
            } else if (!ready) {
                val actionLabel = when {
                    notificationsNeedSettings -> R.string.calls_notification_settings
                    config.enabled -> R.string.calls_prepare
                    else -> R.string.calls_enable
                }
                CallPrimaryButton(stringResource(actionLabel), enabled, onPrepare, Modifier.testTag("call-enable"))
            }
            if (config.enabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ready) {
                        TextButton(onClick = onPrepare, enabled = enabled, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.calls_prepare))
                        }
                    }
                    TextButton(onClick = onStop, enabled = !stopBusy, modifier = Modifier.weight(1f).testTag("call-disable")) {
                        Text(stringResource(R.string.calls_disable_short))
                    }
                }
            }
        }
    }
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
private fun CallFoldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, expanded: Boolean,
    testTag: String, onClick: () -> Unit,
) {
    val expansion = stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand)
    Row(
        Modifier.fillMaxWidth().testTag(testTag).clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = expansion }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        CallExpandIcon(expanded)
    }
}

@Composable
internal fun CallExpandIcon(expanded: Boolean) {
    Icon(
        MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = MikaTheme.colors.ink3,
    )
}

@Composable
private fun CallDialForm(
    state: CallRuntimeState, ready: Boolean, enabled: Boolean, number: String, onNumber: (String) -> Unit,
    sim: String?, onSim: (String) -> Unit, preferredSim: String?, canChangeDefault: Boolean,
    onDefaultSim: (String?) -> Unit, onDial: () -> Unit,
) {
    val selected = sim ?: preferredSim
    val available = CallPolicy.chooseSim(state.simOptions, selected)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(number, onNumber, label = { Text(stringResource(R.string.calls_number)) },
            modifier = Modifier.fillMaxWidth().testTag("call-number"), singleLine = true,
            textStyle = TextStyle(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        if (state.simOptions.size > 1 || (selected != null && available == null && state.simOptions.isNotEmpty())) {
            Text(stringResource(R.string.calls_sim), style = MaterialTheme.typography.titleSmall)
            val default = state.simOptions.singleOrNull { it.isDefault }
            if (default != null) RadioRow(stringResource(R.string.calls_default), selected == "default", { onSim("default") },
                subtitle = default.label, first = true)
            state.simOptions.forEachIndexed { index, option ->
                RadioRow(option.label, selected == option.id, { onSim(option.id) }, first = index == 0 && default == null)
            }
        } else {
            state.simOptions.singleOrNull()?.let {
                Text(stringResource(R.string.calls_sim) + " · " + it.label, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (preferredSim != null) {
            val saved = state.simOptions.find { it.id == preferredSim }
            Text(
                if (saved != null) stringResource(R.string.calls_sim_preferred, saved.label)
                else stringResource(R.string.calls_sim_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MikaTheme.colors.ink3,
            )
        }
        if (available?.id != preferredSim || preferredSim != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available != null && available.id != preferredSim) {
                    TextButton(onClick = { onDefaultSim(available.id) }, enabled = canChangeDefault,
                        modifier = Modifier.weight(1f).testTag("call-save-default-sim")) {
                        Text(stringResource(R.string.calls_sim_save_preferred))
                    }
                }
                if (preferredSim != null) {
                    TextButton(onClick = { onDefaultSim(null) }, enabled = canChangeDefault,
                        modifier = Modifier.weight(1f).testTag("call-clear-default-sim")) {
                        Text(stringResource(R.string.calls_sim_clear_preferred))
                    }
                }
            }
        }
        CallPrimaryButton(
            stringResource(R.string.calls_dial),
            enabled && ready && CallPolicy.outgoingNumber(number) != null && available != null,
            onDial, Modifier.testTag("call-dial"),
        )
        Text(stringResource(R.string.calls_call_note), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
    }
}

@Composable
private fun PhoneNumberText(number: String) {
    Text(number, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
}

@Composable
private fun CallPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MikaShapes.Button,
        colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
    ) { Text(text) }
}

@Composable
internal fun directionLabel(direction: CallDirection): String = stringResource(
    if (direction == CallDirection.INCOMING) R.string.calls_direction_in else R.string.calls_direction_out,
)

@Composable
private fun phaseLabel(phase: CallPhase): String = stringResource(when (phase) {
    CallPhase.DIALING -> R.string.calls_dialing
    CallPhase.RINGING -> R.string.calls_ringing
    CallPhase.CONNECTING -> R.string.calls_connecting
    CallPhase.ACTIVE -> R.string.calls_connected
    CallPhase.HOLDING -> R.string.calls_holding
    CallPhase.DISCONNECTING -> R.string.calls_disconnecting
    CallPhase.UNKNOWN -> R.string.calls_system_active
    CallPhase.IDLE -> R.string.calls_no_current
})

@Composable
private fun CallRulesDialog(
    config: CallConfig, onDismiss: () -> Unit, onSave: suspend (Boolean, Set<String>, Int) -> Boolean,
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
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioRow(stringResource(R.string.calls_only_list), !all, { all = false }, first = true, enabled = !busy)
                RadioRow(stringResource(R.string.calls_all_incoming), all, { all = true },
                    subtitle = stringResource(R.string.calls_all_note), enabled = !busy)
                OutlinedTextField(
                    numbers, { numbers = it; error = false },
                    modifier = Modifier.fillMaxWidth().testTag("call-allowlist"),
                    label = { Text(stringResource(R.string.calls_allowlist)) },
                    supportingText = {
                        Text(
                            if (validNumbers) stringResource(R.string.calls_allowlist_hint)
                            else stringResource(R.string.calls_allowlist_invalid, CallPolicy.MAX_ALLOWED_NUMBERS),
                        )
                    },
                    textStyle = TextStyle(textDirection = TextDirection.Ltr),
                    isError = !validNumbers, minLines = 2, maxLines = 5, enabled = !busy,
                )
                OutlinedTextField(
                    delay, { delay = it.filter(Char::isDigit).take(2); error = false },
                    modifier = Modifier.fillMaxWidth().testTag("call-answer-delay"),
                    label = { Text(stringResource(R.string.calls_delay_field, minDelay, maxDelay)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = delay.isNotEmpty() && seconds == null, enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && validNumbers && seconds != null, onClick = {
                val duration = seconds ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (onSave(all, normalized ?: return@launch, duration)) onDismiss() else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
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
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calls_credentials_note))
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(legacy, enabled = !busy, role = Role.Checkbox) { legacy = it; key = ""; appId = ""; error = false }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(legacy, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.calls_legacy_credentials))
                }
                if (legacy) OutlinedTextField(
                    appId, { appId = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("App ID") }, singleLine = true, enabled = !busy,
                )
                OutlinedTextField(
                    key, { key = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (legacy) "Access Key" else "API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && key.isNotBlank() && (!legacy || appId.isNotBlank()), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            runtime.settings.saveCredentials(if (legacy) appId else "", if (legacy) key else "", if (legacy) "" else key)
                        }
                        key = ""; appId = ""
                        onResult(saved)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun CallProfileDialog(config: CallConfig, onDismiss: () -> Unit, onSave: suspend (CallConfig) -> Boolean) {
    var greeting by rememberSaveable { mutableStateOf(config.greeting) }
    var prompt by remember { mutableStateOf(config.systemPrompt) }
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
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(greeting, { greeting = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_greeting)) }, maxLines = 3, enabled = !busy)
                OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_instructions)) }, maxLines = 5, enabled = !busy)
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_duration)) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(speaker, { speaker = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_speaker)) }, singleLine = true, enabled = !busy,
                    isError = !allowedVoice, supportingText = { Text(stringResource(R.string.calls_speaker_edit_note)) })
                Text(stringResource(R.string.calls_audio_route))
                CallAudioMode.entries.forEachIndexed { i, option ->
                    RadioRow(
                        stringResource(if (option == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect),
                        selected = mode == option, onClick = { mode = option }, first = i == 0, enabled = !busy,
                    )
                }
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = duration != null && allowedVoice && !busy, onClick = {
                val maxSeconds = duration ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (onSave(config.copy(greeting = greeting, systemPrompt = prompt, speaker = speaker,
                                audioMode = mode, maxCallSeconds = maxSeconds))) onDismiss() else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
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
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.calls_bypass_note))
                OutlinedTextField(
                    numbers, { numbers = it; error = false },
                    label = { Text(stringResource(R.string.calls_number)) },
                    modifier = Modifier.fillMaxWidth().testTag("call-bypass-numbers"),
                    supportingText = { Text(if (normalized != null) stringResource(R.string.calls_allowlist_hint)
                        else stringResource(R.string.calls_allowlist_invalid, CallPolicy.MAX_ALLOWED_NUMBERS)) },
                    textStyle = TextStyle(textDirection = TextDirection.Ltr),
                    isError = normalized == null, minLines = 2, maxLines = 6, enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && normalized != null, onClick = {
                val next = normalized ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (onSave(next)) onDismiss() else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}
