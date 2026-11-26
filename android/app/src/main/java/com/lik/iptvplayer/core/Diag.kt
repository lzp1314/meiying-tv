package com.lik.iptvplayer.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
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

    /**
     * 冷启动耗时打点。用户说「首次打开很慢、很卡」，光看界面分不清是
     * 读快照慢、拉 M3U/EPG 慢、还是起播慢。四个毫秒数放在设置→诊断里，
     * 截一张图就能定位该优化哪一段。
     *
     * 用 elapsedRealtime 而不是 currentTimeMillis：后者会被系统校时改掉，
     * 算出来的耗时的可能是负数。
     */
    object BootTiming {
        @Volatile private var startMs = 0L
        @Volatile private var cacheMs = -1L
        @Volatile private var refreshMs = -1L
        @Volatile private var firstFrameMs = -1L
        @Volatile private var firstFrameEngine = ""

        fun markStart() {
            if (startMs == 0L) startMs = SystemClock.elapsedRealtime()
        }

        // 这三项只记冷启动那一次：打点是为了回答「首次打开为什么慢」，
        // 后续换源/手动刷新的耗时是另一回事，别把首启数据冲掉。
        fun markCache(ms: Long) { if (cacheMs < 0L) cacheMs = ms }

        fun markRefresh(ms: Long) { if (refreshMs < 0L) refreshMs = ms }

        /** 只记第一次出画面——换台耗时和冷启动是两回事，别混在一起。 */
        fun markFirstFrame(engine: String) {
            if (firstFrameMs >= 0L || startMs == 0L) return
            firstFrameMs = SystemClock.elapsedRealtime() - startMs
            firstFrameEngine = engine
        }

        fun summary(): String {
            val total = if (firstFrameMs >= 0L) firstFrameMs else
                if (startMs > 0L) SystemClock.elapsedRealtime() - startMs else -1L
            return buildString {
                append("启动→首帧 ${fmt(total)}")
                append(" · 读缓存 ${fmt(cacheMs)}")
                append(" · 拉源 ${fmt(refreshMs)}")
                if (firstFrameEngine.isNotBlank()) append(" · 引擎 $firstFrameEngine")
            }
        }

        private fun fmt(ms: Long): String = if (ms < 0L) "—" else "${ms}ms"
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        // longVersionCode 是 API 28+，这里必须用 versionCode，否则 API 21 上直接 NoSuchFieldError
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName}(${info.versionCode})"
    }.getOrDefault("?")
}
