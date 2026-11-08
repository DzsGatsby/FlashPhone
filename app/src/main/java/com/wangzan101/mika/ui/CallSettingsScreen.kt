package com.wangzan101.mika.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A single call workspace: actions stay visible while settings and past calls expand in place. */
@Composable
fun CallSettingsScreen(runtime: CallRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val config by runtime.settings.state.collectAsState()
    val state by runtime.state.collectAsState()
    val history by runtime.history.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var number by rememberSaveable { mutableStateOf("") }
    var sim by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var credentials by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    fun perform(action: suspend () -> CallActionResult) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = action().message }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = context.getString(R.string.calls_action_failed) }
            finally { busy = false }
        }
    }
    fun save(next: CallConfig) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { runtime.settings.save(next) } }
                .onFailure { message = it.message ?: context.getString(R.string.calls_action_failed) }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) perform {
            withContext(Dispatchers.IO) { runtime.settings.save(runtime.settings.state.value.copy(enabled = true)) }
            runtime.prepare()
        } else message = context.getString(R.string.calls_permissions_required)
    }
    LaunchedEffect(runtime) { runtime.refresh() }

    PageScaffold(stringResource(R.string.calls_title), onBack, Modifier.testTag("call-settings")) {
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    state.aiActive -> stringResource(R.string.calls_ai_active)
                    state.pendingAutoAnswer -> stringResource(R.string.calls_answer_pending)
                    state.phase != CallPhase.IDLE -> phaseLabel(state.phase)
                    state.prepared && config.enabled && config.credentialsConfigured && state.audioReady -> stringResource(R.string.calls_ready)
                    else -> stringResource(R.string.calls_off)
                }, style = MaterialTheme.typography.titleMedium)
                state.number?.let { Text(it) }
                state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.audioDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.calls_limits, config.maxCallSeconds), style = MaterialTheme.typography.bodySmall)
                if (state.phase != CallPhase.IDLE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MikaButton(stringResource(R.string.calls_takeover), { perform { runtime.takeover() } },
                            Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = !busy && state.canTakeOver)
                        MikaButton(stringResource(R.string.calls_hangup), { perform { runtime.hangup() } },
                            Modifier.weight(1f), kind = ButtonKind.Tonal, enabled = !busy && state.canHangUp)
                    }
                    if (state.phase == CallPhase.RINGING) MikaButton(stringResource(R.string.calls_answer),
                        { perform { runtime.answer() } }, enabled = !busy && state.canAnswer, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        message?.let { Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(R.string.calls_tab_call, R.string.calls_tab_settings, R.string.calls_tab_history).forEachIndexed { i, label ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(stringResource(label)) })
            }
        }
        when (tab) {
            0 -> {
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.calls_number)) },
                    modifier = Modifier.fillMaxWidth().testTag("call-number"), singleLine = true)
                if (state.simOptions.isNotEmpty()) {
                    GroupLabel(stringResource(R.string.calls_sim))
                    state.simOptions.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sim == option.id, onClick = { sim = option.id })
                            Text(option.label + if (option.isDefault) " · " + stringResource(R.string.calls_default) else "")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                MikaButton(stringResource(R.string.calls_dial), { perform { runtime.dial(number, sim) } },
                    Modifier.fillMaxWidth().testTag("call-dial"),
                    enabled = !busy && config.enabled && state.prepared && config.credentialsConfigured && state.audioReady && state.phase == CallPhase.IDLE && number.isNotBlank())
                FooterNote(stringResource(R.string.calls_call_note))
            }
            1 -> {
                MikaButton(stringResource(if (config.enabled) R.string.calls_prepare else R.string.calls_enable), {
                    permissions.launch(buildList {
                        add(Manifest.permission.READ_PHONE_STATE); add(Manifest.permission.CALL_PHONE)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                }, Modifier.fillMaxWidth(), enabled = !busy)
                if (config.enabled) TextButton(onClick = { perform { runtime.stop() } }, enabled = !busy) {
                    Text(stringResource(R.string.calls_disable))
                }
                GroupLabel(stringResource(R.string.calls_provider))
                GroupCard {
                    ListRow(MikaIcons.Phone, stringResource(R.string.calls_credentials),
                        stringResource(if (config.credentialsConfigured) R.string.calls_key_saved else R.string.calls_key_missing),
                        first = true, onClick = { credentials = true }) { RowChevron() }
                    ListRow(MikaIcons.Message, stringResource(R.string.calls_profile),
                        stringResource(R.string.calls_model_label, config.model), onClick = { profile = true }) { RowChevron() }
                }
                GroupLabel(stringResource(R.string.calls_incoming))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(config.audioVerified, { save(config.copy(audioVerified = it, autoAnswer = if (it) config.autoAnswer else false)) },
                        enabled = state.audioReady && state.phase == CallPhase.IDLE)
                    Text(stringResource(R.string.calls_audio_verified), modifier = Modifier.weight(1f))
                }
                if (!config.audioVerified) FooterNote(stringResource(R.string.calls_verify_note))
                GroupCard {
                    ListRow(MikaIcons.Phone, stringResource(R.string.calls_auto_answer), first = true) {
                        MikaSwitch(config.autoAnswer, { save(config.copy(autoAnswer = it)) }, enabled = config.enabled && state.prepared && config.credentialsConfigured && config.audioVerified && state.audioReady)
                    }
                    ListRow(MikaIcons.Globe, stringResource(R.string.calls_all_incoming), stringResource(R.string.calls_all_note)) {
                        MikaSwitch(config.allowAllIncoming, { save(config.copy(allowAllIncoming = it)) })
                    }
                    ListRow(MikaIcons.Phone, stringResource(R.string.calls_allowlist),
                        config.allowedIncomingNumbers.joinToString(", ").ifBlank { stringResource(R.string.calls_allowlist_empty) },
                        onClick = { profile = true }) { RowChevron() }
                }
                FooterNote(stringResource(R.string.calls_privacy_note))
            }
            2 -> {
                if (history.isEmpty()) FooterNote(stringResource(R.string.calls_history_empty))
                history.asReversed().forEach { record ->
                    GroupCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(record.number.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.startedAtMs)),
                                style = MaterialTheme.typography.bodySmall)
                            Text(record.result, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { expanded = if (expanded == record.id) null else record.id }) {
                                Text(stringResource(if (expanded == record.id) R.string.calls_collapse else R.string.calls_transcript))
                            }
                            if (expanded == record.id) record.transcript.forEach { line ->
                                Text((if (line.role == "assistant") "Mika" else stringResource(R.string.calls_caller)) + ": " + line.text)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (credentials) CallCredentialsDialog(runtime, onDismiss = { credentials = false }) { message = it }
    if (profile) CallProfileDialog(config, onDismiss = { profile = false }) { next -> save(next) }
}

@Composable
private fun phaseLabel(phase: CallPhase): String = stringResource(when (phase) {
    CallPhase.DIALING -> R.string.calls_dialing; CallPhase.RINGING -> R.string.calls_ringing
    CallPhase.CONNECTING -> R.string.calls_connecting; CallPhase.ACTIVE -> R.string.calls_connected
    CallPhase.HOLDING -> R.string.calls_holding; CallPhase.DISCONNECTING -> R.string.calls_disconnecting
    else -> R.string.calls_off
})

@Composable
private fun CallCredentialsDialog(runtime: CallRuntime, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var legacy by remember { mutableStateOf(false) }
    // Deliberately not rememberSaveable: credentials never enter saved instance state.
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.calls_key_saved)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.calls_credentials)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.calls_credentials_note))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(legacy, { legacy = it; key = ""; appId = "" })
                Text(stringResource(R.string.calls_legacy_credentials))
            }
            if (legacy) OutlinedTextField(appId, { appId = it }, label = { Text("App ID") }, singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text(if (legacy) "Access Key" else "API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && key.isNotBlank() && (!legacy || appId.isNotBlank()), onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { runtime.settings.saveCredentials(if (legacy) appId else "", if (legacy) key else "", if (legacy) "" else key) }
                    key = ""; appId = ""; onResult(saved); onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.calls_save)) }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } })
}

@Composable
private fun CallProfileDialog(config: CallConfig, onDismiss: () -> Unit, onSave: (CallConfig) -> Unit) {
    var greeting by remember { mutableStateOf(config.greeting) }
    var prompt by remember { mutableStateOf(config.systemPrompt) }
    var numbers by remember { mutableStateOf(config.allowedIncomingNumbers.joinToString("\n")) }
    var seconds by remember { mutableStateOf(config.maxCallSeconds.toString()) }
    var speaker by remember { mutableStateOf(config.speaker) }
    var mode by remember { mutableStateOf(config.audioMode) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.calls_profile)) }, text = {
        androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.then(Modifier.heightIn(max = 440.dp)).then(Modifier.verticalScroll(scroll)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(greeting, { greeting = it }, label = { Text(stringResource(R.string.calls_greeting)) }, maxLines = 3)
                OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.calls_instructions)) }, maxLines = 5)
                OutlinedTextField(numbers, { numbers = it }, label = { Text(stringResource(R.string.calls_allowlist)) }, maxLines = 4)
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.calls_duration)) }, singleLine = true)
                OutlinedTextField(speaker, { speaker = it }, label = { Text(stringResource(R.string.calls_speaker)) }, singleLine = true)
                Text(stringResource(R.string.calls_audio_route))
                CallAudioMode.entries.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == option, { mode = option }); Text(if (option == CallAudioMode.DIRECT) "Direct telephony" else "Call redirection")
                } }
            }
        }
    }, confirmButton = { TextButton(enabled = (seconds.toIntOrNull() ?: 0) in 15..600, onClick = {
        onSave(config.copy(greeting = greeting, systemPrompt = prompt, speaker = speaker, audioMode = mode,
            maxCallSeconds = seconds.toInt(), allowedIncomingNumbers = numbers.split(Regex("[,，\\s]+")) .filter(String::isNotBlank).toSet()))
        onDismiss()
    }) { Text(stringResource(R.string.calls_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_cancel)) } })
}
