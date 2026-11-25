package com.lik.iptvplayer.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 现场诊断：把未捕获异常写到 filesDir/crash.log，供低配盒子闪退后取证。
 *
 * 为什么必须自己写：这类运营商盒子上 adb 常常连不上，
 * 崩溃一闪而过不留痕，没有堆栈就只能靠猜。日志限制 256KB，只保留最近的现场。
 */
object Diag {
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun append(context: Context, thread: Thread, error: Throwable) {
        val f = File(context.filesDir, FILE)
        if (f.exists() && f.length() > MAX_BYTES) f.delete()
        val text = buildString {
            appendLine("=== ${stamp()} ===")
            appendLine(deviceInfo(context))
            appendLine("thread: ${thread.name}")
            appendLine(error.stackTraceToString())
        }
        f.appendText(text)
    }

    fun deviceInfo(context: Context): String {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mem) }
        return buildString {
            append("model=${Build.MANUFACTURER} ${Build.MODEL}")
            append(" | android=${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})")
            append(" | heapMax=${rt.maxMemory() / mb}MB")
            append(" | heapUsed=${(rt.totalMemory() - rt.freeMemory()) / mb}MB")
            if (mem.totalMem > 0) append(" | ram=${mem.totalMem / mb}MB free=${mem.availMem / mb}MB")
            append(" | lowRam=${isLowRam}")
            append(" | app=${appVersion(context)}")
        }
    }

    /** 低配判定：堆上限小于 192MB 的基本是 1GB 内存的老盒子，节目单要减量。 */
    val isLowRam: Boolean
        get() = Runtime.getRuntime().maxMemory() < 192L * 1024 * 1024

    /** 最近一次崩溃的尾部内容；没有则返回 null。 */
    fun lastCrash(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching {
            val all = f.readText()
            // 只取最后一段，几千行堆栈没必要全展示
            val idx = all.lastIndexOf("===")
            val start = if (idx > 10) all.lastIndexOf("===", idx - 1) else -1
            (if (start >= 0) all.substring(start) else all).takeLast(4000)
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        // longVersionCode 是 API 28+，这里必须用 versionCode，否则 API 21 上直接 NoSuchFieldError
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName}(${info.versionCode})"
    }.getOrDefault("?")
}
