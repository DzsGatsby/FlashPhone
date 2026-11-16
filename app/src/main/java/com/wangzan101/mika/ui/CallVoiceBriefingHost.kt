package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.calls.briefing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class VoiceBriefingLauncher(val busy: Boolean, val start: (String, String?, String) -> Unit)

/** Permission, lifecycle, number and SIM ownership stay with the visible workspace. */
@Composable
internal fun rememberCallVoiceBriefing(
    runtime: CallRuntime,
    draft: CallTaskDraft,
    allowed: Boolean,
    onAuthorized: (CallTaskSubmission) -> Unit,
): VoiceBriefingLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(runtime) { VoiceBriefingController(context, scope) }
    val voice by controller.state.collectAsState()
    val currentAllowed by rememberUpdatedState(allowed)
    val currentAuthorized by rememberUpdatedState(onAuthorized)
    var open by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        detail = if (granted) "麦克风已允许，请再次轻点绿色按钮开始交代" else "未获得麦克风权限；可以长按绿色按钮填写文字交代"
    }
    fun cancel() { attempt++; starting = false; controller.cancel() }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { attempt++; starting = false; controller.cancel("已离开前台，未拨号") }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    LaunchedEffect(allowed, draft.number, draft.simOverride) {
        if (!allowed && (starting || voice.active)) cancel()
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { cancel(); open = false },
            title = { Text("和豆包交代任务") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label)
                    Text(when {
                        starting || voice.activity == VoiceBriefingActivity.CONNECTING -> "正在连接豆包…"
                        voice.activity == VoiceBriefingActivity.LISTENING -> "正在听，请交代要办的事"
                        voice.activity == VoiceBriefingActivity.CLOSING -> if (voice.message != null) "语音交代已中断，未拨号" else "正在释放语音连接…"
                        voice.activity == VoiceBriefingActivity.AUTHORIZED -> "交代已确认，正在提交拨号"
                        voice.message != null -> "语音交代已中断，未拨号"
                        else -> "尚未开始语音交代"
                    })
                    Text("交代完毕后，单独说“我说完了，你去打”，才会拨出。")
                    if (voice.assistantTranscript.isNotBlank()) Text("豆包\n${voice.assistantTranscript}")
                    if (voice.ownerTranscript.isNotBlank() || voice.ownerPartial.isNotBlank())
                        Text("本次交代\n${voice.ownerTranscript}\n${voice.ownerPartial}")
                    (detail ?: voice.message)?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { cancel(); open = false }, modifier = Modifier.testTag("call-voice-cancel")) {
                    Text(if (voice.active || starting) "取消，不拨号" else "返回拨号盘")
                }
            },
            modifier = Modifier.testTag("call-voice-briefing"),
        )
    }
    return VoiceBriefingLauncher(starting || voice.active) { number, simId, simLabel ->
        if (!currentAllowed || starting || voice.active) return@VoiceBriefingLauncher
        open = true; detail = null; label = "$number · $simLabel"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val token = ++attempt
            val capturedFields = draft.snapshotFields()
            val capturedRevision = runtime.settings.credentialsRevision.value
            val key = "$number|${simId.orEmpty()}"
            val valid = {
                token == attempt && currentAllowed &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    runtime.state.value.phase == CallPhase.IDLE && runtime.state.value.lockedDialRequestId == null &&
                    runtime.settings.credentialsRevision.value == capturedRevision &&
                    draft.snapshotFields() == capturedFields &&
                    CallPolicy.outgoingNumber(draft.number) == number &&
                    CallPolicy.chooseOutgoingSim(runtime.state.value.simOptions, draft.simOverride,
                        runtime.settings.state.value.defaultOutgoingSimId)?.id == simId
            }
            val initial = runCatching {
                if (listOf(draft.goal, draft.questions, draft.context, draft.constraints).all { it.isBlank() }) null
                else CallTaskBrief(draft.goal, draft.questions.lines().filter { it.isNotBlank() }, draft.context, draft.constraints).validatedSnapshot()
            }
            if (initial.isFailure) {
                detail = "原有文字交代尚不完整，请长按绿色按钮补充后再开始"
            } else {
                starting = true
                scope.launch {
                    val credentials = try { withContext(Dispatchers.IO) { runtime.settings.credentials() } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                    if (token == attempt) starting = false
                    if (valid()) {
                        if (credentials == null) detail = "请先在设置中配置豆包实时语音"
                        else controller.start(key, credentials,
                            VolcRealtimeConfig(speaker = runtime.settings.state.value.speaker), initial.getOrNull(), valid) { brief ->
                            if (valid()) {
                                draft.fill(number, brief)
                                draft.simOverride = simId
                                open = false
                                currentAuthorized(CallTaskSubmission(number, simId, simLabel, brief))
                            }
                        }
                    }
                }
            }
        }
    }
}
