package com.lik.iptvplayer.player

import android.os.Handler
import android.os.Looper
import com.lik.iptvplayer.core.Channel
import com.lik.iptvplayer.core.Network
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 换台预热：在当前台停留 3 秒后，让服务端先把「下一个台」的票据和清单取好，
 * 下次换过去时第一份清单直接命中缓存，省掉两次串行往返（约 0.3~0.6 秒）。
 *
 * 只做一件事，失败静默：不弹提示、不重试、不影响播放状态。
 * 「预热哪个台」由调用方决定（PlayerScreen 会只选同一分组内的下一个），
 * 这里只负责去抖和发请求。
 */
object Warmer {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /**
     * 每次换台都调一次；3 秒内又换台会自动取消上一次（连点换台不去浪费出口配额）。
     * 传 null 表示清除待执行的预热：切源、进设置、退后台、销毁时都要调。
     */
    fun schedule(channel: Channel?) {
        pending?.let(handler::removeCallbacks)
        pending = null
        if (channel == null) return
        val task = Runnable { fire(channel) }
        pending = task
        handler.postDelayed(task, 3_000L)
    }

    private fun fire(channel: Channel) {
        val url = warmUrl(channel.url) ?: return
        // 这里跑在主线程 Handler 回调里：slug 若含非法字符，Request.Builder().url()
        // 会抛 IllegalArgumentException。必须整个兜住，否则预热失败反而崩了 App。
        runCatching {
            val request = Request.Builder().url(url).get().build()
            // 异步发出，不阻塞主线程；202 空 body 读完就关，不判断状态码
            Network.client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) {
                    runCatching { response.close() }
                }
            })
        }
    }

}

/**
 * /live/xxx.m3u8 或 /ysp/xxx.m3u8 -> /warm/xxx；别的地址返回 null（不预热）。
 * 密码段之类的路径前缀原样保留，只替换最后一段目录名。
 * query 丢掉——服务端只认路径里的 slug。
 *
 * 故意放在顶层且不碰任何 Android API：Warmer 是个带 Handler 的 object，
 *  JVM 单测直接加载它会踩 android.jar 的 Stub 异常，这里拆开就能纯函数验证。
 */
internal fun warmUrl(url: String): String? {
    val clean = url.substringBefore('?')
    for (marker in listOf("/live/", "/ysp/")) {
        val at = clean.lastIndexOf(marker)
        if (at < 0) continue
        val slug = clean.substring(at + marker.length).removeSuffix(".m3u8")
        if (slug.isBlank()) return null
        return clean.substring(0, at) + "/warm/" + slug
    }
    return null
}
