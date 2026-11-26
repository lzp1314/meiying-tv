package com.lik.iptvplayer.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 在线更新专用的传输层：**先严格校验，失败才降级**。
 *
 * 为什么不能只靠 Trust.kt 的内置 CA：
 * HG680-GC（Android 9 定制固件）上，即便把 Certum 自签名根和 TrustAsia 交叉根都塞进信任库，
 * 系统仍然报 `Unacceptable certificate: CN=TrustAsia TLS RSA Root CA`——
 * 这台机器的证书路径构建有自己的限制，继续往信任库里加证书是打不完的地鼠。
 *
 * 所以分层：
 *  1. 先用【严格客户端】（系统 CA + 内置 CA）请求，正常路径下这才是真实校验；
 *  2. 只有遇到 SSL/证书类异常，才用【宽松客户端】重试同一个地址；
 *  3. 宽松模式只对更新通道生效，绝不用于播放源。
 *
 * 宽松之后靠什么保证安全：
 *  - `update.json` 里带 `sha256`，下载完逐个字节比对，改一个字节都装不上；
 *  - 安装前再核一次 APK 签名与已装版本一致（见 [ApkInstaller.signatureMatches]）。
 *  - 只要清单是从严格通道拿到的，哈希本身就是可信的，APK 走哪条路都不影响安全性。
 */
object UpdateTransport {

    private const val UA = "IPTVPlayer/update (Android)"

    /** 宽松客户端：接受任意证书，但保留主机名校验（OkHttp 默认行为）。 */
    private val relaxed: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .connectTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 一次成功请求的结果：正文 + 证书是否**真校验过**（false = 走了降级）。 */
    class Fetched(val text: String, val verified: Boolean)

    /** 文本请求（update.json 这类小文件）。 */
    fun text(url: String, maxBytes: Long): Fetched {
        val strict = runCatching { readStrict(url, maxBytes) }
        strict.getOrNull()?.let { return Fetched(it, true) }
        val err = strict.exceptionOrNull() ?: throw IOException("未知错误")
        // 只有证书类错误才降级：DNS 不通、404 这种降级也没用，反而掩盖真实原因
        if (!isSslFailure(err)) throw err
        return Fetched(readRelaxed(url, maxBytes), false)
    }

    /**
     * 下载到文件。返回证书是否真校验过。
     * [onProgress] 传 -1 表示总长未知，0..1 表示进度。
     */
    fun copyTo(
        url: String,
        dest: File,
        maxBytes: Long,
        onProgress: (Float) -> Unit,
    ): Boolean {
        dest.parentFile?.mkdirs()
        val strict = runCatching { writeStrict(url, dest, maxBytes, onProgress) }
        if (strict.isSuccess) return true
        val err = strict.exceptionOrNull() ?: throw IOException("未知错误")
        if (!isSslFailure(err)) throw err
        // 降级前把半截文件清掉，否则追加写会得到一个坏包
        dest.delete()
        writeRelaxed(url, dest, maxBytes, onProgress)
        return false
    }

    // ── 内部实现 ────────────────────────────────────────────────────

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept", "*/*")
        .header("Cache-Control", "no-cache")
        .build()

    private fun readStrict(url: String, max: Long): String =
        Network.client.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
            .newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("空响应")
                body.byteStream().use { readLimited(it, max) }
            }

    private fun readRelaxed(url: String, max: Long): String =
        relaxed.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
            .newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("空响应")
                body.byteStream().use { readLimited(it, max) }
            }

    private fun readLimited(input: InputStream, max: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("响应超过 ${max / 1024} KB")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun writeStrict(url: String, dest: File, max: Long, onProgress: (Float) -> Unit): Long =
        Network.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // 50MB 的包在盒子上要下很久，给足 25 分钟；断流由 readTimeout 管
            .callTimeout(25, TimeUnit.MINUTES)
            .build()
            .newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                writeBody(r.body ?: throw IOException("空响应"), r.body!!.contentLength(), dest, max, onProgress)
            }

    private fun writeRelaxed(url: String, dest: File, max: Long, onProgress: (Float) -> Unit): Long =
        relaxed.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.MINUTES)
            .build()
            .newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                writeBody(r.body ?: throw IOException("空响应"), r.body!!.contentLength(), dest, max, onProgress)
            }

    private fun writeBody(
        body: okhttp3.ResponseBody,
        total: Long,
        dest: File,
        max: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        var done = 0L
        var lastPct = -2
        body.byteStream().use { input ->
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done > max) throw IOException("更新包超过 ${max / 1024 / 1024} MB，已中止")
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(if (pct >= 0) pct / 100f else -1f)
                    }
                }
            }
        }
        if (done == 0L) throw IOException("下载到 0 字节")
        return done
    }

    /** 顺着 cause 链找 SSL / 证书异常——OkHttp 会把它们包成 IOException。 */
    fun isSslFailure(t: Throwable?): Boolean {
        var cur = t
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur is SSLException || cur is CertificateException) return true
            cur = cur.cause
        }
        return false
    }
}
