package com.wangzan101.mika.calls.voiceclone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.telecom.TelecomManager
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallPhase
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

enum class VoiceCloneActivity {
    IDLE, RECORDING, PLAYING_SAMPLE, SUBMITTING, CHECKING, FETCHING_DEMO, PLAYING_DEMO, APPLYING,
}

data class VoiceCloneState(
    val speakerId: String,
    val activity: VoiceCloneActivity = VoiceCloneActivity.IDLE,
    val status: VoiceCloneStatus? = null,
    val durationMillis: Long = 0,
    val hasRecording: Boolean = false,
    val remainingTraining: Int? = null,
    val demoAvailable: Boolean = false,
    val selectedForCalls: Boolean = false,
    val callBusy: Boolean = false,
    val error: VoiceCloneError? = null,
    val awaitingStatus: Boolean = false,
    val currentSampleSubmitted: Boolean = false,
) {
    val validSpeakerId: Boolean get() = VolcVoiceCloneClient.validSpeakerId(speakerId)
    val canSubmit: Boolean get() = validSpeakerId && hasRecording && !currentSampleSubmitted &&
        !awaitingStatus && status != VoiceCloneStatus.TRAINING && remainingTraining != 0 &&
        activity == VoiceCloneActivity.IDLE && !callBusy
}

/** One local sample may be sent at most once to any one slot, even after switching slots. */
internal class VoiceCloneSampleSubmissions {
    private val slots = mutableSetOf<String>()
    operator fun contains(slot: String): Boolean = slot in slots
    fun mark(slot: String) { slots.add(slot) }
    fun reject(slot: String) { slots.remove(slot) }
    fun discardSample() { slots.clear() }
}

/** get_voice describes a slot, not the particular recording last sent by this device. */
internal enum class VoiceCloneTrainingEvidence {
    UNCONFIRMED, TRAINING_OBSERVED;

    fun after(status: VoiceCloneStatus, fromSubmission: Boolean): VoiceCloneTrainingEvidence? = when {
        status == VoiceCloneStatus.TRAINING -> TRAINING_OBSERVED
        (fromSubmission || this == TRAINING_OBSERVED) && status in TERMINAL_STATES -> null
        else -> this
    }

    companion object {
        private val TERMINAL_STATES = setOf(VoiceCloneStatus.READY, VoiceCloneStatus.ACTIVE, VoiceCloneStatus.FAILED)

        fun restore(saved: String?, legacyPending: Boolean): VoiceCloneTrainingEvidence? = when {
            saved == TRAINING_OBSERVED.name -> TRAINING_OBSERVED
            saved != null || legacyPending -> UNCONFIRMED
            else -> null
        }
    }
}

/** A screen-owned controller. Only explicit user actions can upload, fetch, query or apply an ID. */
class VoiceCloneController(private val app: MikaApp) : AutoCloseable {
    private val calls = app.calls
    private val settings = calls.settings
    private val prefs = app.getSharedPreferences("mika_call_voice_clone", Context.MODE_PRIVATE)
    private val cacheRoot = File(app.cacheDir, "call-voice-clone")
    private val cache = File(cacheRoot, UUID.randomUUID().toString())
    private val client = VolcVoiceCloneClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var recordStop: AtomicBoolean? = null
    private var sample: File? = null
    private val sampleSubmissions = VoiceCloneSampleSubmissions()
    private var previewUrl: HttpUrl? = null
    private var previewUntil = 0L
    private var previewExpiry: Job? = null
    private var interrupted = false
    private var closed = false

    private val initialSpeaker = prefs.getString(LAST_SLOT, null)
        ?.takeIf { VolcVoiceCloneClient.validSpeakerId(it) }
        ?: settings.state.value.speaker.takeIf { VolcVoiceCloneClient.validSpeakerId(it) }.orEmpty()
    private val mutable = MutableStateFlow(
        VoiceCloneState(
            speakerId = initialSpeaker,
            selectedForCalls = initialSpeaker.isNotEmpty() && initialSpeaker == settings.state.value.speaker,
            awaitingStatus = trainingEvidence(initialSpeaker) != null,
            callBusy = calls.state.value.phase != CallPhase.IDLE,
        ),
    )
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            calls.state.collect { value ->
                if (value.phase != CallPhase.IDLE) {
                    mutable.update { it.copy(callBusy = true) }
                    interruptMediaForCall()
                } else mutable.update { it.copy(callBusy = false) }
            }
        }
        scope.launch {
            settings.state.collect { config ->
                mutable.update { it.copy(selectedForCalls = config.speaker == it.speakerId) }
            }
        }
        scope.launch {
            var revision = settings.credentialsRevision.value
            settings.credentialsRevision.collect { updated ->
                if (revision != updated) {
                    clearDemo()
                    mutable.update { it.copy(status = null, remainingTraining = null) }
                }
                revision = updated
            }
        }
        // A process kill can strand a private cache directory; never reuse its recordings.
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_CACHE_MS
            cacheRoot.listFiles()?.filter { it != cache && it.lastModified() < cutoff }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    fun setSpeakerId(value: String) {
        if (closed || operation != null) return
        val id = value.trim().take(100)
        if (id == mutable.value.speakerId) return
        clearDemo()
        mutable.update {
            it.copy(
                speakerId = id,
                status = null,
                remainingTraining = null,
                awaitingStatus = trainingEvidence(id) != null,
                currentSampleSubmitted = id in sampleSubmissions,
                selectedForCalls = id == settings.state.value.speaker,
                error = null,
            )
        }
    }

    fun startRecording() {
        if (closed || operation != null) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(VoiceCloneError.MIC_PERMISSION)
            return
        }
        if (mutable.value.callBusy) {
            fail(VoiceCloneError.CALL_BUSY)
            return
        }
        begin(VoiceCloneActivity.RECORDING, VoiceCloneError.RECORDING_FAILED) {
            val stop = AtomicBoolean(false)
            recordStop = stop
            val destination = newFile("sample-", ".wav")
            var keep = false
            clearSample()
            try {
                val duration = VoiceCloneRecorder.record(destination, stop, ::systemCallIdle) { milliseconds ->
                    mutable.update { value ->
                        if (value.activity == VoiceCloneActivity.RECORDING) value.copy(durationMillis = milliseconds)
                        else value
                    }
                }
                sample = destination
                keep = true
                mutable.update { it.copy(durationMillis = duration, hasRecording = true) }
            } finally {
                recordStop = null
                if (!keep) {
                    destination.delete()
                    mutable.update { it.copy(durationMillis = 0, hasRecording = false) }
                }
            }
        }
    }

    fun stopRecording() { recordStop?.set(true) }

    fun discardRecording() {
        if (mutable.value.activity == VoiceCloneActivity.RECORDING) operation?.cancel()
        else if (operation == null) clearSample()
    }

    fun playSample() {
        val file = sample ?: return
        begin(VoiceCloneActivity.PLAYING_SAMPLE, VoiceCloneError.PLAYBACK_FAILED) {
            ensureIdleForMedia()
            playUntilEnded(file)
        }
    }

    fun stopPlayback() {
        if (mutable.value.activity in PLAYBACK_STAGES) operation?.cancel()
    }

    fun submitTraining() {
        val current = mutable.value
        val file = sample
        if (closed || operation != null) return
        if (!current.validSpeakerId) return fail(VoiceCloneError.SPEAKER_ID_INVALID)
        if (current.callBusy) return fail(VoiceCloneError.CALL_BUSY)
        if (current.remainingTraining == 0) return fail(VoiceCloneError.NO_TRAINING_LEFT)
        if (current.awaitingStatus || current.status == VoiceCloneStatus.TRAINING ||
            current.currentSampleSubmitted) return fail(VoiceCloneError.REFRESH_NEEDED)
        if (file == null || !current.hasRecording) return fail(VoiceCloneError.AUDIO_LENGTH)
        begin(VoiceCloneActivity.SUBMITTING, VoiceCloneError.NETWORK) {
            val revision = settings.credentialsRevision.value
            val auth = withContext(Dispatchers.IO) { settings.credentials() }
            claimAttempt(current.speakerId)
            sampleSubmissions.mark(current.speakerId)
            clearDemo()
            mutable.update { it.copy(awaitingStatus = true, currentSampleSubmitted = true, status = null) }
            val response = try {
                client.train(auth, current.speakerId, file, SCRIPT)
            } catch (e: VoiceCloneFailure) {
                if (e.error in DEFINITE_REJECTIONS) {
                    updateEvidence(current.speakerId, null)
                    if (e.error != VoiceCloneError.WER_MISMATCH) sampleSubmissions.reject(current.speakerId)
                    mutable.update { it.copy(awaitingStatus = false, currentSampleSubmitted = current.speakerId in sampleSubmissions) }
                }
                throw e
            }
            checkCredentialsRevision(revision)
            acceptResult(current.speakerId, response, revision, fromSubmission = true)
            if (response.status == VoiceCloneStatus.TRAINING) pollTraining(current.speakerId, auth, revision)
        }
    }

    fun refreshStatus() {
        val id = mutable.value.speakerId
        if (!VolcVoiceCloneClient.validSpeakerId(id)) return fail(VoiceCloneError.SPEAKER_ID_INVALID)
        begin(VoiceCloneActivity.CHECKING, VoiceCloneError.NETWORK) {
            saveLastSlot(id)
            val revision = settings.credentialsRevision.value
            val auth = withContext(Dispatchers.IO) { settings.credentials() }
            val status = client.query(auth, id)
            checkCredentialsRevision(revision)
            acceptResult(id, status, revision)
            if (status.status == VoiceCloneStatus.TRAINING) pollTraining(id, auth, revision)
        }
    }

    fun playDemo() {
        val demo = previewUrl
        if (demo == null || SystemClock.elapsedRealtime() >= previewUntil) {
            clearDemo()
            return fail(VoiceCloneError.DEMO_EXPIRED)
        }
        begin(VoiceCloneActivity.FETCHING_DEMO, VoiceCloneError.PLAYBACK_FAILED) {
            ensureIdleForMedia()
            val destination = newFile("demo-", ".audio")
            try {
                client.downloadDemo(demo, destination)
                ensureIdleForMedia()
                mutable.update { it.copy(activity = VoiceCloneActivity.PLAYING_DEMO) }
                playUntilEnded(destination)
            } finally {
                destination.delete()
            }
        }
    }

    /** The UI confirms billing and the existing automatic-answer setting before invoking this. */
    fun applyToCalls() {
        val current = mutable.value
        if (!current.validSpeakerId) return fail(VoiceCloneError.SPEAKER_ID_INVALID)
        if (current.awaitingStatus || current.status !in READY_STATES) return fail(VoiceCloneError.REFRESH_NEEDED)
        changeCallVoice(current.speakerId)
    }

    fun resetToDefault() = changeCallVoice(CallConfig().speaker)

    private fun changeCallVoice(id: String) {
        begin(VoiceCloneActivity.APPLYING, VoiceCloneError.SAVE_FAILED) {
            ensureIdleForMedia()
            withContext(Dispatchers.IO) {
                synchronized(settings) {
                    settings.save(settings.state.value.copy(speaker = id))
                }
            }
        }
    }

    private suspend fun pollTraining(
        id: String,
        auth: com.wangzan101.mika.calls.VolcRealtimeCredentials?,
        revision: Long,
    ) {
        mutable.update { it.copy(activity = VoiceCloneActivity.CHECKING) }
        val finished = withTimeoutOrNull(POLL_LIMIT_MS) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                checkCredentialsRevision(revision)
                val result = client.query(auth, id)
                checkCredentialsRevision(revision)
                acceptResult(id, result, revision)
                if (result.status != VoiceCloneStatus.TRAINING) break
            }
            true
        } == true
        if (!finished) fail(VoiceCloneError.STATUS_TIMEOUT)
    }

    private suspend fun acceptResult(
        id: String,
        response: VoiceCloneResult,
        revision: Long,
        fromSubmission: Boolean = false,
    ) {
        val next = withContext(Dispatchers.IO) {
            synchronized(prefs) {
                checkCredentialsRevision(revision)
                val before = trainingEvidence(id)
                val after = before?.after(response.status, fromSubmission)
                if (after != before) writeEvidence(id, after)
                after
            }
        }
        checkCredentialsRevision(revision)
        // A previous voice can still be READY after an ambiguous retrain. It is not evidence
        // that the newly submitted recording finished, and must not enable Apply or Preview.
        val unresolved = next != null && response.status != VoiceCloneStatus.TRAINING
        val demo = response.demo.takeUnless { unresolved }
        clearDemo()
        previewUrl = demo
        previewUntil = SystemClock.elapsedRealtime() + DEMO_VALID_MS
        if (demo != null) {
            previewExpiry = scope.launch {
                delay(DEMO_VALID_MS)
                clearDemo()
            }
        }
        mutable.update {
            it.copy(
                status = response.status.takeUnless { unresolved },
                remainingTraining = response.remainingTraining ?: it.remainingTraining,
                demoAvailable = demo != null,
                awaitingStatus = next != null,
                error = when {
                    unresolved -> VoiceCloneError.REFRESH_NEEDED
                    response.status == VoiceCloneStatus.FAILED -> VoiceCloneError.TRAINING_FAILED
                    else -> null
                },
            )
        }
    }

    private fun checkCredentialsRevision(revision: Long) {
        if (revision != settings.credentialsRevision.value) throw VoiceCloneFailure(VoiceCloneError.REFRESH_NEEDED)
    }

    private fun begin(
        stage: VoiceCloneActivity,
        fallback: VoiceCloneError,
        block: suspend () -> Unit,
    ) {
        if (closed || operation != null) return
        interrupted = false
        mutable.update { it.copy(activity = stage, error = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                if (interrupted && !closed) fail(VoiceCloneError.RECORDING_INTERRUPTED)
                throw e
            } catch (e: VoiceCloneFailure) {
                if (e.error == VoiceCloneError.DEMO_EXPIRED) clearDemo()
                fail(e.error)
            } catch (_: SecurityException) {
                fail(if (stage == VoiceCloneActivity.RECORDING) VoiceCloneError.MIC_PERMISSION else fallback)
            } catch (_: Exception) {
                fail(fallback)
            } finally {
                operation = null
                mutable.update { it.copy(activity = VoiceCloneActivity.IDLE) }
            }
        }
        operation = job
        job.start()
    }

    private fun interruptMediaForCall() {
        if (mutable.value.activity == VoiceCloneActivity.RECORDING ||
            mutable.value.activity in PLAYBACK_STAGES) {
            interrupted = true
            operation?.cancel()
        }
    }

    private suspend fun ensureIdleForMedia() {
        if (mutable.value.callBusy || !withContext(Dispatchers.IO) { systemCallIdle() }) {
            throw VoiceCloneFailure(VoiceCloneError.CALL_BUSY)
        }
    }

    @SuppressLint("MissingPermission") // This read fails closed on a missing/denied permission.
    private fun systemCallIdle(): Boolean = runCatching {
        app.getSystemService(TelecomManager::class.java)?.isInCall == false
    }.getOrDefault(false)

    private suspend fun playUntilEnded(file: File) = kotlinx.coroutines.coroutineScope {
        val player = MediaPlayer()
        val watcher = launch {
            while (true) {
                delay(200)
                if (!withContext(Dispatchers.IO) { systemCallIdle() }) {
                    throw VoiceCloneFailure(VoiceCloneError.CALL_BUSY)
                }
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { result ->
                player.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                player.setOnPreparedListener {
                    if (result.isActive) {
                        if (mutable.value.callBusy || !systemCallIdle()) {
                            result.resumeWithException(VoiceCloneFailure(VoiceCloneError.CALL_BUSY))
                        } else {
                            try { it.start() }
                            catch (_: Exception) {
                                result.resumeWithException(VoiceCloneFailure(VoiceCloneError.PLAYBACK_FAILED))
                            }
                        }
                    }
                }
                player.setOnCompletionListener { if (result.isActive) result.resume(Unit) }
                player.setOnErrorListener { _, _, _ ->
                    if (result.isActive) result.resumeWithException(VoiceCloneFailure(VoiceCloneError.PLAYBACK_FAILED))
                    true
                }
                file.inputStream().use { player.setDataSource(it.fd) }
                player.prepareAsync()
            }
        } finally {
            watcher.cancel()
            runCatching { player.release() }
        }
    }

    private fun clearSample() {
        sample?.delete()
        sample = null
        sampleSubmissions.discardSample()
        mutable.update { it.copy(hasRecording = false, durationMillis = 0, currentSampleSubmitted = false) }
    }

    private fun clearDemo() {
        previewExpiry?.cancel()
        previewExpiry = null
        previewUrl = null
        previewUntil = 0L
        mutable.update { it.copy(demoAvailable = false) }
    }

    private fun trainingEvidence(id: String): VoiceCloneTrainingEvidence? {
        if (!VolcVoiceCloneClient.validSpeakerId(id)) return null
        return VoiceCloneTrainingEvidence.restore(
            prefs.getString("training_evidence_$id", null), prefs.getBoolean("pending_$id", false),
        )
    }

    private suspend fun saveLastSlot(id: String) = withContext(Dispatchers.IO) {
        if (!prefs.edit().putString(LAST_SLOT, id).commit()) throw VoiceCloneFailure(VoiceCloneError.SAVE_FAILED)
    }

    private suspend fun claimAttempt(id: String) = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            if (trainingEvidence(id) != null) throw VoiceCloneFailure(VoiceCloneError.REFRESH_NEEDED)
            writeEvidence(id, VoiceCloneTrainingEvidence.UNCONFIRMED)
        }
    }

    private suspend fun updateEvidence(id: String, evidence: VoiceCloneTrainingEvidence?) = withContext(Dispatchers.IO) {
        synchronized(prefs) { writeEvidence(id, evidence) }
    }

    private fun writeEvidence(id: String, evidence: VoiceCloneTrainingEvidence?) {
        val edit = prefs.edit().putString(LAST_SLOT, id)
            .remove("pending_$id").putString("training_evidence_$id", evidence?.name)
        if (!edit.commit()) throw VoiceCloneFailure(VoiceCloneError.SAVE_FAILED)
    }

    private fun newFile(prefix: String, extension: String): File {
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Voice clone cache unavailable")
        return File.createTempFile(prefix, extension, cache)
    }

    private fun fail(error: VoiceCloneError) { if (!closed) mutable.update { it.copy(error = error) } }

    override fun close() {
        if (closed) return
        closed = true
        operation?.cancel()
        clearDemo()
        scope.cancel()
        clearSample()
        runCatching { cache.deleteRecursively() }
    }

    companion object {
        const val SCRIPT = "您好，我现在正在录制一段声音。今天的天气很舒服，窗外偶尔有风，街边的树叶轻轻摇动。" +
            "如果您有事情要转告，可以慢慢说。我会把时间、地点和需要处理的事项记清楚。谢谢您的来电，再见。"
        private const val LAST_SLOT = "last_slot"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_LIMIT_MS = 30_000L
        private const val DEMO_VALID_MS = 55 * 60_000L
        private const val STALE_CACHE_MS = 60 * 60_000L
        private val READY_STATES = setOf(VoiceCloneStatus.READY, VoiceCloneStatus.ACTIVE)
        private val PLAYBACK_STAGES = setOf(
            VoiceCloneActivity.PLAYING_SAMPLE, VoiceCloneActivity.FETCHING_DEMO, VoiceCloneActivity.PLAYING_DEMO,
        )
        private val DEFINITE_REJECTIONS = setOf(
            VoiceCloneError.SPEAKER_ID_INVALID, VoiceCloneError.API_KEY_REQUIRED, VoiceCloneError.AUTH,
            VoiceCloneError.RATE_LIMITED, VoiceCloneError.WER_MISMATCH, VoiceCloneError.REQUEST_REJECTED,
            VoiceCloneError.AUDIO_LENGTH, VoiceCloneError.RECORDING_FAILED,
        )
    }
}
