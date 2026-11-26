package com.lik.iptvplayer.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 调起系统安装器安装新版本。
 *
 * 两个坑：
 * - Android 7+ 不能用 file:// 直传，必须走 FileProvider（否则 FileUriExposedException 闪退）；
 * - Android 8+ 还要单独授权「安装未知应用」，没授权时系统安装器会直接关掉，
 *   表现为「点了没反应」，所以这里先判断再引导。
 */
object ApkInstaller {

    fun dir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** 8.0 起每个应用单独控制「安装未知应用」。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到本应用的「安装未知应用」开关页；部分定制盒子没有这个页，调用方要接受失败。 */
    fun openUnknownSourcesSettings(context: Context): Boolean = runCatching {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        true
    }.getOrDefault(false)

    /** 返回 false 表示这台机器没有能处理 apk 的安装器（部分老盒子被裁剪掉了）。 */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk,
        )
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        true
    }.getOrDefault(false)

    /** 签名核对结果：只有 [MISMATCH] 才该拦，[UNKNOWN] 表示本机读不出来。 */
    enum class SigCheck { MATCH, MISMATCH, UNKNOWN }

    /**
     * 核对安装包的签名证书，是否是已安装版本的同一条 lineage。
     *
     * 为什么要有 [UNKNOWN]：Android 9 一类定制固件（HG680-GC）上
     * `getPackageArchiveInfo` 解析独立 APK 的 v3 签名会返回 null，包明明是好的也读不出来。
     * 早期版本把「读不出」当成「不一致」，直接把正版包拒了。
     * 现在读不出来就交给系统安装器判决——它对签名是强制校验，真不一致一定装不上，
     * 我们这层只负责把错误翻译成人话，不该反过来误杀。
     */
    fun checkSignature(context: Context, apk: File): SigCheck {
        val mine = runCatching { installedCerts(context) }.getOrDefault(emptySet())
        if (mine.isEmpty()) return SigCheck.UNKNOWN

        // 第一条路：系统解析 APK。flags 同时带新旧两个常量，兼容只认 GET_SIGNATURES 的 ROM。
        val fromApk = runCatching { archiveCerts(context, apk) }.getOrDefault(emptySet())
            // 第二条路：直接读 APK 里的 v1 签名证书（META-INF/*.RSA），不依赖 ROM 的解析器
            .ifEmpty { runCatching { v1Certs(apk) }.getOrDefault(emptySet()) }

        if (fromApk.isEmpty()) return SigCheck.UNKNOWN
        return if (fromApk.any { it in mine }) SigCheck.MATCH else SigCheck.MISMATCH
    }

    /** 老调用点用：除了确凿的「不一致」，都放行。 */
    fun signatureMatches(context: Context, apk: File): Boolean =
        checkSignature(context, apk) != SigCheck.MISMATCH

    @Suppress("DEPRECATION")
    private fun signFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun installedCerts(context: Context): Set<String> =
        signaturesOf(context.packageManager.getPackageInfo(context.packageName, signFlags()))

    @Suppress("DEPRECATION")
    private fun archiveCerts(context: Context, apk: File): Set<String> {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signFlags())
            ?: return emptySet()
        return signaturesOf(info)
    }

    /** 读 APK 的 v1 签名（JAR signing）证书。纯文件解析，绕开各 ROM 的 PackageParser 差异。 */
    private fun v1Certs(apk: File): Set<String> = java.util.zip.ZipFile(apk).use { zip ->
        val out = mutableSetOf<String>()
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        zip.entries().asSequence()
            .filter { e ->
                !e.isDirectory && e.name.startsWith("META-INF/", ignoreCase = true) && (
                    e.name.endsWith(".RSA", ignoreCase = true) ||
                        e.name.endsWith(".DSA", ignoreCase = true) ||
                        e.name.endsWith(".EC", ignoreCase = true)
                    )
            }
            .forEach { e ->
                runCatching {
                    zip.getInputStream(e).use { input ->
                        val cert = factory.generateCertificate(input)
                            as java.security.cert.X509Certificate
                        out += sha256(cert.encoded)
                    }
                }
            }
        out
    }

    private fun signaturesOf(info: android.content.pm.PackageInfo): Set<String> {
        val out = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return out
            // 两个来源都收：多签看 apkContentsSigners，证书轮换看 history
            runCatching { si.apkContentsSigners?.forEach { out += sha256(it.toByteArray()) } }
            runCatching { si.signingCertificateHistory?.forEach { out += sha256(it.toByteArray()) } }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.forEach { out += sha256(it.toByteArray()) }
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
