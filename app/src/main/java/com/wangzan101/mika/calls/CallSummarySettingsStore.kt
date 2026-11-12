package com.wangzan101.mika.calls

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CallSummarySettings(
    val providerId: String? = null,
    val model: String? = null,
    val autoGenerate: Boolean = true,
    val version: Int = 1,
) {
    override fun toString() = "CallSummarySettings(configured=${providerId != null}, autoGenerate=$autoGenerate)"
}

/** Dedicated settings; never changes the chat model roles or any owner conversation. */
class CallSummarySettingsStore internal constructor(directory: File) {
    private val file = File(directory, "call-summary-settings.json")
    private val json = Json { encodeDefaults = true }
    private val mutable = MutableStateFlow(CallSummarySettings())
    val state = mutable.asStateFlow()
    private val failed = MutableStateFlow(false)
    val error = failed.asStateFlow()
    private var corruptRead = false

    init {
        try {
            if (file.exists()) mutable.value = decode(file.readTextBounded())
        } catch (_: Exception) { failed.value = true; corruptRead = true }
    }

    @Synchronized
    fun select(providerId: String, model: String): Boolean = update(
        mutable.value.copy(providerId = providerId.trim(), model = model.trim()),
    )

    @Synchronized
    fun clearSelection(): Boolean = update(mutable.value.copy(providerId = null, model = null))

    @Synchronized
    fun setAutoGenerate(enabled: Boolean): Boolean =
        if (corruptRead) false else update(mutable.value.copy(autoGenerate = enabled))

    private fun update(next: CallSummarySettings): Boolean {
        var temp: File? = null
        return try {
            validate(next)
            file.parentFile?.mkdirs()
            val payload = json.encodeToString(next).toByteArray(Charsets.UTF_8)
            temp = File.createTempFile("call-summary-", ".tmp", file.parentFile)
            FileOutputStream(temp).use { it.write(payload); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(decode(file.readTextBounded()) == next)
            mutable.value = next
            failed.value = false
            corruptRead = false
            true
        } catch (_: Exception) {
            failed.value = true
            false
        } finally { temp?.delete() }
    }

    private fun File.readTextBounded(): String {
        require(length() <= 2_048)
        return readText(Charsets.UTF_8).also { require(it.length <= 2_048) }
    }

    private fun decode(raw: String) = json.decodeFromString<CallSummarySettings>(raw).also(::validate)

    private fun validate(settings: CallSummarySettings) {
        require(settings.version == 1)
        require((settings.providerId == null) == (settings.model == null))
        require(settings.providerId == null || Regex("[A-Za-z0-9_-]{1,128}").matches(settings.providerId))
        require(settings.model == null || Regex("[A-Za-z0-9_./:@+\\-]{1,256}").matches(settings.model))
    }

    companion object {
        private val instances = mutableMapOf<String, CallSummarySettingsStore>()

        @Synchronized
        fun get(context: Context): CallSummarySettingsStore {
            val dir = context.applicationContext.noBackupFilesDir
            return instances.getOrPut(dir.absolutePath) { CallSummarySettingsStore(dir) }
        }
    }
}
