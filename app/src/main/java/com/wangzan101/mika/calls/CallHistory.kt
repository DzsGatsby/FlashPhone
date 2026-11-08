package com.wangzan101.mika.calls

import android.content.Context
import android.util.Base64
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CallTranscriptLine(val role: String, val text: String) {
    override fun toString(): String = "CallTranscriptLine(role=$role)"
}

@Serializable
data class CallRecord(
    val id: String,
    val number: String?,
    val direction: CallDirection,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val result: String,
    val transcript: List<CallTranscriptLine>,
) {
    override fun toString(): String = "CallRecord(direction=$direction, result=$result)"
}

internal class CallHistory(context: Context) {
    private val prefs = context.getSharedPreferences("mika_call_history", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("mika-call-history")
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<CallRecord>>(emptyList())
    val state = mutable.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock { readOnce() }
    }

    suspend fun append(record: CallRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            readOnce()
            val next = (listOf(record) + mutable.value).take(MAX_CALLS)
            // Keep this call visible in the current process if local storage becomes unavailable.
            mutable.value = next
            runCatching {
                val bytes = Json.encodeToString(next).toByteArray()
                try {
                    prefs.edit().putString(KEY, Base64.encodeToString(cipher.encrypt(bytes), Base64.NO_WRAP)).commit()
                } finally {
                    bytes.fill(0)
                }
            }
            Unit
        }
    }

    private fun readOnce() {
        if (loaded) return
        loaded = true
        mutable.value = runCatching {
            val stored = prefs.getString(KEY, null)?.takeIf { it.length <= 600_000 } ?: return
            val bytes = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
            try {
                Json.decodeFromString<List<CallRecord>>(bytes.toString(Charsets.UTF_8)).take(MAX_CALLS)
            } finally {
                bytes.fill(0)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val MAX_CALLS = 12
        const val MAX_LINES = 32
        const val MAX_LINE_LENGTH = 240
        private const val KEY = "encrypted_calls_v1"
    }
}
