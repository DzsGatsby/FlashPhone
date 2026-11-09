package com.wangzan101.mika.about

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** App-owned download for users who configured a proxy. Only a complete, current transfer is published. */
class ProxiedApkDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Transfer(val target: File) {
        val part = File(target.path + ".part-" + UUID.randomUUID())
        var job: Job? = null
        var call: Call? = null
        var bytes = 0L
        var total = -1L
    }

    private val lock = Any()
    private var current: Transfer? = null

    fun start(url: String, file: File, expectedSize: Long? = null) = synchronized(lock) {
        cancel()
        val transfer = Transfer(file)
        current = transfer
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                fetchInto(http, url, transfer.part, expectedSize,
                    onCall = { call ->
                        synchronized(lock) {
                            if (current !== transfer) throw CancellationException()
                            transfer.call = call
                        }
                    },
                    publish = { part ->
                        synchronized(lock) {
                            if (current !== transfer || transfer.job?.isActive != true) throw CancellationException()
                            if (!part.renameTo(file)) throw IOException("cannot move download")
                        }
                    },
                    progress = { done, size ->
                        synchronized(lock) {
                            if (current === transfer) {
                                transfer.bytes = done
                                transfer.total = size
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An incomplete transfer leaves no final APK; state exposes Failed for retry.
            } finally {
                synchronized(lock) { transfer.call = null }
            }
        }
        transfer.job = job
        job.start()
        Unit
    }

    fun state(file: File): ApkDownloadState = synchronized(lock) {
        val transfer = current
        when {
            transfer?.job?.isActive == true && transfer.target == file ->
                ApkDownloadState(DownloadPhase.Downloading, transfer.bytes, transfer.total)
            file.isFile -> ApkDownloadState(DownloadPhase.Ready, file.length(), file.length())
            else -> ApkDownloadState(DownloadPhase.Failed)
        }
    }

    fun cancel() = synchronized(lock) {
        val transfer = current
        current = null
        transfer?.job?.cancel()
        // Interrupt a blocking read immediately; a cancelled transfer can no longer publish.
        transfer?.call?.cancel()
        transfer?.part?.delete()
        Unit
    }

    companion object {
        /** Download to a temporary file, leaving no final APK on a failed or cancelled request. */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            fetchInto(http, url, File(file.path + ".part"), expectedSize = null, onCall = onCall,
                publish = { if (!it.renameTo(file)) throw IOException("cannot move download") }, progress = progress)
        }

        private suspend fun fetchInto(
            http: OkHttpClient,
            url: String,
            part: File,
            expectedSize: Long?,
            onCall: (Call) -> Unit,
            publish: (File) -> Unit,
            progress: (Long, Long) -> Unit,
        ) {
            try {
                coroutineContext.ensureActive()
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val contentLength = body.contentLength()
                    if (expectedSize != null && contentLength >= 0 && contentLength != expectedSize) {
                        throw IOException("download size mismatch")
                    }
                    val size = expectedSize ?: contentLength
                    var done = 0L
                    progress(0, size)
                    coroutineContext.ensureActive()
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                done += n
                                if (size >= 0 && done > size) throw IOException("download size mismatch")
                                out.write(buf, 0, n)
                                progress(done, size)
                            }
                        }
                    }
                    if (done == 0L || size >= 0 && done != size) throw IOException("incomplete download")
                }
                coroutineContext.ensureActive()
                publish(part)
            } finally {
                part.delete()
            }
        }
    }
}
