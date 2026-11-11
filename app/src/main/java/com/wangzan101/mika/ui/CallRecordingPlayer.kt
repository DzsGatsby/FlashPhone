package com.wangzan101.mika.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRecordingInfo
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One expanded record owns one player and one temporary recording. Opening a row never starts playback. */
@Composable
internal fun CallRecordingPlayer(
    recording: CallRecordingInfo,
    onOpen: suspend (String) -> File?,
    onClose: (File) -> Unit,
    enabled: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(recording.id, lifecycle) {
        CallRecordingPlayback(context, recording.id, recording.durationMs, scope, { open(it) }, { close(it) })
    }
    val state = controller.state
    var seeking by remember(recording.id) { mutableStateOf<Int?>(null) }
    DisposableEffect(controller, lifecycle) {
        controller.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.setForeground(true)
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> controller.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    SideEffect { controller.setEnabled(enabled) }
    LaunchedEffect(controller, state.playing) {
        // Only redraw progress while playback is advancing; paused/stopped records have no polling job.
        while (controller.state.playing) {
            delay(250L)
            controller.updatePosition()
        }
    }
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("call-recording-player"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.calls_recording_title), style = MaterialTheme.typography.titleSmall, color = c.ink)
        if (recording.partial) Text(
            stringResource(R.string.calls_recording_partial), style = MaterialTheme.typography.bodySmall,
            color = c.warn, modifier = Modifier.testTag("call-recording-partial"),
        )
        Text(stringResource(R.string.calls_recording_channels), style = MaterialTheme.typography.bodySmall, color = c.ink2)
        Text(stringResource(R.string.calls_recording_output_note), style = MaterialTheme.typography.bodySmall, color = c.ink3)
        val seekLabel = stringResource(R.string.calls_recording_seek)
        Slider(
            value = (seeking ?: state.positionMs).coerceIn(0, state.durationMs).toFloat(),
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
            onValueChange = { seeking = it.toInt() },
            onValueChangeFinished = { seeking?.let(controller::seek); seeking = null },
            enabled = enabled && !state.loading && state.durationMs > 0,
            modifier = Modifier.fillMaxWidth().testTag("call-recording-seek").semantics { contentDescription = seekLabel },
        )
        Text(
            "${recordingTime(seeking ?: state.positionMs)} / ${recordingTime(state.durationMs)}",
            style = MaterialTheme.typography.labelMedium, color = c.ink3,
        )
        if (!enabled) Text(stringResource(R.string.calls_recording_call_active), style = MaterialTheme.typography.bodySmall, color = c.ink3)
        if (state.error) Text(
            stringResource(R.string.calls_recording_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("call-recording-error").semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { if (state.playing) controller.pause() else controller.play() },
                enabled = enabled && !state.loading,
                modifier = Modifier.weight(1f).testTag("call-recording-play-pause"),
            ) {
                Text(stringResource(when {
                    state.loading -> R.string.calls_recording_loading
                    state.playing -> R.string.calls_recording_pause
                    state.error -> R.string.calls_recording_retry
                    else -> R.string.calls_recording_play
                }))
            }
            TextButton(
                onClick = controller::stop,
                enabled = state.loading || state.prepared || state.positionMs > 0,
                modifier = Modifier.testTag("call-recording-stop"),
            ) { Text(stringResource(R.string.calls_recording_stop)) }
        }
    }
}

private fun recordingTime(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    return "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
}

private data class CallRecordingPlaybackState(
    val loading: Boolean = false,
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val error: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int,
)

/** All player operations and state changes stay on the UI thread. Stale acquisitions only close their own file. */
private class CallRecordingPlayback(
    context: Context,
    private val id: String,
    durationMs: Long,
    private val scope: CoroutineScope,
    private val open: suspend (String) -> File?,
    private val close: (File) -> Unit,
) {
    var state by mutableStateOf(CallRecordingPlaybackState(durationMs = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()))
        private set
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var focus: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var generation = 0
    private var disposed = false
    private var foreground = false
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) release(resetPosition = false)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) release(resetPosition = false)
    }

    fun play() {
        if (disposed || !foreground || !enabled || state.loading || state.playing) return
        player?.let { start(it); return }
        if (state.positionMs >= state.durationMs) state = state.copy(positionMs = 0)
        val token = ++generation
        state = state.copy(loading = true, error = false)
        scope.launch {
            var acquired: File? = null
            try {
                // Finish ownership transfer even if the row closes during decryption, then clean up the lease.
                withContext(NonCancellable) { acquired = open(id) }
                ensureActive()
                if (token != generation || disposed || !foreground || !enabled) return@launch
                val source = acquired ?: error("recording unavailable")
                val media = MediaPlayer()
                player = media
                file = source
                acquired = null
                media.setAudioAttributes(attributes)
                media.setOnErrorListener { _, _, _ ->
                    if (player === media && token == generation) fail()
                    true
                }
                media.setOnCompletionListener {
                    if (player === media && token == generation) {
                        val duration = state.durationMs
                        release(resetPosition = false)
                        state = state.copy(positionMs = duration)
                    }
                }
                media.setOnPreparedListener {
                    if (token == generation && !disposed && foreground && enabled && player === media) {
                        try {
                            state = state.copy(prepared = true, durationMs = media.duration.coerceAtLeast(0))
                            val position = state.positionMs.coerceIn(0, state.durationMs)
                            if (position > 0) {
                                media.setOnSeekCompleteListener {
                                    if (player === media && token == generation) {
                                        runCatching { media.setOnSeekCompleteListener(null) }
                                        start(media)
                                    }
                                }
                                media.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
                            } else start(media)
                        } catch (_: Exception) { fail() }
                    }
                }
                media.setDataSource(source.absolutePath)
                media.prepareAsync()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == generation && !disposed) fail()
            } finally {
                acquired?.let { runCatching { close(it) } }
            }
        }
    }

    private fun start(media: MediaPlayer) {
        if (!foreground || !enabled || disposed || player !== media) return
        try {
            val token = generation
            lateinit var request: AudioFocusRequest
            request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN && token == generation && focus === request) {
                        release(resetPosition = false)
                    }
                }, Handler(Looper.getMainLooper())).build()
            focus = request
            if (audioManager?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail()
                return
            }
            media.start()
            state = state.copy(playing = true, loading = false, error = false)
        } catch (_: Exception) { fail() }
    }

    fun pause() {
        try {
            player?.pause()
            updatePosition()
            state = state.copy(playing = false)
            abandonFocus()
        } catch (_: Exception) { fail() }
    }

    fun seek(positionMs: Int) {
        val position = positionMs.coerceIn(0, state.durationMs)
        state = state.copy(positionMs = position)
        if (state.prepared) try {
            player?.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) { fail() }
    }

    fun updatePosition() {
        if (!state.prepared) return
        try { state = state.copy(positionMs = (player?.currentPosition ?: state.positionMs).coerceIn(0, state.durationMs)) }
        catch (_: Exception) { fail() }
    }

    fun stop() { release(resetPosition = true) }

    fun dispose() {
        disposed = true
        release(resetPosition = false)
    }

    private fun fail() {
        release(resetPosition = false)
        state = state.copy(error = true)
    }

    private fun release(resetPosition: Boolean) {
        generation++
        val previous = player
        player = null
        val position = if (resetPosition) 0 else if (state.prepared) {
            runCatching { previous?.currentPosition ?: state.positionMs }.getOrDefault(state.positionMs)
        } else state.positionMs
        // Release the decoder before deleting its file, and invalidate every pending async listener.
        if (previous != null) {
            runCatching {
                previous.setOnPreparedListener(null)
                previous.setOnCompletionListener(null)
                previous.setOnSeekCompleteListener(null)
                previous.setOnErrorListener(null)
            }
            runCatching { previous.release() }
        }
        val previousFile = file
        file = null
        previousFile?.let { runCatching { close(it) } }
        abandonFocus()
        state = state.copy(loading = false, prepared = false, playing = false, positionMs = position.coerceIn(0, state.durationMs))
    }

    private fun abandonFocus() {
        val previous = focus
        focus = null
        if (previous != null) runCatching { audioManager?.abandonAudioFocusRequest(previous) }
    }
}
