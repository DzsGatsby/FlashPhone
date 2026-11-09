package com.wangzan101.mika.calls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Pipes carry 16 kHz input and 24 kHz output, PCM16 LE mono. Nothing is written to storage. */
internal class CallPcmBridge(
    private val capture: InputStream,
    private val playback: OutputStream,
    private val interruptPlayback: suspend () -> Boolean,
) {
    private data class Frame(val generation: Int, val pcm: ByteArray)

    suspend fun run(
        session: VolcRealtimeSession,
        greeting: String,
        counters: CallMediaCounters?,
        onTranscript: (String, String) -> Unit,
    ): Unit = coroutineScope {
        val playbackQueue = Channel<Frame>(16)
        val generation = AtomicInteger(0)
        val writerLock = Mutex()
        val assistantText = StringBuilder()
        fun flushAssistantText() {
            if (assistantText.isNotBlank()) onTranscript("assistant", assistantText.toString())
            assistantText.clear()
        }
        // The separate writer leaves model event collection responsive when a pipe is full.
        val output = launch(Dispatchers.IO) {
            for (frame in playbackQueue) {
                var written = 0
                while (written < frame.pcm.size && generation.get() == frame.generation) {
                    val count = (frame.pcm.size - written).coerceAtMost(960) // 20 ms
                    val accepted = writerLock.withLock {
                        if (generation.get() != frame.generation) return@withLock false
                        playback.write(frame.pcm, written, count)
                        true
                    }
                    if (!accepted) break
                    written += count
                }
            }
        }
        try {
            if (!session.setMuted(false) || !session.sendGreeting(greeting)) {
                throw CallMediaFailure("火山未接受本次通话的开场白")
            }
            val input = launch(Dispatchers.IO) {
                val frame = ByteArray(VolcRealtimeClient.INPUT_CHUNK_BYTES)
                var position = 0
                while (true) {
                    val count = capture.read(frame, position, frame.size - position)
                    if (count < 0) throw CallMediaFailure("运营商音频输入已断开")
                    if (count == 0) continue
                    position += count
                    if (position == frame.size) {
                        if (!session.sendPcm16(frame)) throw CallMediaFailure("火山音频输入已中断")
                        counters?.inputAccepted(frame, System.nanoTime())
                        position = 0
                    }
                }
            }
            try {
                session.events.collect { event ->
                    counters?.eventReceived(event)
                    when (event) {
                        is VolcRealtimeEvent.Audio -> if (event.pcm.isNotEmpty() &&
                            !playbackQueue.trySend(Frame(generation.get(), event.pcm)).isSuccess) {
                            throw CallMediaFailure("通话音频输出拥塞，已交还系统")
                        }
                        VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                            generation.incrementAndGet()
                            while (playbackQueue.tryReceive().isSuccess) Unit
                            // First release any writer blocked on a full pipe. Flush again under
                            // the writer lock to remove the last chunk that raced the first flush.
                            val cleared = interruptPlayback() && withTimeoutOrNull(750) {
                                writerLock.withLock { interruptPlayback() }
                            } == true
                            if (!cleared) throw CallMediaFailure("通话音频无法及时停止，已交还系统")
                            flushAssistantText()
                        }
                        is VolcRealtimeEvent.UserTranscript -> if (event.isFinal) onTranscript("caller", event.text)
                        is VolcRealtimeEvent.AssistantTranscript -> {
                            val room = CallHistory.MAX_LINE_LENGTH - assistantText.length
                            if (room > 0) assistantText.append(event.delta.take(room))
                        }
                        VolcRealtimeEvent.AssistantTextEnded -> flushAssistantText()
                        is VolcRealtimeEvent.Failed -> throw event.error
                        VolcRealtimeEvent.Closed -> throw CallMediaFailure("火山语音会话已结束，电话由系统处理")
                        else -> Unit
                    }
                }
                throw CallMediaFailure("火山语音连接已断开，电话由系统处理")
            } finally {
                input.cancel()
                flushAssistantText()
            }
        } finally {
            // Closing both FDs is what releases a blocking read or write in a cancelled child.
            playbackQueue.cancel()
            output.cancel()
            runCatching { capture.close() }
            runCatching { playback.close() }
        }
    }
}

internal class CallMediaFailure(message: String) : Exception(message)
