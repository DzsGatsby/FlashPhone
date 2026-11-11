package com.wangzan101.mika.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.voiceclone.VoiceCloneActivity
import com.wangzan101.mika.calls.voiceclone.VoiceCloneController
import com.wangzan101.mika.calls.voiceclone.VoiceCloneError
import com.wangzan101.mika.calls.voiceclone.VoiceCloneState
import com.wangzan101.mika.calls.voiceclone.VoiceCloneStatus
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme

private enum class CloneStep(@StringRes val title: Int) {
    SLOT(R.string.voice_clone_step_slot), RECORD(R.string.voice_clone_step_record), FINISH(R.string.voice_clone_step_finish),
}

private enum class CloneConfirmation { UPLOAD, APPLY }

/** Network actions require an explicit tap; the recording stays local until upload is confirmed. */
@Composable
internal fun VoiceCloneScreen(runtime: CallRuntime, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MikaApp
    val controller = remember(app) { VoiceCloneController(app) }
    val state by controller.state.collectAsState()
    val config by runtime.settings.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var step by rememberSaveable { mutableStateOf(CloneStep.SLOT) }
    var finishBack by rememberSaveable { mutableStateOf(CloneStep.RECORD) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<CloneConfirmation?>(null) }
    var microphoneDenied by remember { mutableStateOf(false) }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        microphoneDenied = !allowed
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            !controller.state.value.callBusy && step == CloneStep.RECORD && consent) controller.startRecording()
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                when (controller.state.value.activity) {
                    VoiceCloneActivity.RECORDING -> controller.discardRecording()
                    VoiceCloneActivity.PLAYING_SAMPLE, VoiceCloneActivity.PLAYING_DEMO, VoiceCloneActivity.FETCHING_DEMO ->
                        controller.stopPlayback()
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.close()
        }
    }

    fun back() {
        when {
            state.activity == VoiceCloneActivity.RECORDING -> controller.stopRecording()
            state.activity in playbackActivities -> controller.stopPlayback()
            step == CloneStep.FINISH -> step = finishBack
            step == CloneStep.RECORD -> step = CloneStep.SLOT
            else -> onBack()
        }
    }
    BackHandler { back() }

    PageScaffold(stringResource(R.string.voice_clone_title), { back() }, Modifier.testTag("voice-clone-screen")) {
        CallSectionHeader(
            title = stringResource(R.string.voice_clone_step, step.ordinal + 1, CloneStep.entries.size, stringResource(step.title)),
            badge = "${step.ordinal + 1} / ${CloneStep.entries.size}",
            badgeTone = TileTone.Accent,
        )
        if (state.callBusy) {
            CallSurfaceCard(
                modifier = Modifier.padding(bottom = 12.dp),
                tone = TileTone.Warn,
            ) {
                ListRow(MikaIcons.Phone, stringResource(R.string.voice_clone_call_busy), first = true,
                    tone = TileTone.Warn, onClick = onBack) { RowChevron() }
            }
        }
        when (step) {
            CloneStep.SLOT -> VoiceCloneSlot(
                state = state, credentialsConfigured = config.credentialsConfigured,
                hasCustomVoice = config.speaker != CallConfig().speaker,
                onId = controller::setSpeakerId, onQuery = controller::refreshStatus,
                onRecord = { step = CloneStep.RECORD },
                onResult = { finishBack = CloneStep.SLOT; step = CloneStep.FINISH },
                onReset = controller::resetToDefault,
            )
            CloneStep.RECORD -> VoiceCloneRecording(
                state, consent, { consent = it }, microphoneDenied,
                onRecord = {
                    microphoneDenied = false
                    recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = controller::stopRecording,
                onDiscard = controller::discardRecording,
                onListen = controller::playSample, onStopListen = controller::stopPlayback,
                onNext = { finishBack = CloneStep.RECORD; step = CloneStep.FINISH },
            )
            CloneStep.FINISH -> VoiceCloneFinish(
                state = state, credentialsConfigured = config.credentialsConfigured, hasCustomVoice = config.speaker != CallConfig().speaker,
                onUpload = { confirmation = CloneConfirmation.UPLOAD },
                onQuery = controller::refreshStatus, onDemo = controller::playDemo,
                onStopPlayback = controller::stopPlayback, onApply = { confirmation = CloneConfirmation.APPLY },
                onReset = controller::resetToDefault, onRecordAgain = { step = CloneStep.RECORD },
            )
        }
        state.error?.let {
            FooterNote(
                stringResource(errorLabel(it)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    val pending = confirmation
    if (pending != null) {
        val uploading = pending == CloneConfirmation.UPLOAD
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (uploading) R.string.voice_clone_upload else R.string.voice_clone_apply)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (uploading) stringResource(R.string.voice_clone_upload_confirm)
                        else stringResource(R.string.voice_clone_apply_confirm),
                    )
                    Text(state.speakerId, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                    if (!uploading) {
                        Text(stringResource(R.string.voice_clone_first_use_cost))
                        Text(stringResource(if (config.autoAnswer) R.string.voice_clone_auto_on else R.string.voice_clone_auto_off))
                    }
                }
            },
            confirmButton = {
                val enabled = !state.callBusy && state.activity == VoiceCloneActivity.IDLE &&
                    (if (uploading) consent && config.credentialsConfigured && state.canSubmit else state.canApply)
                TextButton(enabled = enabled, onClick = {
                    confirmation = null
                    if (uploading) controller.submitTraining() else controller.applyToCalls()
                }) { Text(stringResource(if (uploading) R.string.voice_clone_upload else R.string.voice_clone_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.calls_cancel)) } },
        )
    }
}

private val playbackActivities = setOf(
    VoiceCloneActivity.PLAYING_SAMPLE, VoiceCloneActivity.PLAYING_DEMO, VoiceCloneActivity.FETCHING_DEMO,
)

private val VoiceCloneState.canApply: Boolean
    get() = status.isUsable && validSpeakerId && !awaitingStatus && !selectedForCalls

private val VoiceCloneStatus?.isUsable: Boolean
    get() = this == VoiceCloneStatus.READY || this == VoiceCloneStatus.ACTIVE

@Composable
private fun VoiceCloneSlot(
    state: VoiceCloneState, credentialsConfigured: Boolean, hasCustomVoice: Boolean,
    onId: (String) -> Unit, onQuery: () -> Unit, onRecord: () -> Unit, onResult: () -> Unit, onReset: () -> Unit,
) {
    val idle = state.activity == VoiceCloneActivity.IDLE && !state.callBusy
    CallSurfaceCard(
        tone = if (state.selectedForCalls) TileTone.Ok else TileTone.Accent,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.voice_clone_slot_help), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                state.speakerId, onId, label = { Text(stringResource(R.string.voice_clone_slot_id)) },
                singleLine = true, enabled = idle,
                isError = state.speakerId.isNotBlank() && !state.validSpeakerId,
                textStyle = TextStyle(textDirection = TextDirection.Ltr),
                modifier = Modifier.fillMaxWidth().testTag("voice-clone-slot"),
            )
            if (!credentialsConfigured) Text(stringResource(R.string.voice_clone_key_required),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            CloneStatus(state)
            if (state.selectedForCalls) Text(stringResource(R.string.voice_clone_selected), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = onQuery, enabled = idle && credentialsConfigured && state.validSpeakerId,
                modifier = Modifier.fillMaxWidth().testTag("voice-clone-query"),
            ) { Text(stringResource(R.string.voice_clone_query)) }
            if (state.status != null && state.status != VoiceCloneStatus.NOT_FOUND) {
                TextButton(onClick = onResult, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.voice_clone_step_finish))
                }
            }
            CloneButton(stringResource(R.string.voice_clone_step_record), idle && state.validSpeakerId && !state.awaitingStatus &&
                state.status != VoiceCloneStatus.TRAINING, onRecord)
            if (hasCustomVoice) TextButton(onClick = onReset, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.voice_clone_reset))
            }
        }
    }
    FooterNote(stringResource(R.string.flash_voice_clone_cost))
}

@Composable
private fun VoiceCloneRecording(
    state: VoiceCloneState, consent: Boolean, onConsent: (Boolean) -> Unit, microphoneDenied: Boolean,
    onRecord: () -> Unit, onStop: () -> Unit, onDiscard: () -> Unit, onListen: () -> Unit,
    onStopListen: () -> Unit, onNext: () -> Unit,
) {
    val recording = state.activity == VoiceCloneActivity.RECORDING
    val listening = state.activity == VoiceCloneActivity.PLAYING_SAMPLE
    val idle = state.activity == VoiceCloneActivity.IDLE && !state.callBusy
    val length = state.durationMillis
    val validLength = length in 14_000L..30_000L
    CallSurfaceCard(
        tone = if (recording) TileTone.Warn else if (state.hasRecording && validLength) TileTone.Ok else TileTone.Accent,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.voice_clone_sample_help), style = MaterialTheme.typography.bodyMedium)
            Surface(color = MikaTheme.colors.fill, shape = MikaShapes.Button) {
                Text(VoiceCloneController.SCRIPT, Modifier.fillMaxWidth().padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(consent, enabled = idle && !state.hasRecording, role = Role.Checkbox, onValueChange = onConsent),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Checkbox(consent, onCheckedChange = null, enabled = idle && !state.hasRecording)
                Text(stringResource(R.string.voice_clone_consent), style = MaterialTheme.typography.bodyMedium)
            }
            if (microphoneDenied) Text(stringResource(R.string.voice_clone_mic_permission), color = MaterialTheme.colorScheme.error)
            if (recording || state.hasRecording) {
                Text(
                    stringResource(if (recording) R.string.voice_clone_recording_time else R.string.voice_clone_sample_time,
                        (length / 1_000L).toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (recording) MaterialTheme.colorScheme.error else MikaTheme.colors.ink,
                )
                if (state.hasRecording && !recording && !validLength) {
                    Text(stringResource(R.string.voice_clone_length), color = MaterialTheme.colorScheme.error)
                }
            }
            when {
                recording -> CloneButton(
                    stringResource(R.string.voice_clone_stop_recording), true, onStop, Modifier.testTag("voice-clone-stop"),
                    destructive = true,
                )
                listening -> CloneButton(stringResource(R.string.voice_clone_stop_playing), true, onStopListen)
                state.hasRecording -> {
                    OutlinedButton(onClick = onListen, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.voice_clone_listen_sample))
                    }
                    TextButton(onClick = onDiscard, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.voice_clone_rerecord))
                    }
                    CloneButton(stringResource(R.string.voice_clone_step_finish), idle && consent && validLength, onNext)
                }
                else -> CloneButton(
                    stringResource(R.string.voice_clone_start_recording), idle && consent, onRecord,
                    Modifier.testTag("voice-clone-start"),
                )
            }
        }
    }
}

@Composable
private fun VoiceCloneFinish(
    state: VoiceCloneState, credentialsConfigured: Boolean, hasCustomVoice: Boolean,
    onUpload: () -> Unit, onQuery: () -> Unit, onDemo: () -> Unit, onStopPlayback: () -> Unit,
    onApply: () -> Unit, onReset: () -> Unit, onRecordAgain: () -> Unit,
) {
    val idle = state.activity == VoiceCloneActivity.IDLE && !state.callBusy
    CallSurfaceCard(
        tone = if (state.selectedForCalls || state.status.isUsable) TileTone.Ok else TileTone.Accent,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CloneStatus(state)
            if (state.hasRecording) {
                Text(stringResource(R.string.voice_clone_sample_time, (state.durationMillis / 1_000L).toInt()),
                    style = MaterialTheme.typography.bodySmall)
                CloneButton(stringResource(R.string.voice_clone_upload), idle && credentialsConfigured && state.canSubmit, onUpload,
                    Modifier.testTag("voice-clone-upload"))
            }
            OutlinedButton(
                onClick = onQuery, enabled = idle && credentialsConfigured && state.validSpeakerId,
                modifier = Modifier.fillMaxWidth().testTag("voice-clone-query"),
            ) { Text(stringResource(R.string.voice_clone_query)) }
            if (state.status.isUsable) {
                when {
                    state.activity in playbackActivities -> TextButton(
                        onClick = onStopPlayback, modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.voice_clone_stop_playing)) }
                    state.demoAvailable -> OutlinedButton(onClick = onDemo, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.voice_clone_listen_demo))
                    }
                    else -> Text(stringResource(R.string.voice_clone_no_demo), style = MaterialTheme.typography.bodySmall,
                        color = MikaTheme.colors.ink3)
                }
                if (!state.selectedForCalls) CloneButton(stringResource(R.string.voice_clone_apply), idle && state.canApply, onApply,
                    Modifier.testTag("voice-clone-apply"))
            }
            if (state.selectedForCalls) Text(stringResource(R.string.voice_clone_selected), style = MaterialTheme.typography.bodyMedium)
            if (hasCustomVoice) TextButton(onClick = onReset, enabled = idle, modifier = Modifier.fillMaxWidth().testTag("voice-clone-default")) {
                Text(stringResource(R.string.voice_clone_reset))
            }
            if (!state.hasRecording && !state.awaitingStatus && state.status != VoiceCloneStatus.TRAINING) TextButton(
                onClick = onRecordAgain, enabled = idle && state.validSpeakerId, modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.voice_clone_step_record)) }
        }
    }
    FooterNote(stringResource(R.string.flash_voice_clone_cost))
}

@Composable
private fun CloneStatus(state: VoiceCloneState) {
    val activity = when (state.activity) {
        VoiceCloneActivity.SUBMITTING -> R.string.voice_clone_uploading
        VoiceCloneActivity.CHECKING -> R.string.voice_clone_checking
        VoiceCloneActivity.FETCHING_DEMO -> R.string.voice_clone_preview_loading
        VoiceCloneActivity.APPLYING -> R.string.voice_clone_saving
        else -> null
    }
    if (activity != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(
        stringResource(activity ?: when (state.status) {
            VoiceCloneStatus.NOT_FOUND -> R.string.voice_clone_not_found
            VoiceCloneStatus.TRAINING -> R.string.voice_clone_training
            VoiceCloneStatus.READY, VoiceCloneStatus.ACTIVE -> R.string.voice_clone_trained
            VoiceCloneStatus.FAILED -> R.string.voice_clone_training_failed
            VoiceCloneStatus.UNKNOWN, null -> if (state.awaitingStatus) R.string.voice_clone_refresh else R.string.voice_clone_not_checked
        }),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    state.remainingTraining?.let {
        Text(stringResource(R.string.voice_clone_remaining, it), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CloneButton(
    text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false,
) {
    val c = MikaTheme.colors
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MikaShapes.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) MaterialTheme.colorScheme.error else c.accent,
            contentColor = if (destructive) MaterialTheme.colorScheme.onError else c.onAccent,
        ),
    ) { Text(text) }
}

@StringRes
private fun errorLabel(error: VoiceCloneError): Int = when (error) {
    VoiceCloneError.SPEAKER_ID_INVALID -> R.string.voice_clone_slot_help
    VoiceCloneError.MIC_PERMISSION -> R.string.voice_clone_mic_permission
    VoiceCloneError.CALL_BUSY -> R.string.voice_clone_call_unavailable
    VoiceCloneError.RECORDING_INTERRUPTED -> R.string.voice_clone_interrupted
    VoiceCloneError.RECORDING_FAILED -> R.string.voice_clone_record_failed
    VoiceCloneError.AUDIO_LENGTH -> R.string.voice_clone_length
    VoiceCloneError.PLAYBACK_FAILED -> R.string.voice_clone_play_failed
    VoiceCloneError.API_KEY_REQUIRED -> R.string.voice_clone_key_required
    VoiceCloneError.AUTH -> R.string.voice_clone_not_authorized
    VoiceCloneError.WER_MISMATCH -> R.string.voice_clone_mismatch
    VoiceCloneError.TRAINING_FAILED -> R.string.voice_clone_training_failed
    VoiceCloneError.NO_TRAINING_LEFT -> R.string.voice_clone_no_quota
    VoiceCloneError.STATUS_TIMEOUT, VoiceCloneError.REFRESH_NEEDED -> R.string.voice_clone_refresh
    VoiceCloneError.DEMO_EXPIRED -> R.string.voice_clone_no_demo
    VoiceCloneError.SAVE_FAILED -> R.string.calls_action_failed
    VoiceCloneError.RATE_LIMITED, VoiceCloneError.REQUEST_REJECTED, VoiceCloneError.SERVER,
    VoiceCloneError.NETWORK, VoiceCloneError.INVALID_RESPONSE -> R.string.voice_clone_cloud_error
}
