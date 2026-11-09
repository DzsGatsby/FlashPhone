package com.wangzan101.mika.calls.voiceclone

import com.wangzan101.mika.calls.VolcRealtimeCredentials
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

enum class VoiceCloneStatus { NOT_FOUND, TRAINING, READY, FAILED, ACTIVE, UNKNOWN }

enum class VoiceCloneError {
    SPEAKER_ID_INVALID, MIC_PERMISSION, CALL_BUSY, RECORDING_FAILED, RECORDING_INTERRUPTED,
    AUDIO_LENGTH, PLAYBACK_FAILED, API_KEY_REQUIRED, AUTH, RATE_LIMITED, WER_MISMATCH,
    REQUEST_REJECTED, SERVER, NETWORK, INVALID_RESPONSE, TRAINING_FAILED, NO_TRAINING_LEFT,
    STATUS_TIMEOUT, DEMO_EXPIRED, SAVE_FAILED, REFRESH_NEEDED,
}

internal class VoiceCloneFailure(val error: VoiceCloneError) : IOException(error.name)

internal class VoiceCloneResult(
    val status: VoiceCloneStatus,
    val remainingTraining: Int?,
    val demo: HttpUrl?,
)

/** Uses existing S_ slots only. Neither training nor status requests are retried by OkHttp. */
internal class VolcVoiceCloneClient(
    http: OkHttpClient = OkHttpClient(),
    private val base: HttpUrl = "https://openspeech.bytedance.com/".toHttpUrl(),
) {
    private val client = http.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun train(
        credentials: VolcRealtimeCredentials?,
        speakerId: String,
        sample: File,
        text: String,
    ): VoiceCloneResult {
        val wav = withContext(Dispatchers.IO) {
            if (!sample.isFile || sample.length() !in VoiceCloneWav.MIN_WAV_BYTES..VoiceCloneWav.MAX_WAV_BYTES) {
                throw VoiceCloneFailure(VoiceCloneError.AUDIO_LENGTH)
            }
            try {
                sample.readBytes()
            } catch (_: IOException) {
                throw VoiceCloneFailure(VoiceCloneError.RECORDING_FAILED)
            }
        }
        return try {
            val body = withContext(Dispatchers.Default) {
                buildJsonObject {
                    put("speaker_id", speakerId)
                    putJsonObject("audio") {
                        put("data", Base64.getEncoder().encodeToString(wav))
                        put("format", "wav")
                    }
                    put("text", text)
                    put("language", 0)
                    putJsonObject("extra_params") { put("demo_text", DEMO_TEXT) }
                }
            }
            post("voice_clone", credentials, speakerId, body)
        } finally {
            wav.fill(0)
        }
    }

    suspend fun query(credentials: VolcRealtimeCredentials?, speakerId: String): VoiceCloneResult =
        post("get_voice", credentials, speakerId, buildJsonObject { put("speaker_id", speakerId) })

    private suspend fun post(
        operation: String,
        credentials: VolcRealtimeCredentials?,
        speakerId: String,
        body: JsonObject,
    ): VoiceCloneResult {
        if (!validSpeakerId(speakerId)) throw VoiceCloneFailure(VoiceCloneError.SPEAKER_ID_INVALID)
        val auth = credentials ?: throw VoiceCloneFailure(VoiceCloneError.API_KEY_REQUIRED)
        val payload = withContext(Dispatchers.Default) {
            body.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON)
        }
        val request = Request.Builder()
            .url(checkNotNull(base.resolve("api/v3/tts/$operation")))
            // Even HTTP Retry-After follow-ups must not replay a training submission.
            .post(object : RequestBody() {
                override fun contentType(): MediaType? = payload.contentType()
                override fun contentLength(): Long = payload.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = payload.writeTo(sink)
            })
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .apply { auth.applyHeaders(this) }
            .build()
        // The existing credentials helper supports older real-time authentication as well.
        // This V3 client deliberately refuses that form before opening a connection.
        if (request.header("X-Api-Key") == null) throw VoiceCloneFailure(VoiceCloneError.API_KEY_REQUIRED)

        return execute(request) { response ->
            val json = readObject(response)
            val code = (json?.get("code") as? JsonPrimitive)?.intOrNull
            if (!response.isSuccessful || code == WER_ERROR) {
                throw VoiceCloneFailure(httpError(response.code, code))
            }
            val reply = json ?: throw VoiceCloneFailure(VoiceCloneError.INVALID_RESPONSE)
            val returnedSpeaker = (reply["speaker_id"] as? JsonPrimitive)?.content
            if (returnedSpeaker != null && returnedSpeaker != speakerId) {
                throw VoiceCloneFailure(VoiceCloneError.INVALID_RESPONSE)
            }
            val wireState = (reply["status"] as? JsonPrimitive)?.intOrNull
                ?: throw VoiceCloneFailure(VoiceCloneError.INVALID_RESPONSE)
            val status = when (wireState) {
                0 -> VoiceCloneStatus.NOT_FOUND
                1 -> VoiceCloneStatus.TRAINING
                2 -> VoiceCloneStatus.READY
                3 -> VoiceCloneStatus.FAILED
                4 -> VoiceCloneStatus.ACTIVE
                else -> VoiceCloneStatus.UNKNOWN
            }
            val remaining = (reply["available_training_times"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            val previews = (reply["speaker_status"] as? JsonArray).orEmpty().mapNotNull { row ->
                val entry = row as? JsonObject ?: return@mapNotNull null
                val kind = (entry["model_type"] as? JsonPrimitive)?.intOrNull ?: 0
                val url = (entry["demo_audio"] as? JsonPrimitive)?.content?.toHttpUrlOrNull()
                    ?.takeIf(::officialDemoUrl) ?: return@mapNotNull null
                kind to url
            }
            VoiceCloneResult(status, remaining, previews.maxByOrNull { it.first }?.second)
        }
    }

    /** Audio is downloaded only for an explicit preview action; this request never has a Key. */
    suspend fun downloadDemo(url: HttpUrl, destination: File) {
        if (!officialDemoUrl(url)) throw VoiceCloneFailure(VoiceCloneError.DEMO_EXPIRED)
        try {
            execute(Request.Builder().url(url).get().build()) { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    if (response.code in setOf(403, 404, 410)) throw VoiceCloneFailure(VoiceCloneError.DEMO_EXPIRED)
                    throw VoiceCloneFailure(httpError(response.code, null))
                }
                val declared = body.contentLength()
                if (declared > MAX_DEMO_BYTES) throw VoiceCloneFailure(VoiceCloneError.INVALID_RESPONSE)
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            total += size
                            if (total > MAX_DEMO_BYTES) throw VoiceCloneFailure(VoiceCloneError.INVALID_RESPONSE)
                            output.write(buffer, 0, size)
                        }
                        if (total == 0L) throw VoiceCloneFailure(VoiceCloneError.INVALID_RESPONSE)
                    }
                }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    private suspend fun <T> execute(request: Request, consume: (Response) -> T): T =
        suspendCancellableCoroutine { result ->
            val call = client.newCall(request)
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val kind = if (e is SocketTimeoutException) VoiceCloneError.STATUS_TIMEOUT else VoiceCloneError.NETWORK
                    result.resumeWithException(VoiceCloneFailure(kind))
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching { response.use(consume) }
                    outcome.fold({ result.resume(it) }) { failure ->
                        result.resumeWithException(
                            (failure as? VoiceCloneFailure)
                                ?: VoiceCloneFailure(if (failure is IOException) VoiceCloneError.NETWORK else VoiceCloneError.INVALID_RESPONSE),
                        )
                    }
                }
            })
        }

    private fun readObject(response: Response): JsonObject? {
        val text = response.peekBody(MAX_RESPONSE_BYTES + 1).use { body ->
            if (body.contentLength() > MAX_RESPONSE_BYTES) return null
            body.string()
        }
        return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private fun httpError(status: Int, providerCode: Int?): VoiceCloneError = when {
        providerCode == WER_ERROR -> VoiceCloneError.WER_MISMATCH
        status == 401 || status == 403 -> VoiceCloneError.AUTH
        status == 429 -> VoiceCloneError.RATE_LIMITED
        status == 408 -> VoiceCloneError.STATUS_TIMEOUT
        status >= 500 -> VoiceCloneError.SERVER
        else -> VoiceCloneError.REQUEST_REJECTED
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val SLOT = Regex("S_[A-Za-z0-9_-]{1,98}")
        private const val WER_ERROR = 45001109
        private const val MAX_RESPONSE_BYTES = 64L * 1024
        private const val MAX_DEMO_BYTES = 6L * 1024 * 1024
        private const val DEMO_TEXT = "您好，我是 Flash phone AI 电话助手，请问有什么需要转告？"

        fun validSpeakerId(value: String): Boolean = SLOT.matches(value)

        internal fun officialDemoUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            (url.host == "bytespeech.com" || url.host.endsWith(".bytespeech.com"))
    }
}
