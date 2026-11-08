package com.wangzan101.mika.calls

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credentials are encrypted together; never put decrypted fields in a state flow or an error. */
class CallSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-volc-realtime-calls"),
) {
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    internal val credentialsRevision = MutableStateFlow(0L)

    @Synchronized
    fun save(config: CallConfig): CallConfig {
        val defaults = CallConfig()
        require(config.allowedIncomingNumbers.size <= CallPolicy.MAX_ALLOWED_NUMBERS) { "最多可设置 100 个来电号码" }
        val numbers = config.allowedIncomingNumbers.map {
            requireNotNull(CallPolicy.incomingNumber(it)) { "自动接听名单中包含无效号码" }
        }.toSet()
        require(config.greeting.length <= CallPolicy.MAX_GREETING_CHARS) { "开场白过长" }
        require(config.systemPrompt.length <= CallPolicy.MAX_PROMPT_CHARS) { "通话说明过长" }
        val speaker = config.speaker.trim().ifBlank { defaults.speaker }
        require(speaker.length <= CallPolicy.MAX_SPEAKER_CHARS && speaker.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-" }) {
            "无效的火山音色编号"
        }
        val next = config.copy(
            allowedIncomingNumbers = numbers,
            audioVerified = config.audioVerified && config.audioMode == mutable.value.audioMode,
            maxCallSeconds = config.maxCallSeconds.coerceIn(15, CallPolicy.MAX_SECONDS),
            greeting = config.greeting.trim().ifBlank { defaults.greeting },
            systemPrompt = config.systemPrompt.trim(),
            speaker = speaker,
            credentialsConfigured = mutable.value.credentialsConfigured,
        )
        val committed = prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putBoolean("auto_answer", next.autoAnswer)
            .putBoolean("allow_all", next.allowAllIncoming)
            .putStringSet("allowlist", numbers)
            .putInt("max_seconds", next.maxCallSeconds)
            .putString("audio_mode", next.audioMode.name)
            .putString("audio_verified_build", Build.FINGERPRINT.takeIf { next.audioVerified })
            .putString("greeting", next.greeting)
            .putString("prompt", next.systemPrompt)
            .putString("speaker", next.speaker)
            .commit()
        if (!next.enabled || committed) mutable.value = next
        check(committed) { "无法保存电话设置" }
        return next
    }

    @Synchronized
    fun saveCredentials(appId: String, accessKey: String, apiKey: String) {
        val legacyId = appId.trim()
        val legacyKey = accessKey.trim()
        val modern = apiKey.trim()
        require(listOf(legacyId, legacyKey, modern).all { value ->
            value.length <= 1_024 && value.all { it.code in 33..126 }
        }) { "火山凭据格式无效" }
        val clear = legacyId.isEmpty() && legacyKey.isEmpty() && modern.isEmpty()
        require(clear || (modern.isNotEmpty() && legacyId.isEmpty() && legacyKey.isEmpty()) ||
            (modern.isEmpty() && legacyId.isNotEmpty() && legacyKey.isNotEmpty())) {
            "请仅填写 API Key，或同时填写旧版 App ID 和 Access Key"
        }
        val edit = prefs.edit()
        if (clear) {
            edit.remove(CREDENTIALS)
        } else {
            val bytes = "${modern}\n${legacyId}\n${legacyKey}".toByteArray()
            try {
                val encrypted = runCatching { cipher.encrypt(bytes) }.getOrElse { error("无法加密火山凭据") }
                edit.putString(CREDENTIALS, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            } finally {
                bytes.fill(0)
            }
        }
        val committed = edit.commit()
        if (committed || clear) {
            mutable.value = mutable.value.copy(credentialsConfigured = !clear)
            credentialsRevision.value++
        }
        check(committed) { "无法保存火山凭据" }
    }

    @Synchronized
    internal fun credentials(): VolcRealtimeCredentials? = runCatching {
        val stored = prefs.getString(CREDENTIALS, null) ?: return null
        val bytes = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
        try {
            val fields = bytes.toString(Charsets.UTF_8).split('\n')
            if (fields.size != 3) return null
            VolcRealtimeCredentials(apiKey = fields[0], appId = fields[1], accessKey = fields[2]).takeIf { it.valid }
        } finally {
            bytes.fill(0)
        }
    }.getOrNull()

    private fun read(): CallConfig = runCatching {
        val defaults = CallConfig()
        CallConfig(
            enabled = prefs.getBoolean("enabled", false),
            autoAnswer = prefs.getBoolean("auto_answer", false),
            allowAllIncoming = prefs.getBoolean("allow_all", false),
            allowedIncomingNumbers = prefs.getStringSet("allowlist", emptySet()).orEmpty()
                .mapNotNull(CallPolicy::incomingNumber).take(CallPolicy.MAX_ALLOWED_NUMBERS).toSet(),
            maxCallSeconds = prefs.getInt("max_seconds", CallPolicy.DEFAULT_SECONDS).coerceIn(15, CallPolicy.MAX_SECONDS),
            audioMode = prefs.getString("audio_mode", null)?.let(CallAudioMode::valueOf) ?: defaults.audioMode,
            audioVerified = prefs.getString("audio_verified_build", null)?.let { it == Build.FINGERPRINT } == true,
            credentialsConfigured = credentials() != null,
            greeting = prefs.getString("greeting", defaults.greeting).orEmpty().take(CallPolicy.MAX_GREETING_CHARS),
            systemPrompt = prefs.getString("prompt", "").orEmpty().take(CallPolicy.MAX_PROMPT_CHARS),
            speaker = prefs.getString("speaker", defaults.speaker).orEmpty().take(CallPolicy.MAX_SPEAKER_CHARS),
        )
    }.getOrDefault(CallConfig())

    companion object {
        private const val CREDENTIALS = "volc_credentials_v1"

        fun create(context: Context): CallSettings =
            CallSettings(context.getSharedPreferences("mika_call_settings", Context.MODE_PRIVATE))
    }
}
