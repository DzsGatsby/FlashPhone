package com.wangzan101.mika.calls.remote

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.launch

/** Explicit phone-side account enrollment and separate remote execution authorization. */
@Composable
fun CallDeviceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as MikaApp).callDeviceAgent }
    val state by agent.state.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL.take(64)) }
    var saving by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("返回电话设置") }
        Text("设备账号与远程电话任务", style = MaterialTheme.typography.headlineSmall)
        Text("使用同一账号，从 Grok、Codex 或控制台选择这台 Android 手机，委派有明确目标的电话任务。此入口仅控制电话，不提供通用手机代操作。")
        Text(state.detail, color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CallDeviceProtocol.ORIGIN))) }) {
            Text("打开账号控制台 / 获取一次性连接码")
        }
        if (!state.enrolled) {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text("设备名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(code, { code = it.take(128) }, label = { Text("一次性设备连接码") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(enabled = !saving && !state.busy && code.isNotBlank() && name.isNotBlank(), onClick = {
                saving = true
                val submitted = code
                code = ""
                scope.launch { try { agent.enroll(submitted, name) } finally { saving = false } }
            }) { Text(if (saving || state.busy) "正在连接…" else "连接设备账号") }
        } else {
            Text("${state.account} · ${state.name}")
            Text("开启后，本账号已授权的远程电话任务可直接拨号，无须每通在手机再点确认；仍检查号码、SIM、空闲状态和电话权限。转写与该任务录音通过加密连接上传到本账号私有控制台。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("允许远程电话任务", modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, enabled = !saving, onCheckedChange = { enabled ->
                    saving = true
                    scope.launch { try { agent.setEnabled(enabled) } finally { saving = false } }
                })
            }
            if (state.enabled && !state.foreground) Button(onClick = { agent.restoreAuthorizedConnection() }) { Text("恢复后台连接") }
            Text("后台状态：${if (state.foreground) "前台服务已确认" else "未运行"}；网络：${if (state.connected) "已连接" else "未连接"}")
            Text("关闭会停止接收任务并交还该任务的 AI 通话，不会自动挂断运营商电话。进程重启不会重新拨号；手机仍须完成电话与音频助手配置。")
            OutlinedButton(enabled = !saving, onClick = {
                saving = true
                scope.launch { try { agent.disconnectAccount() } finally { saving = false } }
            }) { Text("断开设备账号") }
        }
    }
}
