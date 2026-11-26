package com.lik.iptvplayer.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 订阅与节目单共用一个 OkHttp 客户端；播放侧复用同一个实例以共享连接池。 */
object Network {
    private const val MAX_BYTES = 6L * 1024 * 1024

    /**
     * Application Context，仅用来加载内置 CA。
     * 必须在 [init] 里注入；没注入时客户端退回纯系统证书校验，不会崩。
     */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 节目单上限。
     * 原来是 3MB（低内存 1.5MB），结果国内全量 XMLTV 普遍十几 MB，
     * 直接被砍成「没有节目单」——电视端看不到节目单根因就在这里。
     * 现在改流式解析，正文不再驻留内存，上限可以放宽。
     */
    const val EPG_MAX_BYTES = 32L * 1024 * 1024
    const val EPG_MAX_BYTES_LOW_RAM = 20L * 1024 * 1024

    /**
     * 惰性构建：把 [init] 注入的 Context 用上，同时保证任何调用顺序都不会抛异常。
     * SSL 方面挂「系统信任库 + 内置 TrustAsia CA」，专治老 Android 盒子的证书链缺失。
     */
    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        appContext?.let { ctx ->
            runCatching { Trust.sslSocketFactory(ctx) }.getOrNull()?.let { (sf, tm) ->
                builder.sslSocketFactory(sf, tm)
            }
        }
        builder.build()
    }

    /**
     * 节目单专用客户端：内置候选可能连着试几个，单个必须尽快失败，
     * 否则四个候选串起来能让首次加载卡一分多钟。
     * 继承 [client] 的 SSL 配置，所以老设备的证书兜底同样生效。
     */
    private val epgClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun normalize(url: String): String =
        M3uParser.normalizeSourceUrl(url)
            ?: throw IllegalArgumentException("仅支持 http / https 订阅地址")

    fun text(url: String, maxBytes: Long = MAX_BYTES): String {
        val request = Request.Builder()
            .url(normalize(url))
            .header("User-Agent", "IPTVPlayer/0.1 (Android)")
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("空响应")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            body.byteStream().buffered().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        throw IllegalStateException("响应超过 ${maxBytes / 1024 / 1024} MB")
                    }
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        }
    }

    /**
     * 边下边解析：把响应流交给调用方，全程不把整个文件读成字符串。
     * 大节目单的内存占用从「文件大小 × 数倍」降到「解析结果本身」。
     */
    fun <T> stream(url: String, maxBytes: Long, block: (InputStream) -> T): T {
        val request = Request.Builder()
            .url(normalize(url))
            .header("User-Agent", "IPTVPlayer/0.1 (Android)")
            .header("Accept", "*/*")
            .build()
        epgClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("空响应")
            val declared = body.contentLength()
            if (declared > maxBytes) {
                throw IllegalStateException("节目单 ${declared / 1024 / 1024} MB，超过 ${maxBytes / 1024 / 1024} MB 上限")
            }
            return body.byteStream().use { raw ->
                block(LimitStream(raw, maxBytes))
            }
        }
    }

    /** 读取超过上限时抛错，防止边下边解析被超大文件拖死。 */
    private class LimitStream(
        private val inner: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) {
                total++
                check()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                total += n
                check()
            }
            return n
        }

        private fun check() {
            if (total > maxBytes) {
                throw IOException("响应超过 ${maxBytes / 1024 / 1024} MB")
            }
        }

        override fun close() = inner.close()
    }
}
