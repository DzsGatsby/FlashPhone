package com.wangzan101.mika.calls.codex

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Only a configuration reference. No thread, memory, fingerprint or credential escapes this reader. */
internal data class CodexSummarySelection(val providerId: String, val model: String)

/** Unlike AtomicFile.openRead this reader never recovers, renames, creates or writes owner state. */
internal fun readCodexSummarySelection(directory: File): CodexSummarySelection? {
    val base = File(directory, "calls-codex-selection.json")
    val backup = File(directory, "calls-codex-selection.json.bak")
    val source = if (backup.isFile) backup else base
    if (!source.exists()) return null
    val bytes = source.inputStream().use { input ->
        val buffer = ByteArray(1_025)
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        buffer.copyOf(length)
    }
    require(bytes.size <= 1_024) { "Invalid summary model selection" }
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        ?: error("Invalid summary model selection")
    require((root["version"] as? JsonPrimitive)?.longOrNull == 1L)
    require(root.keys.all { it in setOf("version", "providerId", "model", "threadId", "keyFingerprint") })
    fun string(key: String): String? = root[key]?.let {
        require(it is JsonPrimitive && it.isString)
        it.contentOrNull
    }
    val provider = string("providerId")
    val model = string("model")
    // Validate owner-only fields without returning them or opening an owner thread.
    val thread = string("threadId")
    val fingerprint = string("keyFingerprint")
    require(provider == null || Regex("[A-Za-z0-9_-]{1,128}").matches(provider))
    require(model == null || Regex("[A-Za-z0-9_.:-]{1,128}").matches(model))
    require((provider == null) == (model == null))
    require(thread == null || provider != null && Regex("[A-Za-z0-9_-]{1,128}").matches(thread))
    require(fingerprint == null || thread != null && Regex("[0-9a-f]{64}").matches(fingerprint))
    return if (provider == null || model == null) null else CodexSummarySelection(provider, model)
}
