package com.wangzan101.mika.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadPhase { Idle, Downloading, Paused, Ready, Failed }

data class ApkDownloadState(
    val phase: DownloadPhase = DownloadPhase.Idle,
    val bytes: Long = 0,
    val total: Long = -1,
) {
    val percent: Int? get() = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}

internal fun requireNewerPackage(installedPackage: String, installedVersion: Long, archivePackage: String?, archiveVersion: Long) {
    if (archivePackage != installedPackage || archiveVersion <= installedVersion) {
        throw ApkRejected(ApkRejected.Reason.NotNewer)
    }
}

/**
 * 系统负责后台下载;只保存任务编号,进程重启后仍能查询、取消或安装。方法在 IO 线程调用。
 * 文件放在本应用的外部专属目录(别的 App 读写不到),同一时间只留一个;换版本、删除、装好后都清掉。
 * 设了网络代理(设置 › 网络代理)时系统下载服务不走它,改由 Mika 自己经代理在进程里下([ProxiedApkDownload])。
 */
class ApkUpdater(private val context: Context, private val net: NetProxy = NetProxy.shared) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
    private val directory: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("external files unavailable"), "updates")
    private val file: File? get() = prefs.getString("file", null)?.takeIf(updateName::matches)?.let { File(directory, it) }
    /** 这一份是 Mika 自己经代理下的(不是系统下载服务)。 */
    private val viaApp: Boolean get() = prefs.getBoolean("viaApp", false)

    fun state(release: ReleaseInfo): ApkDownloadState = synchronized(operations) {
        if (matches(release)) recorded() else ApkDownloadState()
    }

    private fun matches(release: ReleaseInfo): Boolean = release.apkUrl != null &&
        prefs.getString("url", null) == release.apkUrl &&
        prefs.getLong("size", -1L) == (release.apkSize ?: -1L) &&
        prefs.getString("digest", null).equals(release.apkDigest, ignoreCase = true)

    /** 记下的那个下载任务现在的状态;系统里查不到这个任务算失败。 */
    private fun recorded(): ApkDownloadState {
        val target = file
        if (viaApp) {
            val state = target?.let(proxied::state) ?: ApkDownloadState(DownloadPhase.Failed)
            if (state.phase == DownloadPhase.Failed) cleanParts(target)
            return state
        }
        val id = prefs.getLong("id", -1)
        if (id < 0) return ApkDownloadState(DownloadPhase.Failed)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val phase = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadPhase.Downloading
                    DownloadManager.STATUS_PAUSED -> DownloadPhase.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> if (target?.isFile == true) DownloadPhase.Ready else DownloadPhase.Failed
                    else -> DownloadPhase.Failed
                }
                val state = ApkDownloadState(phase, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (phase != DownloadPhase.Failed) return state
            }
        }
        if (runCatching { manager.remove(id) }.isSuccess) {
            target?.delete()
            prefs.edit().remove("id").commit()
        }
        return ApkDownloadState(DownloadPhase.Failed)
    }

    fun download(release: ReleaseInfo) = synchronized(operations) {
        val url = requireNotNull(release.apkUrl)
        ApkIntegrity.requireOfficialUrl(url)
        if (matches(release) && recorded().phase in
            listOf(DownloadPhase.Downloading, DownloadPhase.Paused, DownloadPhase.Ready)) return@synchronized
        cancel()
        val folder = directory
        check(folder.mkdirs() || folder.isDirectory)
        // 进程被杀、记录丢了时留下的旧文件也一并清掉,目录里只留这一次的。
        folder.listFiles()?.filter { updateName.matches(it.name) || partName.matches(it.name) }?.forEach { it.delete() }
        // remove 的后台清理可能晚于下一次下载,不能复用旧任务的文件名。
        val target = File(folder, "update-${UUID.randomUUID()}.apk")
        if (net.proxyFor(Uri.parse(url).host.orEmpty()) != null) {
            check(prefs.edit().asset(release, target).putBoolean("viaApp", true).commit()) { "could not save download" }
            proxied.start(url, target, release.apkSize)
            return@synchronized
        }
        val label = context.applicationInfo.loadLabel(context.packageManager)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("$label ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            // 只在下载中显示通知:完成后的通知一点就会绕过下面的校验直接打开安装包,所以不留。
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = manager.enqueue(request)
        if (!prefs.edit().asset(release, target).putLong("id", id).commit()) {
            manager.remove(id)
            error("could not save download")
        }
    }

    private fun SharedPreferences.Editor.asset(release: ReleaseInfo, target: File): SharedPreferences.Editor =
        putString("url", release.apkUrl).putLong("size", release.apkSize ?: -1L)
            .putString("digest", release.apkDigest).putString("file", target.name)

    fun cancel() = synchronized(operations) {
        if (viaApp) proxied.cancel()
        val id = prefs.getLong("id", -1)
        val removed = runCatching { if (id >= 0) manager.remove(id) }
        val target = file
        cleanParts(target)
        check(target == null || !target.exists() || target.delete())
        removed.getOrThrow()
        check(prefs.edit().clear().commit())
    }

    private fun cleanParts(target: File?) {
        if (target == null) return
        target.parentFile?.listFiles()?.filter { it.name == target.name + ".part" ||
            it.name.startsWith(target.name + ".part-") && partName.matches(it.name) }?.forEach { it.delete() }
    }

    /** Drop obsolete or unreadable APKs on startup; active or paused system downloads can resume. */
    fun dropInstalled() = synchronized(operations) {
        val target = file ?: return@synchronized
        if (recorded().phase != DownloadPhase.Ready) return@synchronized
        val archive = runCatching { archiveInfo(target, 0) }.getOrNull()
        if (archive?.packageName == context.packageName && archive.longVersionCode > installedInfo(0).longVersionCode) return@synchronized
        cancel()
    }

    /**
     * 只接受本应用、更高版本、同一签名的完整附件;没通过就删掉文件并抛 [ApkRejected]。
     * 系统安装器之后还会再验签名并要求用户确认。
     */
    fun installIntent(release: ReleaseInfo): Intent = synchronized(operations) {
        check(matches(release) && recorded().phase == DownloadPhase.Ready)
        ApkIntegrity.requireOfficialUrl(requireNotNull(release.apkUrl))
        val target = requireNotNull(file)
        try {
            ApkIntegrity.verify(target, release.apkSize, release.apkDigest)
            val archive = archiveInfo(target, SIGNING_FLAGS) ?: throw ApkRejected(ApkRejected.Reason.Corrupt)
            val installed = installedInfo(SIGNING_FLAGS)
            requireNewerPackage(context.packageName, installed.longVersionCode, archive.packageName, archive.longVersionCode)
            ApkIntegrity.requireSameSigner(installed.signers(), archive.signers(), archive.signerHistory())
        } catch (e: Exception) {
            val rejected = e as? ApkRejected ?: ApkRejected(ApkRejected.Reason.Corrupt)
            runCatching { cancel() }.exceptionOrNull()?.let(rejected::addSuppressed)
            throw rejected
        }
        // Both download paths give the installer the exact file whose bytes were just verified.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", target)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(target: File, flags: Int): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(target.path, flags)

    @Suppress("DEPRECATION")
    private fun installedInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    /** 当前签名证书;个别系统读安装包时不给 signingInfo,退回旧字段 signatures。 */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<String> =
        (signingInfo?.apkContentsSigners ?: signatures).orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()

    /** 轮换过签名的包带着历史证书;多个签名者时没有历史。 */
    private fun PackageInfo.signerHistory(): Set<String> {
        val info = signingInfo ?: return emptySet()
        if (info.hasMultipleSigners()) return emptySet()
        return info.signingCertificateHistory.orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    }

    private companion object {
        val operations = Any()
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val updateName = Regex("update-$UUID_PATTERN\\.apk")
        val partName = Regex("update-$UUID_PATTERN\\.apk\\.part(?:-$UUID_PATTERN)?")

        /** 经代理在进程里下的那一份,进程里只有一个。 */
        val proxied by lazy {
            ProxiedApkDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)))
        }

        @Suppress("DEPRECATION")
        const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
