package com.wangzan101.mika.calls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

internal enum class CallSummaryStatus { RUNNING, FAILED, UNCONFIGURED, CANCELED, SAVE_FAILED, BUSY, TIMEOUT, INVALID, EMPTY }

/** Main-dispatcher owned. Bounded, no retries, and never restores work after process death. */
internal class CallSummaryCoordinator(
    private val scope: CoroutineScope,
    private val history: CallHistory,
    private val idle: () -> Boolean,
    private val available: suspend () -> Boolean,
    private val generate: suspend (CallRecord) -> CallSummary,
    private val timeoutMs: Long = 35_000L,
    private val automaticEnabled: () -> Boolean = { true },
) {
    private val mutable = MutableStateFlow<Map<String, CallSummaryStatus>>(emptyMap())
    val states = mutable.asStateFlow()
    private var active: Pair<String, Job>? = null
    private var activeAutomatic = false
    private val pending = linkedSetOf<String>()

    fun scheduleNew(id: String) {
        if (!automaticEnabled()) return
        if (pending.size < 4) pending.add(id)
        onCallStateChanged()
    }

    fun onCallStateChanged() {
        if (!automaticEnabled()) {
            pending.clear()
            if (activeAutomatic) active?.first?.let(::cancel)
        }
        if (active == null && pending.isEmpty()) return
        if (!idle()) {
            active?.first?.let { cancel(it) }
            return
        }
        while (active == null && pending.isNotEmpty()) {
            val id = pending.first()
            pending.remove(id)
            request(id, automatic = true)
        }
    }

    fun request(id: String, automatic: Boolean = false) {
        if (active?.first == id) return
        val record = history.state.value.firstOrNull { it.id == id } ?: return
        pending.remove(id)
        if (automatic && (!automaticEnabled() || record.summary != null)) return
        if (record.transcript.none { it.text.isNotBlank() }) { status(id, CallSummaryStatus.EMPTY); return }
        if (!idle() || active != null) { status(id, CallSummaryStatus.BUSY); return }
        status(id, CallSummaryStatus.RUNNING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(timeoutMs) {
                    if (!available()) { status(id, CallSummaryStatus.UNCONFIGURED); return@withTimeout }
                    coroutineContext.ensureActive()
                    if (!idle()) { status(id, CallSummaryStatus.CANCELED); return@withTimeout }
                    val summary = generate(record)
                    coroutineContext.ensureActive()
                    if (!idle()) { status(id, CallSummaryStatus.CANCELED); return@withTimeout }
                    when (history.setSummary(id, CallSummaryEngine.sourceFingerprint(record), summary)) {
                        CallHistory.Result.SAVED, CallHistory.Result.NOT_FOUND -> mutable.value = mutable.value - id
                        CallHistory.Result.WRITE_FAILED -> status(id, CallSummaryStatus.SAVE_FAILED)
                        CallHistory.Result.SOURCE_CHANGED -> status(id, CallSummaryStatus.INVALID)
                    }
                }
            } catch (_: TimeoutCancellationException) { status(id, CallSummaryStatus.TIMEOUT) }
            catch (e: CancellationException) { throw e }
            catch (e: SummaryModelException) { status(id, when(e.kind) {
                SummaryModelFailure.NOT_CONFIGURED, SummaryModelFailure.CONFIGURATION, SummaryModelFailure.UNSUPPORTED -> CallSummaryStatus.UNCONFIGURED
                SummaryModelFailure.TIMEOUT -> CallSummaryStatus.TIMEOUT
                SummaryModelFailure.INPUT_LIMIT, SummaryModelFailure.OUTPUT_LIMIT -> CallSummaryStatus.INVALID
                SummaryModelFailure.REQUEST -> CallSummaryStatus.FAILED
            }) }
            catch (_: IllegalArgumentException) { status(id, CallSummaryStatus.INVALID) }
            catch (_: Exception) { status(id, CallSummaryStatus.FAILED) }
            finally {
                if (active?.first == id && active?.second === coroutineContext[Job]) { active = null; activeAutomatic = false }
                onCallStateChanged()
            }
        }
        active = id to job
        activeAutomatic = automatic
        job.start()
    }

    fun cancel(id: String) {
        pending.remove(id)
        active?.takeIf { it.first == id }?.let { (_, job) ->
            // Keep the slot until cancellation has unwound the HTTP request.
            status(id, CallSummaryStatus.CANCELED)
            job.cancel()
        }
    }

    fun forget(id: String) { cancel(id); mutable.value = mutable.value - id }
    fun clear() { pending.clear(); active?.first?.let(::cancel); mutable.value = emptyMap() }

    private fun status(id: String, value: CallSummaryStatus) {
        val validIds = history.state.value.map { it.id }.toSet()
        mutable.value = mutable.value.filterKeys { it in validIds } + if (id in validIds) mapOf(id to value) else emptyMap()
    }
}
