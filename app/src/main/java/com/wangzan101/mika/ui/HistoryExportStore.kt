package com.wangzan101.mika.ui

import android.content.Context
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** Only opaque references belong in saved state; speech stays encrypted in non-backed-up storage. */
internal class HistoryExportStore(
    private val directory: File,
    private val cipher: KeyCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun create(text: String): String = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs())
        cleanupLocked(emptySet())
        check(entries().size < MAX_ENTRIES) { "Export storage is full" }
        val plain = text.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES) { "Export is too large" }
        val token = UUID.randomUUID().toString()
        val temporary = file(token, ".tmp")
        try {
            val encrypted = cipher.encrypt(plain)
            check(encrypted.size <= MAX_BYTES + 64)
            temporary.outputStream().use { it.write(encrypted); it.fd.sync() }
            check(temporary.renameTo(file(token, ".data")))
            token
        } finally {
            plain.fill(0)
            temporary.delete()
        }
    }

    /** The lock includes destination close. A recreated UI joins the same result instead of rewriting it. */
    fun deliver(token: String, destination: () -> OutputStream): Boolean = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs())
        val done = file(token, ".done")
        if (done.exists()) {
            when (done.takeIf { it.length() <= 6L }?.readText()) {
                "ok" -> return@synchronized true
                "failed" -> return@synchronized false
            }
            // An interrupted receipt write cannot turn a still-available snapshot into a failure.
            done.delete()
        }
        val source = file(token, ".data")
        var plain: ByteArray? = null
        val success = try {
            check(source.isFile && source.length() in 1..(MAX_BYTES + 64).toLong())
            val decoded = cipher.decrypt(source.readBytes())
            plain = decoded
            check(decoded.size <= MAX_BYTES)
            destination().use { it.write(decoded); it.flush() }
            true
        } catch (_: Exception) {
            false
        } finally {
            plain?.fill(0)
        }
        // Keep a tiny receipt until the UI acknowledges it, including across process recreation.
        val receipt = file(token, ".receipt")
        receipt.outputStream().use { it.write(if (success) "ok".toByteArray() else "failed".toByteArray()); it.fd.sync() }
        check(receipt.renameTo(done))
        source.delete()
        success
    }

    fun discard(token: String) = synchronized(lock) {
        listOf(".data", ".done", ".tmp", ".receipt").forEach { file(token, it).delete() }
    }

    fun cleanup(protected: Set<String>) = synchronized(lock) { cleanupLocked(protected) }

    private fun cleanupLocked(protected: Set<String>) {
        directory.listFiles().orEmpty().forEach { entry ->
            val token = entry.name.substringBeforeLast('.')
            if (validToken.matches(token) && token !in protected && now() - entry.lastModified() > MAX_AGE_MS) entry.delete()
        }
    }

    private fun entries(): Set<String> = directory.listFiles().orEmpty().mapNotNull { entry ->
        entry.name.substringBeforeLast('.').takeIf(validToken::matches)
    }.toSet()

    private fun file(token: String, suffix: String): File {
        require(validToken.matches(token))
        return File(directory, token + suffix)
    }

    companion object {
        private val lock = Any()
        private val validToken = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        internal const val MAX_ENTRIES = 8
        internal const val MAX_BYTES = 8 * 1024 * 1024
        internal const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        fun forContext(context: Context) = HistoryExportStore(
            File(context.applicationContext.noBackupFilesDir, "history-exports"),
            KeystoreKeyCipher("flash-phone-history-exports"),
        )
    }
}
