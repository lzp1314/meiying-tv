package com.lik.iptvplayer.core

import android.content.Context
import android.content.Intent
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
}
