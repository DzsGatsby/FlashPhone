package com.wangzan101.mika.calls.remote

import com.wangzan101.mika.calls.CallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/** Final text depends on real call completion, not the independent recording archive job. */
internal suspend fun awaitDeviceCallRecord(workId: String?, history: StateFlow<List<CallRecord>>): CallRecord? {
    if (workId == null) return null
    return withTimeoutOrNull(20_000L) { history.first { records -> records.any { it.id == workId } }.first { it.id == workId } }
}

internal fun availableDeviceRecordings(history: Flow<List<CallRecord>>): Flow<Set<String>> =
    history.map { records -> records.mapNotNull { record -> record.recording?.id?.takeIf { !record.recordingPending } }.toSet() }
        .distinctUntilChanged()

/** Both completion and progress failures are contained; external cancellation remains cancellation. */
internal suspend fun observeDeviceCall(
    state: StateFlow<RemoteCallSnapshot>,
    onProgress: suspend (RemoteCallSnapshot) -> Unit,
    onCompleted: suspend (RemoteCallSnapshot) -> Unit,
    onFailure: () -> Unit,
) {
    try {
        val terminal = state.onEach { if (!it.terminal) onProgress(it) }.first { it.terminal && it.workFinished }
        onCompleted(terminal)
    } catch (canceled: CancellationException) { throw canceled }
    catch (_: Exception) { onFailure() }
}
