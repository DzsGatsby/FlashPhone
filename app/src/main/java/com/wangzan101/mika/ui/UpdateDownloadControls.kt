package com.wangzan101.mika.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.ApkDownloadState
import com.wangzan101.mika.about.ApkRejected
import com.wangzan101.mika.about.ApkUpdater
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpdateDownloadControls(release: ReleaseInfo) {
    val url = release.apkUrl ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val updater = remember(context.applicationContext) { ApkUpdater(context.applicationContext) }
    val calls = (context.applicationContext as MikaApp).calls
    val callState by calls.state.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember(url) { mutableStateOf(ApkDownloadState()) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable(url) { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    // Store the release identity so a recreated activity or a newly discovered APK cannot resume the wrong install.
    var queuedInstall by rememberSaveable { mutableStateOf<String?>(null) }
    var waitingForPermission by rememberSaveable { mutableStateOf<String?>(null) }
    var openedInstaller by rememberSaveable { mutableStateOf<String?>(null) }

    fun canInstallNow(): Boolean {
        if (calls.state.value.phase == CallPhase.IDLE) return true
        if (queuedInstall == url) queuedInstall = null
        if (waitingForPermission == url) waitingForPermission = null
        error = R.string.about_update_call_busy
        return false
    }

    fun finishPermission() {
        val expected = waitingForPermission ?: return
        waitingForPermission = null
        if (expected == url) {
            if (canInstallNow()) {
                if (context.packageManager.canRequestPackageInstalls()) queuedInstall = url
                else error = R.string.about_update_install_permission_denied
            }
            refresh++
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishPermission()
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (openedInstaller == url) {
            openedInstaller = null
            refresh++
        }
    }
    DisposableEffect(lifecycle, url) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    // Some settings screens do not return a result. Check the actual system permission on return.
                    finishPermission()
                    refresh++
                }
                Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApkRejected) {
                error = when (e.reason) {
                    ApkRejected.Reason.Corrupt -> R.string.about_update_bad_file
                    ApkRejected.Reason.NotNewer -> R.string.about_update_not_newer
                    ApkRejected.Reason.Signer -> R.string.about_update_bad_signer
                }
            } catch (_: Exception) {
                error = R.string.about_update_error
            } finally {
                busy = false
                refresh++
            }
        }
    }

    LaunchedEffect(url, refresh, foreground) {
        if (!foreground) return@LaunchedEffect
        try {
            do {
                state = withContext(Dispatchers.IO) { updater.state(release) }
                if (state.phase == DownloadPhase.Failed && queuedInstall == url) queuedInstall = null
                if (state.phase != DownloadPhase.Downloading && state.phase != DownloadPhase.Paused) break
                delay(1_000)
            } while (true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (queuedInstall == url) queuedInstall = null
            state = ApkDownloadState(DownloadPhase.Failed)
            error = R.string.about_update_error
        }
    }

    LaunchedEffect(url, state.phase, busy, foreground, queuedInstall, waitingForPermission, callState.phase) {
        if ((queuedInstall == url || waitingForPermission == url) && !canInstallNow()) return@LaunchedEffect
        if (!foreground || busy || queuedInstall != url || state.phase != DownloadPhase.Ready) return@LaunchedEffect
        queuedInstall = null
        run {
            if (!canInstallNow()) return@run
            if (context.packageManager.canRequestPackageInstalls()) {
                val intent = withContext(Dispatchers.IO) { updater.installIntent(release) }
                if (!canInstallNow()) return@run
                if (!foreground) {
                    queuedInstall = url
                    return@run
                }
                openedInstaller = url
                try {
                    installer.launch(intent)
                } catch (e: Exception) {
                    openedInstaller = null
                    throw e
                }
            } else {
                waitingForPermission = url
                try {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null)))
                } catch (e: Exception) {
                    waitingForPermission = null
                    throw e
                }
            }
        }
    }

    UpdateDownloadActions(state, busy, error,
        onDownload = {
            run {
                if (canInstallNow()) queuedInstall = url
                try {
                    withContext(Dispatchers.IO) { updater.download(release) }
                } catch (e: Exception) {
                    queuedInstall = null
                    throw e
                }
            }
        },
        onCancel = {
            run {
                queuedInstall = null
                withContext(Dispatchers.IO) { updater.cancel() }
            }
        },
        onInstall = { if (canInstallNow()) queuedInstall = url },
    )
}

@Composable
internal fun UpdateDownloadActions(
    state: ApkDownloadState,
    busy: Boolean,
    /** 上次操作没成时的说明;为空时显示安装提示。 */
    error: Int?,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = state.phase == DownloadPhase.Downloading || state.phase == DownloadPhase.Paused
    val text = when {
        busy -> stringResource(R.string.about_update_working)
        state.phase == DownloadPhase.Paused -> stringResource(R.string.about_update_paused)
        active -> state.percent?.let { stringResource(R.string.about_update_progress, it) }
            ?: stringResource(R.string.about_update_downloading)
        state.phase == DownloadPhase.Ready -> stringResource(R.string.about_update_install)
        state.phase == DownloadPhase.Failed -> stringResource(R.string.about_update_retry)
        else -> stringResource(R.string.about_update_download_in_app)
    }
    MikaButton(text, if (state.phase == DownloadPhase.Ready) onInstall else onDownload,
        Modifier.fillMaxWidth().padding(top = 14.dp).testTag("update-primary-action"), enabled = !busy && !active)
    if (active) {
        val progress = state.percent
        val modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("update-download-progress")
        if (progress != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = modifier)
        else if (state.phase == DownloadPhase.Downloading) LinearProgressIndicator(modifier)
    }
    if (active || state.phase == DownloadPhase.Ready) {
        MikaButton(stringResource(R.string.about_update_cancel), onCancel,
            Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal, enabled = !busy)
    }
    val note = error ?: if (state.phase == DownloadPhase.Failed) R.string.about_update_error else R.string.about_update_install_hint
    val message = when (note) {
        R.string.about_update_install_hint, R.string.about_update_bad_signer, R.string.about_update_not_newer ->
            stringResource(note, stringResource(R.string.app_name))
        else -> stringResource(note)
    }
    Text(message,
        Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MikaType.Caption, color = if (error != null || state.phase == DownloadPhase.Failed) MikaTheme.colors.bad else MikaTheme.colors.ink3)
}
