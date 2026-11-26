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

    /**
     * 安装包的签名证书里，是不是有一张和当前已装版本相同。
     *
     * 更新通道在证书校验失败时会降级（见 [UpdateTransport]），真实性靠 update.json 里的
     * sha256 保证。这里再加一道：**包必须出自同一张签名证书**，否则就是被换过的包。
     * 系统安装器其实也会拦（签名不同装不上），但它报的是「应用未安装」，
     * 用户完全看不懂；这里提前拦下并给出人话。
     */
    fun signatureMatches(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return@runCatching false
        @Suppress("DEPRECATION")
        val mine = pm.getPackageInfo(context.packageName, flags)
        val a = signaturesOf(archive)
        val b = signaturesOf(mine)
        // 只要有一张证书对得上就放行：证书轮换期间新旧两张会同时存在
        a.isNotEmpty() && b.isNotEmpty() && a.any { it in b }
    }.getOrDefault(false)

    private fun signaturesOf(info: android.content.pm.PackageInfo): Set<String> {
        val out = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return out
            val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            certs?.forEach { out += sha256(it.toByteArray()) }
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
