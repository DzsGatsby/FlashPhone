package com.wangzan101.mika.calls

import android.content.Context
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One instance per app runtime. All recording and playback I/O stays off the phone dispatcher.
 * Plaintext exists only in private scratch/cache files, removed on finish or the next initialization.
 */
class CallRecordingStore internal constructor(
    private val directory: File,
    private val playbackDirectory: File,
    cipher: KeyCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "call-recordings"),
        File(context.cacheDir, "call-recording-playback"),
        KeystoreKeyCipher("flashphone-call-recordings"),
    )

    private val lock = Mutex()
    private val archive = CallRecordingArchive(cipher)
    private val scratchDirectory = File(directory, "scratch")
    private var initialized = false
    private var active: CallRecordingSession? = null
    private val committing = mutableSetOf<CallRecordingSession>()
    private val tombstones = mutableSetOf<String>()
    private var playback: File? = null

    init {
        // Crash leftovers are removed on app initialization, even before the next recording/playback.
        // The same lock protects this task and start(), so it cannot delete a new active scratch file.
        CoroutineScope(Dispatchers.IO).launch { lock.withLock { runCatching { initialize() } } }
    }

    /** Takes ownership of monitor on success and failure. Failure must not stop the actual phone call. */
    suspend fun start(id: String, monitor: InputStream): CallRecordingSession {
        var acquired: CallRecordingSession? = null
        try {
            return withContext(Dispatchers.IO) {
                lock.withLock {
                    initialize()
                    require(CallRecordingLimits.validId(id)) { "Invalid recording ID" }
                    active?.takeIf { it.isReaderDone }?.let { committing.add(it); active = null }
                    check(active == null && committing.none { it.id == id } && !recording(id).exists()) { "A recording is already present" }
                    check(committing.size < CallRecordingLimits.MAX_FILES) { "Recording storage is busy" }
                    clearPlayback()
                    // Reserve space for both the bounded plaintext work file and its encrypted commit.
                    val commitBudget = committing.size * CallRecordingArchive.MAX_ARCHIVE_BYTES * 2L
                    val reserve = maxOf(workBytes(), commitBudget) + CallRecordingArchive.MAX_ARCHIVE_BYTES * 2L
                    trim(reserve = reserve)
                    check(recordings().sumOf { it.length() } + reserve <= CallRecordingLimits.MAX_TOTAL_BYTES) { "Recording storage is full" }
                    val session = CallRecordingSession(id, now(), monitor, File(scratchDirectory, "$id.wav"), ::save, ::detach)
                    active = session
                    acquired = session
                    session.start()
                    session
                }
            }
        } catch (error: Exception) {
            runCatching { monitor.close() }
            withContext(NonCancellable) { acquired?.discard() }
            throw error
        }
    }

    /** Returns a single private decrypted lease; opening another recording deletes the previous lease. */
    suspend fun playbackFile(id: String): File? {
        if (!CallRecordingLimits.validId(id)) return null
        var acquired: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val lease = lock.withLock {
                    initialize()
                    clearPlayback()
                    val source = recording(id)
                    if (!source.isFile) return@withLock null
                    val temporary = File.createTempFile("recording-", ".wav", playbackDirectory)
                    playback = temporary
                    acquired = temporary
                    source to temporary
                } ?: return@withContext null
                val (source, temporary) = lease
                try {
                    // A newly arriving call can close this lease immediately; it never waits for decryption.
                    archive.decrypt(id, source, temporary)
                    coroutineContext.ensureActive()
                    lock.withLock {
                        if (playback == temporary && temporary.isFile) temporary
                        else { temporary.delete(); null }
                    }
                } catch (error: CancellationException) {
                    temporary.delete()
                    throw error
                } catch (_: Exception) {
                    lock.withLock { if (playback == temporary) playback = null }
                    temporary.delete()
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { acquired?.let { closePlayback(it) } }
            throw cancelled
        }
    }

    /** A stale player release must never remove a newer player's plaintext. Release MediaPlayer first. */
    suspend fun closePlayback(file: File? = null) = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            if (file == null || file == playback) clearPlayback()
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!CallRecordingLimits.validId(id)) return@withContext false
        lock.withLock {
            initialize()
            active?.takeIf { it.id == id && it.isReaderDone }?.let { committing.add(it); active = null }
            if (active?.id == id) return@withLock false
            if (committing.any { it.id == id }) tombstones.add(id)
            clearPlayback()
            val file = recording(id)
            !file.exists() || file.delete()
        }
    }

    /** Call after history is loaded/saved. The in-progress recording is never pruned. */
    suspend fun prune(liveIds: Set<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            initialize()
            recordings().filter { it.name.removeSuffix(EXTENSION) !in liveIds && !protected(it) }
                .forEach { it.delete() }
            trim()
        }
    }

    private suspend fun save(session: CallRecordingSession, scratch: File, durationMs: Long, partial: Boolean): CallRecordingInfo? =
        try {
            val destination = lock.withLock {
                if (active === session) { active = null; committing.add(session) }
                if (session in committing && session.id !in tombstones) recording(session.id) else null
            }
            if (destination == null) null else {
                // The active-session identity protects this file from delete/prune while committing.
                // Crypto/file streaming holds no metadata lock needed by a newly arriving call.
                archive.encrypt(session.id, scratch, destination)
                lock.withLock {
                    if (session.id in tombstones) { destination.delete(); null }
                    else {
                        // A slower older archive must not become newer than the following call.
                        destination.setLastModified(session.startedAtMs)
                        trim(protect = destination)
                        CallRecordingInfo(session.id, durationMs, partial, destination.length())
                    }
                }
            }
        } catch (_: Exception) {
            null
        }

    private suspend fun detach(session: CallRecordingSession) = lock.withLock {
        if (active === session) active = null
        committing.remove(session)
        tombstones.remove(session.id)
    }

    private fun initialize() {
        if (initialized) return
        check(directory.mkdirs() || directory.isDirectory)
        check(scratchDirectory.mkdirs() || scratchDirectory.isDirectory)
        check(playbackDirectory.mkdirs() || playbackDirectory.isDirectory)
        scratchDirectory.listFiles()?.filter(File::isFile)?.forEach { check(it.delete()) }
        directory.listFiles()?.filter { it.isFile && it.name.endsWith("$EXTENSION.tmp") }?.forEach { check(it.delete()) }
        playbackDirectory.listFiles()?.filter(File::isFile)?.forEach { check(it.delete()) }
        initialized = true
        trim()
    }

    private fun trim(protect: File? = null, reserve: Long = 0L) {
        val files = recordings().sortedByDescending { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files.asReversed()) {
            if (count <= CallRecordingLimits.MAX_FILES && bytes + reserve <= CallRecordingLimits.MAX_TOTAL_BYTES) break
            if (file == protect || protected(file)) continue
            val length = file.length()
            if (file.delete()) { count--; bytes -= length }
        }
    }

    private fun clearPlayback() {
        playback?.let { check(!it.exists() || it.delete()) }
        playback = null
    }

    private fun recording(id: String) = File(directory, id + EXTENSION)
    private fun protected(file: File): Boolean = file.name == active?.id?.plus(EXTENSION) ||
        committing.any { file.name == it.id + EXTENSION }
    private fun workBytes(): Long = scratchDirectory.listFiles().orEmpty().filter(File::isFile).sumOf { it.length() } +
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith("$EXTENSION.tmp") }.sumOf { it.length() }
    private fun recordings(): List<File> = directory.listFiles()?.filter {
        it.isFile && it.name.endsWith(EXTENSION) && CallRecordingLimits.validId(it.name.removeSuffix(EXTENSION))
    }.orEmpty()

    private companion object { const val EXTENSION = ".fpc" }
}

/** Separate monitor reader; a slow/failed recording never blocks realtime PCM transmission. */
class CallRecordingSession internal constructor(
    internal val id: String,
    internal val startedAtMs: Long,
    private val monitor: InputStream,
    private val scratch: File,
    private val save: suspend (CallRecordingSession, File, Long, Boolean) -> CallRecordingInfo?,
    private val detach: suspend (CallRecordingSession) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishLock = Mutex()
    private val forcedPartial = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)
    private var cleanTerminal = false
    private var durationMs = 0L
    private var hasAudio = false
    private var finished = false
    private var result: CallRecordingInfo? = null
    private val reader: Job = scope.launch(start = CoroutineStart.LAZY) { readMonitor() }
    internal val isReaderDone: Boolean get() = reader.isCompleted

    internal fun start() { reader.start() }

    internal suspend fun discard() {
        discarded.set(true)
        runCatching { monitor.close() }
        finish()
    }

    /** Stop the helper first so its clean terminal can arrive. Bounded waits also permit partial saves. */
    suspend fun finish(): CallRecordingInfo? = withContext(NonCancellable + Dispatchers.IO) {
        finishLock.withLock {
            if (finished) return@withLock result
            finished = true
            if (withTimeoutOrNull(FINISH_GRACE_MS) { reader.join(); true } != true) {
                forcedPartial.set(true)
                runCatching { monitor.close() }
                if (withTimeoutOrNull(FINISH_GRACE_MS) { reader.join(); true } != true) {
                    // Do not encrypt while an uncooperative stream still owns the scratch file.
                    scope.launch { reader.join(); scratch.delete(); detach(this@CallRecordingSession); scope.cancel() }
                    return@withLock null
                }
            }
            try {
                if (hasAudio && !discarded.get()) result = save(this@CallRecordingSession, scratch, durationMs, !cleanTerminal || forcedPartial.get())
                result
            } finally {
                runCatching { monitor.close() }
                scratch.delete()
                detach(this@CallRecordingSession)
                scope.cancel()
            }
        }
    }

    private fun readMonitor() {
        var timeline: CallRecordingTimeline? = null
        try {
            val file = RandomAccessFile(scratch, "rw")
            timeline = try { CallRecordingTimeline(file) } catch (error: Exception) {
                runCatching { file.close() }
                throw error
            }
            val input = DataInputStream(monitor.buffered(4096))
            var previousOffset = 0L
            repeat(CallRecordingLimits.MAX_PACKETS) {
                val kind = input.readInt()
                val offset = input.readLong()
                val size = input.readInt()
                check(offset >= previousOffset && offset <= CallRecordingLimits.MAX_SECONDS * 1_000_000L)
                previousOffset = offset
                if (kind == 3) {
                    check(size == 0)
                    timeline.endAt(offset)
                    cleanTerminal = true
                    return
                }
                check((kind == 1 || kind == 2) && size in 2..CallRecordingLimits.MAX_PAYLOAD && size % 2 == 0)
                val pcm = ByteArray(size)
                input.readFully(pcm)
                try {
                    if (!timeline.append(kind, offset, pcm)) return
                } finally {
                    pcm.fill(0)
                }
            }
        } catch (_: Exception) {
            // Do not log audio, malformed payloads or OS exceptions; retain a playable partial when possible.
        } finally {
            timeline?.let {
                hasAudio = it.hasAudio
                durationMs = it.durationMs
                if (runCatching { it.close() }.isFailure) hasAudio = false
            }
            runCatching { monitor.close() }
        }
    }

    private companion object { const val FINISH_GRACE_MS = 1_500L }
}
