package com.wangzan101.mika.calls

import com.wangzan101.mika.calls.codex.CodexSummarySelection
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.settings.*
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CallSummaryModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(false, false, 32_000, false)
    private var built = 0
    private var chosen = ""
    private var setup: ClientSetup? = null
    private var requestedTools: List<ToolSpec>? = null
    private var requestedMessages: List<Message>? = null
    private var complete: suspend () -> ModelReply = { ModelReply(listOf(Part.Text("{}")), StopReason.EndTurn, null) }

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, options ->
        built++
        chosen = cfg.providerId + "/" + cfg.model
        setup = options
        object : ChatModel {
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                requestedTools = tools
                requestedMessages = messages
                return complete()
            }
        }
    })

    private fun ProviderStore.official() = connect("", ProviderConfig.Kind.OpenAiResponses,
        "https://api.openai.com/v1", "synthetic-test-key", emptyList(), untested = true)

    @Test fun noConfigurationIsUnavailableAndNeverBuildsOrCallsOwner() = runBlocking {
        val model = CallSummaryModel(store()) { null }
        assertFalse(model.available())
        try { model.summarize("system", "synthetic"); fail() }
        catch (e: SummaryModelException) { assertEquals(SummaryModelFailure.NOT_CONFIGURED, e.kind) }
        assertEquals(0, built)
    }

    @Test fun selectedPhoneProviderWorksWithoutAnyMainOrLightRoleAndDoesNotMutateThem() = runBlocking {
        val providers = store()
        val p = providers.official()
        val model = CallSummaryModel(providers) { CodexSummarySelection(p.id, "gpt-test") }
        val before = tmp.root.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertTrue(model.available())
        assertEquals(0, built)
        val reply = model.summarize("bounded-system", "synthetic-call-line")
        assertEquals(p.id, reply.provider)
        assertEquals("gpt-test", reply.model)
        assertEquals(p.id + "/gpt-test", chosen)
        assertEquals(emptyList<ToolSpec>(), requestedTools)
        assertEquals(listOf(Part.Text("synthetic-call-line")), requestedMessages!!.single().parts)
        assertEquals(CallSummaryModel.MAX_OUTPUT_TOKENS, setup!!.maxOutputTokens)
        assertEquals(25, setup!!.readTimeoutSeconds)
        assertTrue(setup!!.singleRequest)
        assertNull(providers.configFor(ModelRole.Light))
        assertEquals(before, tmp.root.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test fun phoneSelectionTakesPriorityOverConfiguredLightModel() = runBlocking {
        val providers = store()
        val phone = providers.official()
        val light = providers.connect("Light", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1/v1", null, emptyList())
        providers.use(ModelRole.Light, light.id, "local", caps)
        val reply = CallSummaryModel(providers) { CodexSummarySelection(phone.id, "phone-model") }.summarize("s", "input")
        assertEquals(phone.id, reply.provider)
        assertEquals("phone-model", reply.model)
    }

    @Test fun configuredLightIsUsedOnlyWhenNoPhoneSelectionExists() = runBlocking {
        val providers = store()
        val light = providers.connect("Light", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1/v1", null, emptyList())
        providers.use(ModelRole.Light, light.id, "local", caps)
        val model = CallSummaryModel(providers) { null }
        assertTrue(model.available())
        assertEquals("local", model.summarize("s", "input").model)
    }

    @Test fun damagedOrMissingSelectedProviderDoesNotLeakToDifferentLightProvider() = runBlocking {
        val providers = store()
        val model = CallSummaryModel(providers) { CodexSummarySelection("missing", "model") }
        assertFalse(model.available())
        try { model.summarize("s", "input"); fail() }
        catch (e: SummaryModelException) { assertEquals(SummaryModelFailure.CONFIGURATION, e.kind) }
        assertEquals(0, built)
    }

    @Test fun inputLimitRejectsBeforeResolvingProviderAndOutputTruncationIsNotSuccess() = runBlocking {
        val providers = store()
        val p = providers.official()
        val model = CallSummaryModel(providers) { CodexSummarySelection(p.id, "model") }
        try { model.summarize("s", "x".repeat(CallSummaryModel.MAX_INPUT_CHARS + 1)); fail() }
        catch (e: SummaryModelException) { assertEquals(SummaryModelFailure.INPUT_LIMIT, e.kind) }
        assertEquals(0, built)
        complete = { ModelReply(listOf(Part.Text("partial")), StopReason.MaxTokens, null) }
        try { model.summarize("s", "input"); fail() }
        catch (e: SummaryModelException) { assertEquals(SummaryModelFailure.OUTPUT_LIMIT, e.kind) }
    }

    @Test fun cancellationPropagatesAndFailureDoesNotRetry() = runBlocking {
        val providers = store()
        val p = providers.official()
        val model = CallSummaryModel(providers) { CodexSummarySelection(p.id, "model") }
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        complete = { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
        val task = launch { model.summarize("s", "synthetic") }
        started.await()
        task.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertEquals(1, built)
        complete = { error("synthetic provider body should not escape") }
        try { model.summarize("s", "synthetic"); fail() }
        catch (e: SummaryModelException) {
            assertEquals(SummaryModelFailure.REQUEST, e.kind)
            assertFalse(e.message.orEmpty().contains("provider body"))
        }
        assertEquals(2, built)
    }

    @Test fun independentSummarySelectionWinsWithoutReadingOrChangingOwnerOrRoles() = runBlocking {
        val providers = store()
        val p = providers.official()
        val settings = CallSummarySettingsStore(tmp.newFolder("summary-settings"))
        assertTrue(settings.select(p.id, "summary-model"))
        val beforeRoles = providers.roles.value
        val model = CallSummaryModel(providers, settings) { error("Owner selection must not be touched") }
        assertTrue(model.available())
        assertEquals("summary-model", model.summarize("system", "synthetic").model)
        assertEquals(beforeRoles, providers.roles.value)
        providers.removeProvider(p.id)
        assertFalse(model.available())
        assertEquals(p.id, settings.state.value.providerId)
    }

    @Test fun productionSettingsRequireAnExplicitSelectionEvenWhenLegacyModelsExist() = runBlocking {
        val providers = store()
        val phone = providers.official()
        providers.use(ModelRole.Main, phone.id, "legacy-main", caps)
        val settings = CallSummarySettingsStore(tmp.newFolder("explicit-only"))
        val model = CallSummaryModel(providers, settings) { error("Production must not inspect owner selection") }
        assertFalse(model.available())
        try { model.summarize("system", "synthetic"); fail("Expected explicit selection requirement") }
        catch (e: SummaryModelException) { assertEquals(SummaryModelFailure.NOT_CONFIGURED, e.kind) }
        assertEquals(0, built)
        assertTrue(settings.select(phone.id, "explicit-model"))
        assertTrue(model.available())
        assertTrue(settings.clearSelection())
        assertFalse(model.available())
        assertEquals(0, built)
        assertEquals("legacy-main", providers.configFor(ModelRole.Main)!!.model)
    }

    @Test fun twoExplicitApiProvidersSwitchActualRequestsAndPersistWithoutChangingLightOrMain() = runBlocking {
        val first = MockWebServer()
        val second = MockWebServer()
        first.start(); second.start()
        try {
            fun response(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            first.enqueue(response("first")); second.enqueue(response("second"))
            val providers = ProviderStore(tmp.newFolder("providers"), AesGcmKeyCipher { key })
            val p1 = providers.connect("First", ProviderConfig.Kind.OpenAiCompat, first.url("/v1").toString(), "synthetic-1", emptyList())
            val p2 = providers.connect("Second", ProviderConfig.Kind.OpenAiCompat, second.url("/v1").toString(), "synthetic-2", emptyList())
            val dir = tmp.newFolder("selection")
            val settings = CallSummarySettingsStore(dir)
            assertTrue(settings.select(p1.id, "model-one"))
            assertEquals("first", CallSummaryModel(providers, settings) { null }.summarize("system", "synthetic").text)
            assertTrue(settings.select(p2.id, "model-two"))
            val reloaded = CallSummarySettingsStore(dir)
            assertEquals("second", CallSummaryModel(providers, reloaded) { null }.summarize("system", "synthetic").text)
            assertEquals(p2.id, reloaded.state.value.providerId)
            assertEquals(1, first.requestCount)
            assertEquals(1, second.requestCount)
            val body1 = first.takeRequest().body.readUtf8()
            val body2 = second.takeRequest().body.readUtf8()
            assertTrue(body1.contains("model-one")); assertTrue(body2.contains("model-two"))
            assertTrue(body1.contains("max_tokens")); assertTrue(body2.contains("max_tokens"))
            assertFalse(body1.contains("\"tools\""))
            assertNull(providers.configFor(ModelRole.Light))
            assertNull(providers.configFor(ModelRole.Main))
        } finally { first.shutdown(); second.shutdown() }
    }

    @Test fun realDeadlineCancelsTheInFlightModelAndReturnsTimeoutWithoutRetry() = runBlocking {
        val providers = store()
        val provider = providers.official()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        complete = { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
        val model = CallSummaryModel(providers, timeoutMs = 1_000L) { CodexSummarySelection(provider.id, "synthetic") }
        val before = System.nanoTime()
        try { model.summarize("system", "synthetic"); fail("Expected actual deadline") }
        catch (e: SummaryModelException) { assertEquals(SummaryModelFailure.TIMEOUT, e.kind) }
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        assertTrue("The real deadline must not fire instantly", elapsedMs >= 800L)
        assertTrue("Timeout must bound a hanging request", elapsedMs < 5_000L)
        assertTrue(started.isCompleted)
        assertTrue(stopped.isCompleted)
        assertEquals(1, built)
    }
}
