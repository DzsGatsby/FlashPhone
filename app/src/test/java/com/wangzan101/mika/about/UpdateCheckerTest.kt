package com.wangzan101.mika.about

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var github: MockResponse = MockResponse().setResponseCode(500)
    private val hits = mutableListOf<String>()
    private var clock = 1_000_000_000L

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.path.orEmpty()
                return github
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun checker(current: String = "0.1.0") = UpdateChecker(
        tmp.root.resolve(UpdateChecker.CACHE_FILE_NAME), current,
        releaseApi = server.url("/api").toString(),
        now = { clock },
    )

    private fun releaseJson(tag: String) =
        """{"tag_name":"$tag","name":"Flash phone $tag","body":"- 新东西","html_url":"https://github.com/DzsGatsby/FlashPhone/releases/tag/$tag","assets":[]}"""

    private fun release(tag: String) = MockResponse().setBody(releaseJson(tag))

    @Test
    fun newerReleaseFromGitHubShowsUpdate() = runTest {
        github = release("v0.2.0")
        val c = checker()
        c.check(manual = true)
        val s = c.state.value
        assertEquals(CheckStatus.Done, s.status)
        assertEquals("0.2.0", s.update?.version)
        assertTrue(s.hasUpdate)
        assertEquals(1, s.manualRounds)
        assertEquals(listOf("/api"), hits)
        val req = server.takeRequest()
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("FlashPhone/"))
    }

    @Test
    fun defaultCheckerRequestsTheIndependentGitHubEndpoint() = runTest {
        val expected = "https://api.github.com/repos/DzsGatsby/FlashPhone/releases/latest"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(expected, request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(releaseJson("v1.0.9").toResponseBody()).build()
        }.build()
        val c = UpdateChecker(tmp.root.resolve(UpdateChecker.CACHE_FILE_NAME), "1.0.8", client = client)
        c.check(manual = true)
        assertEquals("1.0.9", c.state.value.update?.version)
    }

    @Test
    fun metadataRedirectCannotSwitchFeeds() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api") MockResponse().setResponseCode(302).addHeader("Location", "/legacy")
                else release("v99.0.0")
        }
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertNull(c.state.value.latest)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        github = release("v0.1.0")
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertNull(c.state.value.update)
        assertEquals("0.1.0", c.state.value.latest?.version)
    }

    /** GitHub 限流(403)就是这次没查到:只问 GitHub 一次,不去别处找。 */
    @Test
    fun gitHubRateLimitIsAFailure() = runTest {
        github = MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}""")
        val c = checker()
        c.check(manual = true)
        assertEquals(listOf("/api"), hits)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertNull(c.state.value.update)
    }

    @Test
    fun manualFailureIsReported() = runTest {
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertEquals(1, c.state.value.manualRounds)
        assertNull(c.state.value.update)
    }

    /** 自动检查失败不改界面上的状态;每次打开都查,只挡住两分钟内连着来的。 */
    @Test
    fun autoCheckIsSilentAndRunsOnEveryOpenButNotInBursts() = runTest {
        val c = checker()
        assertTrue(c.autoCheck())
        assertEquals(CheckStatus.Idle, c.state.value.status)
        assertEquals(0, c.state.value.manualRounds)
        val before = hits.size
        clock += UpdateSchedule.MIN_GAP_MS - 1
        assertFalse(c.autoCheck())
        assertEquals(before, hits.size)
        clock += 1
        github = release("v0.2.0")
        assertTrue(c.autoCheck())
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(CheckStatus.Idle, c.state.value.status)
    }

    /** 查到的版本存盘:重开 App 不联网也有红点;装上新版后红点消失;上次自动检查的时间也记着。 */
    @Test
    fun foundUpdateSurvivesRestart() = runTest {
        github = release("v0.2.0")
        checker().autoCheck()
        github = MockResponse().setResponseCode(500)

        val again = checker()
        again.load()
        assertEquals("0.2.0", again.state.value.update?.version)
        assertFalse("just checked", again.autoCheck())

        val upgraded = checker(current = "0.2.0")
        upgraded.load()
        assertNull(upgraded.state.value.update)
        assertNotNull(upgraded.state.value.latest)
    }

    @Test
    fun legacyCacheCannotSupplyAnUpdateOrSuppressFirstCheck() = runTest {
        val legacyLatest = """{"version":"99.0.0","pageUrl":"https://github.com/DzsGatsby/Mika/releases/tag/v99.0.0"}"""
        val legacyCache = """{"lastAttempt":$clock,"latest":$legacyLatest}"""
        val cache = tmp.root.resolve(UpdateChecker.CACHE_FILE_NAME)
        tmp.root.resolve("update-fork-v1.json").writeText(legacyCache)
        github = release("v0.2.0")

        // Unmarked old cache or a cache explicitly belonging to the old feed must both be ignored.
        val legacySource = "https://api.github.com/repos/DzsGatsby/Mika/releases/latest"
        for (contents in listOf(legacyCache, """{"source":"$legacySource","lastAttempt":$clock,"latest":$legacyLatest}""")) {
            cache.writeText(contents)
            val c = checker()
            c.load()
            assertNull(c.state.value.latest)
            assertTrue(c.autoCheck())
            assertEquals("0.2.0", c.state.value.update?.version)
        }
    }

    /** 同时进来的两次自动检查(查还没完)只真的查一次;隔十分钟再打开照样查。 */
    @Test
    fun concurrentAutoChecksHitGitHubOnce() = runTest {
        github = release("v0.2.0")
        val c = checker()
        val ran = listOf(async { c.autoCheck() }, async { c.autoCheck() }).awaitAll()
        assertEquals(1, ran.count { it })
        assertEquals(1, hits.size)
        clock += 10 * 60 * 1000L
        assertTrue(c.autoCheck())
        assertEquals(2, hits.size)
    }

    @Test
    fun scheduleRules() {
        assertTrue(UpdateSchedule.due(null, 5))
        assertFalse(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS - 1))
        assertTrue(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS))
        assertTrue("clock moved back", UpdateSchedule.due(10_000, 5_000))
    }
    @Test
    fun deliveryReceivesOnlyNewerVersionAndCannotBreakCheck() = runTest {
        var delivered: ReleaseInfo? = null
        val c = UpdateChecker(tmp.root.resolve("delivery.json"), "0.1.0",
            releaseApi = server.url("/api").toString(),
            onUpdate = { delivered = it; error("notifications disabled") })
        github = release("v0.2.0")
        c.check(manual = true)
        assertEquals("0.2.0", delivered?.version)
        assertEquals(CheckStatus.Done, c.state.value.status)
        github = release("v0.1.0")
        c.check(manual = true)
        assertNull(delivered)
    }

}
