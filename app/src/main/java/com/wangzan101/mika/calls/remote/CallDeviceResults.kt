package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.calls.CallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** Final text depends on real call completion, not the independent recording archive job. */
internal suspend fun awaitDeviceCallRecord(workId: String?, history: StateFlow<List<CallRecord>>): CallRecord? {
    if (workId == null) return null
    return withTimeoutOrNull(20_000L) { history.first { records -> records.any { it.id == workId } }.first { it.id == workId } }
}

internal fun availableDeviceRecordings(history: Flow<List<CallRecord>>): Flow<Set<String>> =
    history.map { records -> records.mapNotNull { record -> record.recording?.id?.takeIf { !record.recordingPending } }.toSet() }
        .distinctUntilChanged()

/** Keep the exact final facts until a durable commit succeeds. No dial or network operation lives here.
 * Three local attempts per trigger, six per explicit enable; reconnect only spends the remaining budget.
 */
internal class PendingDeviceCallResult(val snapshot: RemoteCallSnapshot, val result: JsonObject) {
    init { require(snapshot.terminal && snapshot.workFinished) }
    private val lock = Mutex()
    private var attempts = 0
    var delivered: Boolean = false
        private set

    /** Only the visible user enable action may replenish attempts; background restore/reconnect may not. */
    suspend fun resetForExplicitEnable(): Unit = lock.withLock {
        if (!delivered) attempts = 0
    }

    suspend fun deliver(persist: suspend (RemoteCallSnapshot, JsonObject) -> Unit): Boolean = lock.withLock {
        if (delivered) return@withLock true
        repeat(3) { burst ->
            if (attempts >= 6) return@withLock false
            currentCoroutineContext().ensureActive()
            attempts++
            try {
                persist(snapshot, result)
                delivered = true
                return@withLock true
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { /* Retain the immutable result; bounded retries only. */ }
            if (burst < 2 && attempts < 6) delay(250L * (burst + 1))
        }
        false
    }
}

/** Both completion and progress failures are contained; external cancellation remains cancellation. */
internal suspend fun observeDeviceCall(
    state: StateFlow<RemoteCallSnapshot>,
    onProgress: suspend (RemoteCallSnapshot) -> Unit,
    onCompleted: suspend (RemoteCallSnapshot) -> Unit,
    onFailure: () -> Unit,
) {
    try {
        val terminal = state.onEach {
            if (!it.terminal) {
                try { onProgress(it) }
                catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { onFailure() }
            }
        }.first { it.terminal && it.workFinished }
        onCompleted(terminal)
    } catch (canceled: CancellationException) { throw canceled }
    catch (_: Exception) { onFailure() }
}
