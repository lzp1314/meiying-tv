package com.lik.iptvplayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Gitee 上 update.json 里描述的一个版本。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    /** APK 的 sha256。有它就能在「证书校验被降级」时依然保证包没被换过。 */
    val sha256: String = "",
    /** 备用下载地址（Gitee 抽风时依次尝试）。 */
    val apkFallbacks: List<String> = emptyList(),
)

/** 一次成功的检查：清单 + 是从哪个地址拿到的 + 证书是否真校验过。 */
data class UpdateCheck(
    val info: UpdateInfo,
    val source: String,
    val verified: Boolean,
    /** 前面失败过几个地址（0 = 首选地址一次命中）。 */
    val skipped: Int,
)

/**
 * 在线更新。
 *
 * 包发布在 Gitee 上，App 需要能直接读到 JSON 的地址。update.json：
 * ```
 * {
 *   "versionCode": 28,
 *   "versionName": "0.5.2",
 *   "apk": "https://.../iptv-player-0.5.2.apk",
 *   "apkFallback": ["https://github.com/.../iptv-player-0.5.2.apk"],
 *   "sha256": "……",
 *   "notes": "修复…"
 * }
 * ```
 * `apk` 写相对路径时按 update.json 所在目录拼；`sha256` 缺省时跳过哈希校验（兼容老清单）。
 *
 * 多地址 + 证书降级都在 [UpdateTransport] 里，这里只管「换着地址试、拿到能用的那份」。
 */
object UpdateChecker {
    /**
     * 首选地址：Gitee raw 直链，发布后立刻生效（没有 CDN 缓存）。
     * 缺点是这台老盒子上证书链验不过（见 UpdateTransport 注释），所以后面要跟镜像。
     */
    const val DEFAULT_URL: String = "https://gitee.com/lzp-zl/meiying-tv/raw/master/release/update.json"

    /**
     * 备用清单地址，按顺序试。
     *
     * - GitHub raw 紧跟 Gitee：两者都是「推完立刻生效」，不会读到上一版。国内常被墙，
     *   但失败得很快（DNS 污染/连接拒绝），不会像 CDN 那样让你误以为已是最新。
     * - jsDelivr 走 GlobalSign 链，Android 9 的老设备一定认，是绕开 Gitee 信任链问题的主力；
     *   代价是分支内容有小时级缓存，发版后短时间内可能读到上一版，所以排在 raw 之后，
     *   并且发版脚本会主动 purge + 复查（release/publish.sh）。
     * - cdn.jsdelivr.net 已移除：2026-11 实测直连与代理都 SSL 握手失败，留着只会白等超时。
     */
    val MIRRORS: List<String> = listOf(
        DEFAULT_URL,
        "https://raw.githubusercontent.com/lzp1314/meiying-tv/master/release/update.json",
        "https://fastly.jsdelivr.net/gh/lzp1314/meiying-tv@master/release/update.json",
    )

    /** 更新包上限 200MB，防呆。 */
    private const val MAX_APK = 200L * 1024 * 1024

    /**
     * 用户改过地址就只信他填的那个；没改过（或填的就是默认值）才走整条镜像链。
     * 这样自建源的人不会被我们塞的备用地址打扰。
     */
    fun candidates(url: String): List<String> {
        val me = url.trim()
        if (me.isEmpty()) return MIRRORS
        if (me == DEFAULT_URL) return MIRRORS
        return listOf(me)
    }

    suspend fun check(url: String): UpdateCheck = withContext(Dispatchers.IO) {
        val urls = candidates(url)
        val errors = mutableListOf<String>()
        for (u in urls) {
            val got = runCatching { UpdateTransport.text(u, 256 * 1024) }
            val fetched = got.getOrNull()
            if (fetched != null) {
                return@withContext UpdateCheck(parse(fetched.text, u), u, fetched.verified, errors.size)
            }
            errors += hostOf(u) + " → " + brief(got.exceptionOrNull())
        }
        throw IOException("所有更新地址都失败：" + errors.joinToString("；"))
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
        // 备用地址：数组或单个字符串都接受
        val fallbacks = mutableListOf<String>()
        when (val fb = o.opt("apkFallback") ?: o.opt("apkFallbacks")) {
            is JSONArray -> for (i in 0 until fb.length()) {
                fb.optString(i).trim().takeIf { it.startsWith("http") }?.let { fallbacks += it }
            }
            is String -> fb.trim().takeIf { it.startsWith("http") }?.let { fallbacks += it }
        }
        return UpdateInfo(
            versionCode = code,
            versionName = name,
            apkUrl = apk,
            notes = o.optString("notes"),
            sha256 = o.optString("sha256").trim(),
            apkFallbacks = fallbacks,
        )
    }

    /**
     * 下载 APK 到 dest：主地址失败自动换备用地址；带 sha256 时逐个字节校验，
     * 不符就把包删掉换下一个地址（全部不符则报错，绝不把可疑的包交给安装器）。
     */
    suspend fun download(
        apkUrl: String,
        dest: File,
        sha256: String = "",
        fallbacks: List<String> = emptyList(),
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        val urls = (listOf(apkUrl) + fallbacks)
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .distinct()
        val errors = mutableListOf<String>()
        for (u in urls) {
            tmp.delete()
            val got = runCatching { UpdateTransport.copyTo(u, tmp, MAX_APK, onProgress) }
            if (got.isFailure) {
                errors += hostOf(u) + " → " + brief(got.exceptionOrNull())
                continue
            }
            if (sha256.isNotBlank()) {
                val actual = sha256Of(tmp)
                if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                    tmp.delete()
                    errors += hostOf(u) + " → 校验不符（应为 ${sha256.take(10)}…，实为 ${actual.take(10)}…）"
                    continue
                }
            }
            // 先写 .part 再改名：中途断网不会留下一个能被安装器打开的坏包
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("保存安装包失败")
            return@withContext dest
        }
        throw IOException("下载失败：" + errors.joinToString("；"))
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: url

    /** 把异常翻成人能看懂的一行，太长就截断——屏幕上一行放不下。 */
    private fun brief(t: Throwable?): String {
        if (t == null) return "未知错误"
        if (UpdateTransport.isSslFailure(t)) return "证书校验失败"
        val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return msg.take(60)
    }
}
