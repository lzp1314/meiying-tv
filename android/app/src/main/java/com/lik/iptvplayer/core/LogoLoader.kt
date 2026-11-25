package com.lik.iptvplayer.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 频道台标加载。
 *
 * 没有引入图片加载库：只为了列表和换台提示里的几十像素小图，
 * 拉一个 Coil/Glide 进来会让包体和内存都变大，老盒子上不划算。
 * 这里做三件事：内存 LRU、按目标尺寸采样解码、单图 512KB 上限。
 */
object LogoLoader {
    private const val MAX_BYTES = 512 * 1024

    /** 按条目数缓存，几十张台标足够覆盖常用频道。 */
    private val cache = LruCache<String, Bitmap>(24)

    suspend fun load(url: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = url.trim()
        if (key.isEmpty()) return@withContext null
        cache.get(key)?.let { return@withContext it }

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
        }.getOrNull()?.also { cache.put(key, it) }
    }
}
