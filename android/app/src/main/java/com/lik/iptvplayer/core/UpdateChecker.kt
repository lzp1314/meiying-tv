package com.lik.iptvplayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gitee 上 update.json 里描述的一个版本。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

/**
 * 在线更新。
 *
 * 包发布在 Gitee 上，App 只需要一个能直接读到 JSON 的地址（raw 直链或任意静态地址）。
 * update.json 长这样：
 * ```
 * {
 *   "versionCode": 12,
 *   "versionName": "0.3.8",
 *   "apk": "iptv-player-0.3.8.apk",     // 也接受完整 http(s) 地址
 *   "notes": "修复换台卡顿"
 * }
 * ```
 * `apk` 写相对路径时按 update.json 所在目录拼，方便整个发布目录一起上传。
 */
object UpdateChecker {
    /**
     * 内置的更新地址：Gitee raw 直链，手机与电视端开箱即能自动检查更新。
     * 用户在设置里改过地址则优先用改过的（[PlaybackPrefs.updateUrl]）。
     * 换仓库或改分支时改这里重编一版即可。
     */
    const val DEFAULT_URL: String = "https://gitee.com/lzp-zl/meiying-tv/raw/master/release/update.json"

    /** 更新包最大 200MB，防呆。 */
    private const val MAX_APK = 200L * 1024 * 1024

    /** 下载单独给长超时：盒子上网慢，APK 十几 MB 要一会儿。 */
    private val downloadClient = Network.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun check(url: String): UpdateInfo = withContext(Dispatchers.IO) {
        val target = url.trim()
        require(target.isNotEmpty()) { "未配置更新地址" }
        val raw = Network.text(target, 256 * 1024)
        parse(raw, target)
    }

    /** 单独拆出来，方便在 JVM 侧用假数据验证字段兼容性。 */
    fun parse(raw: String, baseUrl: String): UpdateInfo {
        val text = raw.trim().removePrefix("\uFEFF")
        val o = JSONObject(text)
        val code = o.opt("versionCode")?.toString()?.trim()?.toIntOrNull() ?: 0
        val name = o.optString("versionName").ifBlank { "未知版本" }
        var apk = o.optString("apk").ifBlank { o.optString("url") }.trim()
        if (apk.startsWith("./")) apk = apk.removePrefix("./")
        // 相对路径：挂到 update.json 同目录下
        if (apk.isNotEmpty() && !apk.startsWith("http://") && !apk.startsWith("https://")) {
            apk = baseUrl.substringBeforeLast('/') + "/" + apk
        }
        if (code <= 0 || apk.isBlank()) {
            throw IllegalStateException("update.json 缺少 versionCode 或 apk 字段")
        }
        return UpdateInfo(code, name, apk, o.optString("notes"))
    }

    /**
     * 下载 APK 到 dest。进度回调按 1% 粒度触发，避免每 64KB 都过一遍 UI 线程。
     * 回调发生在 IO 线程，调用方自己切主线程。
     */
    suspend fun download(
        apkUrl: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "IPTVPlayer/update (Android)")
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw IOException("更新包超过 200MB，已中止")
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(if (pct >= 0) pct / 100f else -1f)
                        }
                    }
                }
            }
        }
        // 先写 .part 再改名：中途断网不会留下一个能被安装器打开的坏包
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("保存安装包失败")
        dest
    }
}
