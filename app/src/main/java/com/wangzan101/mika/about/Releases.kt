package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 一个发布的版本:版本号(不带 v)、标题、更新说明(Markdown)、发布日期、发布页、APK 直链。 */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** 更新说明。 */
    val notes: String,
    /** 发布日期 yyyy-MM-dd;来源没写时为空。 */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** APK 附件的字节数;接口没给时为空。 */
    val apkSize: Long? = null,
    /** APK 附件的摘要,形如 `sha256:<hex>`;较早的附件没有,这时只靠包名、版本号和签名把关。 */
    val apkDigest: String? = null,
) {
    /** 「从 GitHub 下载」打开的地址:有 APK 直链就下 APK,没有就打开发布页。 */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * 读 GitHub 发布页接口(releases/latest)的版本信息:tag_name、name、body、published_at、html_url,
 * 以及 assets 里适合本机的 APK 的 browser_download_url(见 [pickApk])。
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }
    private val digestFormat = Regex("sha256:[0-9a-fA-F]{64}")
    private val releaseTags = "${MikaLinks.RELEASES}/tag".toHttpUrl()

    private data class Asset(val name: String, val url: String, val size: Long?, val digest: String?)

    /**
     * 发布里除了全架构的 `Mika-v<版本>.apk`,还可能有只带某个架构原生库的包,名字以 `-<后缀>.apk` 结尾。
     * 键是 Android 的 ABI 名,值是附件名后缀。
     */
    private val ABI_APK_SUFFIX = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "armeabi-v7a", "x86_64" to "x86_64", "x86" to "x86")

    /** [deviceAbis] 是本机支持的 ABI,按偏好排序(Build.SUPPORTED_ABIS);为空时只取全架构包。 */
    fun fromGitHub(text: String, deviceAbis: List<String> = emptyList(), devPackage: Boolean? = null): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val page = requireNotNull(officialPageUrl(o.str("html_url"))) { "untrusted release page" }
        require(page.pathSegments.last() == tag)
        val body = o.str("body").orEmpty()
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.apkAsset() }
            .filter { matchesPackage(it.name, devPackage) }
        val apk = pickApk(assets, deviceAbis)
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = body.trim(),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = page.toString(),
            apkUrl = apk?.url,
            apkSize = apk?.size,
            apkDigest = apk?.digest,
        )
    }

    /**
     * 本机支持某个架构、发布里又有这个架构的专用包时取专用包(更小);否则取全架构包。
     * 只有别的架构的专用包时不取(装不上),这时打开发布页。
     */
    private fun pickApk(apks: List<Asset>, deviceAbis: List<String>): Asset? {
        fun named(suffix: String) = apks.firstOrNull { it.name.endsWith("-$suffix.apk", ignoreCase = true) }
        val forDevice = deviceAbis.firstNotNullOfOrNull { abi -> ABI_APK_SUFFIX[abi]?.let(::named) }
        val universal = apks.firstOrNull { a -> ABI_APK_SUFFIX.values.none { a.name.endsWith("-$it.apk", ignoreCase = true) } }
        return forDevice ?: universal
    }

    private fun JsonObject.apkAsset(): Asset? {
        val name = str("name")?.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: return null
        val url = str("browser_download_url") ?: return null
        return verifiedMetadata(name, url, "size", "digest")
    }

    /** Missing metadata is supported for old releases; malformed metadata never disables verification. */
    private fun JsonObject.verifiedMetadata(name: String, url: String, sizeKey: String, digestKey: String): Asset? = runCatching {
        ApkIntegrity.requireOfficialUrl(url)
        val parsed = url.toHttpUrl()
        val fileName = parsed.pathSegments.last()
        require(name.isEmpty() || name == fileName)
        val size = if (missing(sizeKey)) null else requireNotNull(strOrNumber(sizeKey)?.toLongOrNull()?.takeIf { it > 0 })
        val digest = if (missing(digestKey)) null else requireNotNull(str(digestKey)?.takeIf(digestFormat::matches))
        Asset(fileName, parsed.toString(), size, digest)
    }.getOrNull()

    private fun JsonObject.missing(key: String): Boolean = this[key] == null || this[key] == JsonNull

    private fun matchesPackage(name: String, devPackage: Boolean?): Boolean {
        val standalone = name.startsWith("FlashPhone-", ignoreCase = true) ||
            name.startsWith("Mika-Dev-", ignoreCase = true)
        return devPackage == null || standalone == devPackage
    }

    private fun officialPageUrl(candidate: String?): HttpUrl? {
        val page = candidate?.toHttpUrlOrNull() ?: return null
        return page.takeIf {
            it.isHttps && it.host == releaseTags.host && it.port == releaseTags.port &&
                it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
                it.encodedPath.startsWith("${releaseTags.encodedPath}/") &&
                it.pathSegments.size == releaseTags.pathSegments.size + 1
        }
    }

    /** 存盘用:记下最近一次查到的版本,下次打开 App 时红点还在。 */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
    }

    fun fromJson(o: JsonObject, devPackage: Boolean? = null): ReleaseInfo? = runCatching {
        val version = requireNotNull(o.str("version"))
        val page = requireNotNull(officialPageUrl(o.str("pageUrl")))
        require(AppVersion.normalize(page.pathSegments.last()) == version)
        val apk = o.str("apkUrl")?.let { o.verifiedMetadata("", it, "apkSize", "apkDigest") }
            ?.takeIf { matchesPackage(it.name, devPackage) }
        ReleaseInfo(
            version = version, name = o.str("name") ?: version, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = page.toString(), apkUrl = apk?.url,
            apkSize = apk?.size, apkDigest = apk?.digest,
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
