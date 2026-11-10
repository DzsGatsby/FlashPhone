package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private val releases = MikaLinks.RELEASES
    private val mainDigest = "sha256:" + "a".repeat(64)
    private val armDigest = "sha256:" + "b".repeat(64)
    private val allDigest = "sha256:" + "c".repeat(64)

    private val github = """
        {
          "tag_name": "v0.2.0",
          "name": "Mika 0.2.0",
          "html_url": "$releases/tag/v0.2.0",
          "published_at": "2026-11-05T08:30:00Z",
          "prerelease": false,
          "body": "### 新增\r\n- 附件面板\r\n",
          "assets": [
            {"name": "mika-0.2.0-gpl-sources.tar.gz", "browser_download_url": "https://github.com/x/src.tar.gz"},
            {"name": "mika-0.2.0.apk", "browser_download_url": "$releases/download/v0.2.0/mika-0.2.0.apk", "size": 12345, "digest": "$mainDigest"}
          ]
        }
    """.trimIndent()

    @Test
    fun readsGitHubRelease() {
        val r = Releases.fromGitHub(github)
        assertEquals("0.2.0", r.version)
        assertEquals("Mika 0.2.0", r.name)
        assertEquals("2026-11-05", r.date)
        assertEquals("$releases/tag/v0.2.0", r.pageUrl)
        assertEquals("$releases/download/v0.2.0/mika-0.2.0.apk", r.apkUrl)
        assertEquals(r.apkUrl, r.downloadUrl)
        assertEquals(12345L, r.apkSize)
        assertEquals(mainDigest, r.apkDigest)
        assertTrue(r.notes.contains("附件面板"))
    }

    @Test
    fun gitHubReleaseWithoutApkOrBody() {
        val r = Releases.fromGitHub("""{"tag_name":"0.3.0","name":"","body":null,"html_url":"$releases/tag/0.3.0","assets":[]}""")
        assertEquals("0.3.0", r.version)
        assertEquals("0.3.0", r.name)
        assertEquals("", r.notes)
        assertNull(r.apkUrl)
        assertNull(r.apkSize)
        assertNull(r.apkDigest)
        assertNull(r.date)
        assertEquals(r.pageUrl, r.downloadUrl)
    }

    @Test(expected = Exception::class)
    fun gitHubErrorBodyIsRejected() {
        Releases.fromGitHub("""{"message":"API rate limit exceeded","documentation_url":"https://docs.github.com"}""")
    }

    @Test
    fun storedReleaseRoundTrips() {
        val r = Releases.fromGitHub(github)
        assertEquals(r, Releases.fromJson(Releases.toJson(r)))
        val bare = r.copy(date = null, apkUrl = null, apkSize = null, apkDigest = null)
        assertEquals(bare, Releases.fromJson(Releases.toJson(bare)))
    }

    private val split = """
        {
          "tag_name": "v0.1.40",
          "html_url": "$releases/tag/v0.1.40",
          "assets": [
            {"name": "Mika-v0.1.40-arm64.apk", "browser_download_url": "$releases/download/v0.1.40/Mika-v0.1.40-arm64.apk", "size": 50, "digest": "$armDigest"},
            {"name": "Mika-v0.1.40.apk", "browser_download_url": "$releases/download/v0.1.40/Mika-v0.1.40.apk", "size": 80, "digest": "$allDigest"}
          ]
        }
    """.trimIndent()

    @Test
    fun arm64DevicePicksTheArm64Apk() {
        val r = Releases.fromGitHub(split, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("$releases/download/v0.1.40/Mika-v0.1.40-arm64.apk", r.apkUrl)
        assertEquals(50L, r.apkSize)
        assertEquals(armDigest, r.apkDigest)
    }

    @Test
    fun otherDevicesPickTheUniversalApk() {
        for (abis in listOf(listOf("x86_64", "x86"), listOf("armeabi-v7a", "armeabi"), emptyList())) {
            val r = Releases.fromGitHub(split, abis)
            assertEquals(abis.toString(), "$releases/download/v0.1.40/Mika-v0.1.40.apk", r.apkUrl)
            assertEquals(allDigest, r.apkDigest)
        }
    }

    @Test
    fun archApkForAnotherDeviceIsNotOffered() {
        val only = """{"tag_name":"v0.1.40","html_url":"$releases/tag/v0.1.40",
            "assets":[{"name":"Mika-v0.1.40-arm64.apk","browser_download_url":"$releases/download/v0.1.40/Mika-v0.1.40-arm64.apk"}]}"""
        val r = Releases.fromGitHub(only, listOf("x86_64", "x86"))
        assertNull(r.apkUrl)
        assertEquals(r.pageUrl, r.downloadUrl)
        assertEquals("$releases/download/v0.1.40/Mika-v0.1.40-arm64.apk", Releases.fromGitHub(only, listOf("arm64-v8a")).apkUrl)
    }

    @Test
    fun standaloneAndOriginalPackageAssetsCannotCrossUpdateChannels() {
        val standalone = "$releases/download/v0.2.0/FlashPhone-v0.2.0.apk"
        val mixed = github.replace("""{"name": "mika-0.2.0-gpl-sources.tar.gz", "browser_download_url": "https://github.com/x/src.tar.gz"}""",
            """{"name":"FlashPhone-v0.2.0.apk","browser_download_url":"$standalone"}""")
        val selected = Releases.fromGitHub(mixed, devPackage = true)
        assertEquals(standalone, selected.apkUrl)
        assertEquals(selected, Releases.fromJson(Releases.toJson(selected), devPackage = true))
        assertNull(Releases.fromJson(Releases.toJson(selected), devPackage = false)?.apkUrl)
        assertEquals(Releases.fromGitHub(github).apkUrl, Releases.fromGitHub(mixed, devPackage = false).apkUrl)
        assertNull(Releases.fromGitHub(github, devPackage = true).apkUrl)
        val legacy = mixed.replace("FlashPhone-", "Mika-Dev-")
        assertEquals(standalone.replace("FlashPhone-", "Mika-Dev-"), Releases.fromGitHub(legacy, devPackage = true).apkUrl)
    }

    @Test
    fun malformedAssetCannotDisableChecksOrHideReleaseNotes() {
        for (malformed in listOf("42", "{}", "\"sha256:short\"")) {
            val release = Releases.fromGitHub(github.replace("\"$mainDigest\"", malformed))
            assertNull(release.apkUrl)
            assertTrue(release.notes.contains("附件面板"))
        }
        val old = Releases.fromGitHub(github.replace("\"$mainDigest\"", "null"))
        assertNull(old.apkDigest)
        assertTrue(old.apkUrl != null)

        val malformedSize = Releases.fromGitHub(github.replace("12345", "-1"))
        assertNull(malformedSize.apkUrl)
    }

    @Test
    fun legacyReleasePagesAndAssetsAreNotTrusted() {
        val legacyReleases = "https://github.com/DzsGatsby/Mika/releases"
        assertThrows(IllegalArgumentException::class.java) {
            Releases.fromGitHub(github.replace(releases, legacyReleases))
        }
        val legacyAsset = github.replace("$releases/download", "$legacyReleases/download")
        val fallback = Releases.fromGitHub(legacyAsset)
        assertNull(fallback.apkUrl)
        assertEquals("$releases/tag/v0.2.0", fallback.downloadUrl)

        val current = Releases.fromGitHub(github)
        assertNull(Releases.fromJson(Releases.toJson(current.copy(pageUrl = "$legacyReleases/tag/v0.2.0"))))
    }
}
