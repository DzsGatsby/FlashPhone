package com.wangzan101.mika.calls.voiceclone

import com.wangzan101.mika.calls.VolcRealtimeCredentials
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcVoiceCloneClientTest {
    @Test
    fun uploadsOneSampleWithV3ProjectKeyAndPollsWithoutTheSample() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"speaker_id":"S_example","status":1,"available_training_times":13}"""))
            server.enqueue(MockResponse().setBody(
                """{"speaker_id":"S_example","status":2,"available_training_times":13,"speaker_status":[{"model_type":5,"demo_audio":"https://x.bytespeech.com/a?signature=mock"}]}""",
            ))
            val client = VolcVoiceCloneClient(OkHttpClient(), server.url("/"))
            val wav = sample()
            try {
                val trained = runBlocking { client.train(AUTH, SLOT, wav, "这是一句照稿朗读的文字。") }
                assertEquals(VoiceCloneStatus.TRAINING, trained.status)
                val upload = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("POST", upload.method)
                assertEquals("/api/v3/tts/voice_clone", upload.path)
                assertEquals("application/json", upload.getHeader("Content-Type"))
                assertEquals("mock-api-key", upload.getHeader("X-Api-Key"))
                assertNull(upload.getHeader("X-Api-Resource-Id"))
                assertNotNull(UUID.fromString(upload.getHeader("X-Api-Request-Id")))
                val body = Json.parseToJsonElement(upload.body.readUtf8()).jsonObject
                assertEquals(SLOT, body.getValue("speaker_id").jsonPrimitive.content)
                assertEquals("0", body.getValue("language").jsonPrimitive.content)
                assertEquals("这是一句照稿朗读的文字。", body.getValue("text").jsonPrimitive.content)
                assertNull(body["model_type"])
                val audio = body.getValue("audio").jsonObject
                assertEquals("wav", audio.getValue("format").jsonPrimitive.content)
                assertTrue(wav.readBytes().contentEquals(Base64.getDecoder().decode(audio.getValue("data").jsonPrimitive.content)))

                val ready = runBlocking { client.query(AUTH, SLOT) }
                assertEquals(VoiceCloneStatus.READY, ready.status)
                assertEquals(13, ready.remainingTraining)
                assertEquals("x.bytespeech.com", ready.demo?.host)
                val query = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/api/v3/tts/get_voice", query.path)
                assertEquals(setOf("speaker_id"), Json.parseToJsonElement(query.body.readUtf8()).jsonObject.keys)
                assertFalse(upload.getHeader("X-Api-Request-Id") == query.getHeader("X-Api-Request-Id"))
            } finally {
                wav.delete()
            }
        }
    }

    @Test
    fun rejectsLegacyCredentialsAndCustomPostpaidSlotsBeforeNetwork() {
        MockWebServer().use { server ->
            val client = VolcVoiceCloneClient(OkHttpClient(), server.url("/"))
            val legacy = VolcRealtimeCredentials(appId = "fake-app", accessKey = "fake-key")
            assertEquals(VoiceCloneError.API_KEY_REQUIRED, assertThrows(VoiceCloneFailure::class.java) {
                runBlocking { client.query(legacy, SLOT) }
            }.error)
            assertEquals(VoiceCloneError.SPEAKER_ID_INVALID, assertThrows(VoiceCloneFailure::class.java) {
                runBlocking { client.query(AUTH, "custom_speaker_id") }
            }.error)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun uploadIsNotReplayedOn503WithImmediateRetryHint() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"status":2}"""))
            val client = VolcVoiceCloneClient(OkHttpClient(), server.url("/"))
            val file = sample()
            try {
                val e = assertThrows(VoiceCloneFailure::class.java) {
                    runBlocking { client.train(AUTH, SLOT, file, "照稿朗读。") }
                }
                assertEquals(VoiceCloneError.SERVER, e.error)
                assertEquals(1, server.requestCount)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun serverTextDoesNotLeakAndDemoUrlsAreRestricted() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"code":45001109,"message":"secret-request-payload"}"""))
            val client = VolcVoiceCloneClient(OkHttpClient(), server.url("/"))
            val e = assertThrows(VoiceCloneFailure::class.java) { runBlocking { client.query(AUTH, SLOT) } }
            assertEquals(VoiceCloneError.WER_MISMATCH, e.error)
            assertFalse(e.toString().contains("secret-request-payload"))
            assertTrue(VolcVoiceCloneClient.officialDemoUrl("https://x.bytespeech.com/test".toHttpUrl()))
            assertFalse(VolcVoiceCloneClient.officialDemoUrl("http://x.bytespeech.com/test".toHttpUrl()))
            assertFalse(VolcVoiceCloneClient.officialDemoUrl("https://bytespeech.com.example.net/test".toHttpUrl()))
        }
    }

    private fun sample(): File = File.createTempFile("voice-clone-test", ".wav").also {
        VoiceCloneWav.write(it, ByteArray(VoiceCloneWav.MIN_PCM_BYTES), VoiceCloneWav.MIN_PCM_BYTES)
    }

    companion object {
        private val AUTH = VolcRealtimeCredentials(apiKey = "mock-api-key")
        private const val SLOT = "S_example"
    }
}
