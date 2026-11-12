package com.wangzan101.mika.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual HTTP adapter + Android Keystore + encrypted history, isolated from all owner data. */
@RunWith(AndroidJUnit4::class)
class CallSummaryNetworkStorageTest {
    @get:Rule val emulatorOnly: org.junit.rules.TestRule = WorkspaceEmulatorOnlyRule()

    @Test fun httpSummaryIsEvidenceCheckedEncryptedAndReadableAfterStoreRecreation() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "summary-test-$suffix"
        val alias = "summary-provider-test-$suffix"
        val temp = File(context.cacheDir, prefsName).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefsName, mode)
        }
        val server = MockWebServer().apply { start() }
        try {
            val providers = ProviderStore(temp, KeystoreKeyCipher(alias), userAgent = "FlashPhone-summary-test")
            val provider = providers.connect("Local fixture", ProviderConfig.Kind.OpenAiCompat,
                server.url("/v1").toString().trimEnd('/'), "fixture-private-key", emptyList(), untested = true)
            val selection = CallSummarySettingsStore(File(temp, "summary"))
            assertTrue(selection.select(provider.id, "fixture-model"))
            val record = CallRecord("fixture-call", "number-must-not-be-sent", CallDirection.INCOMING, 1, 2, "ended",
                listOf(CallTranscriptLine("caller", "门店晚上九点关门。")))
            val raw = """{"highlights":[{"text":"营业至晚上九点","evidence":[{"lineId":1,"quote":"晚上九点关门"}]}],"confirmed":[],"followUps":[],"unknowns":[]}"""
            val chunk = buildJsonObject { putJsonArray("choices") { addJsonObject {
                put("index", 0); put("finish_reason", "stop"); putJsonObject("delta") { put("content", raw) }
            } } }.toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: [DONE]\n\n"))
            val model = CallSummaryModel(providers, selection) { error("Summary must not read owner state") }
            val prompt = CallSummaryEngine.buildPrompt(record)
            val output = model.summarize(prompt.system, prompt.user)
            val summary = CallSummaryEngine.parseResponse(output.text, record, output.provider, output.model)
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            val payload = request.body.readUtf8()
            assertEquals("/v1/chat/completions", request.path)
            assertFalse(payload.contains("number-must-not-be-sent"))
            assertFalse(Json.parseToJsonElement(payload).jsonObject.containsKey("tools"))
            assertEquals("1536", Json.parseToJsonElement(payload).jsonObject["max_tokens"].toString())
            assertEquals(1, server.requestCount)
            val history = CallHistory(isolated)
            assertEquals(CallHistory.Result.SAVED, history.append(record))
            assertEquals(CallHistory.Result.SAVED, history.setSummary(record.id, CallSummaryEngine.sourceFingerprint(record), summary))
            val onDisk = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(onDisk.contains("九点")); assertFalse(onDisk.contains("fixture-call"))
            assertTrue(onDisk.isNotEmpty())
            val reopened = CallHistory(isolated); reopened.load()
            assertEquals(summary, reopened.state.value.single().summary)
            assertEquals(record.transcript, reopened.state.value.single().transcript)
            assertEquals(CallHistory.Result.SAVED, reopened.delete(record.id))
            assertEquals(CallHistory.Result.NOT_FOUND, reopened.setSummary(record.id, summary.sourceFingerprint, summary))
            val providerFiles = temp.walkTopDown().filter { it.isFile }.map { it.readText() }.joinToString()
            assertFalse(providerFiles.contains("fixture-private-key"))
        } finally {
            server.shutdown()
            context.deleteSharedPreferences(prefsName)
            temp.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
