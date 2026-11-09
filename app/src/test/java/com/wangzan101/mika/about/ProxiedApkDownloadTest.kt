package com.wangzan101.mika.about

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 设了网络代理时更新包由 Mika 自己经代理下:请求确实发给代理,下完整才留下文件,出错不留半截。 */
class ProxiedApkDownloadTest {
    private lateinit var proxy: MockWebServer
    private val dir: File = Files.createTempDirectory("apk").toFile()

    @Before fun setUp() { proxy = MockWebServer().also { it.start() } }
    @After fun tearDown() { proxy.shutdown(); dir.deleteRecursively() }

    private fun client() = NetProxy { "http://${proxy.hostName}:${proxy.port}" }.client()

    @Test fun downloadsThroughTheProxyWithProgress() = runTest {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        proxy.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val target = File(dir, "update.apk")
        val seen = mutableListOf<Pair<Long, Long>>()
        ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { done, total -> seen += done to total }
        assertTrue(target.readBytes().contentEquals(body))
        assertEquals("GET http://releases.example.test/mika.apk HTTP/1.1", proxy.takeRequest().requestLine)
        assertEquals(body.size.toLong() to body.size.toLong(), seen.last())
        assertFalse(File(dir, "update.apk.part").exists())
    }

    @Test fun failedDownloadLeavesNoFile() = runTest {
        proxy.enqueue(MockResponse().setResponseCode(404))
        val target = File(dir, "update.apk")
        try {
            ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { _, _ -> }
            fail("expected an error")
        } catch (e: IOException) {
            assertFalse(target.exists())
            assertFalse(File(dir, "update.apk.part").exists())
        }
    }

    @Test fun interruptedTransferLeavesNoApkOrPartialFile() = runTest {
        proxy.enqueue(MockResponse().setBody("x".repeat(200_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val target = File(dir, "update.apk")
        try {
            ProxiedApkDownload.fetch(client(), "http://releases.example.test/mika.apk", target) { _, _ -> }
            fail("expected interrupted transfer")
        } catch (_: IOException) {
            assertFalse(target.exists())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancelledTransferCannotPublishAnApkOrInterfereWithItsReplacement() = runBlocking {
        proxy.enqueue(MockResponse().setBody("x".repeat(200_000)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        proxy.enqueue(MockResponse().setBody("new APK"))
        val download = ProxiedApkDownload(client())
        val old = File(dir, "old.apk")
        val replacement = File(dir, "replacement.apk")
        try {
            download.start("http://releases.example.test/old.apk", old, expectedSize = 200_000)
            withTimeout(5_000L) {
                while (dir.listFiles().orEmpty().none { it.name.startsWith("old.apk.part-") }) delay(10L)
            }
            download.cancel()
            download.start("http://releases.example.test/replacement.apk", replacement, expectedSize = 7)
            withTimeout(5_000L) {
                while (download.state(replacement).phase == DownloadPhase.Downloading) delay(10L)
            }
            assertEquals(DownloadPhase.Ready, download.state(replacement).phase)
            assertEquals("new APK", replacement.readText())
            assertFalse(old.exists())
            assertEquals(listOf(replacement), dir.listFiles().orEmpty().toList())
        } finally {
            download.cancel()
        }
    }

    @Test fun rejectsResponseThatDoesNotMatchTheReleaseSize() = runBlocking {
        proxy.enqueue(MockResponse().setBody("bad"))
        val download = ProxiedApkDownload(client())
        val target = File(dir, "bad.apk")
        try {
            download.start("http://releases.example.test/bad.apk", target, expectedSize = 12)
            withTimeout(5_000L) {
                while (download.state(target).phase == DownloadPhase.Downloading) delay(10L)
            }
            assertEquals(DownloadPhase.Failed, download.state(target).phase)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            download.cancel()
        }
    }

    @Test fun stateFollowsTheFile() {
        val d = ProxiedApkDownload(client())
        val target = File(dir, "update.apk")
        assertEquals(DownloadPhase.Failed, d.state(target).phase)
        target.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(DownloadPhase.Ready, d.state(target).phase)
    }
}
