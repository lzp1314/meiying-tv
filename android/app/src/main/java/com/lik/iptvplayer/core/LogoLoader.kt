package com.lik.iptvplayer.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 频道台标加载。
 *
 * 没有引入图片加载库：只为了列表和换台提示里的几十像素小图，
 * 拉一个 Coil/Glide 进来会让包体和内存都变大，老盒子上不划算。
 *
 * 0.5.5 起加两道闸门，专治「首次进列表很卡」：
 * 1. 最多 3 个并发请求。原来列表滚过一行就发一个，几十个请求会和播放器
 *    抢 OkHttp 的连接池和带宽，播放线程被拖慢。
 * 2. 失败记录 5 分钟。滚回来又发一遍同样的失败请求是纯浪费；
 *    成功一次就清掉记录，源修好后不用等 5 分钟。
 */
object LogoLoader {
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_PARALLEL = 3
    private const val FAIL_TTL_MS = 5 * 60 * 1000L

    /** 按条目数缓存，几十张台标足够覆盖常用频道。 */
    private val cache = LruCache<String, Bitmap>(48)
    private val permits = Semaphore(MAX_PARALLEL)
    private val failures = ConcurrentHashMap<String, Long>()

    suspend fun load(url: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = url.trim()
        if (key.isEmpty()) return@withContext null
        cache.get(key)?.let { return@withContext it }

        val now = System.currentTimeMillis()
        failures[key]?.let { failedAt ->
            if (now - failedAt < FAIL_TTL_MS) return@withContext null
        }
        // 失败表也要有上限：源地址变了会不断留新 key，不能让它无限涨
        if (failures.size > 200) failures.clear()

        val bmp = permits.withPermit {
            runCatching {
                val request = Request.Builder()
                    .url(key)
                    .header("User-Agent", "IPTVPlayer/1.0 (Android)")
                    .build()
                Network.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    val declared = body.contentLength()
                    if (declared > MAX_BYTES) return@use null
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@use null

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0) return@use null

                    var sample = 1
                    while (bounds.outWidth / sample > maxPx * 2 && sample < 8) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }.getOrNull()
        }

        if (bmp != null) {
            cache.put(key, bmp)
            failures.remove(key)
        } else {
            failures[key] = now
        }
        bmp
    }
}
