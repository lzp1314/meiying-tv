package com.lik.iptvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lik.iptvplayer.core.Channel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC 播放引擎，专门用来吃 ExoPlayer 吃不下的台。
 *
 * 为什么需要第二个引擎：Media3 没有内置软件解码器，遇到国内 IPTV 常见的
 * MPEG-2 视频、AC3/EAC3 音频、以及 PMT 与 ES 不一致的非标准 TS，只能报到
 * ERROR_CODE_DECODER_INIT_FAILED 然后黑屏/报错。VLC 自带全套软解与 TS 容错，
 * 这些台在 VLC 上能直接出画面。
 *
 * 内存代价：libVLC 常驻约 30-60MB，所以在 [com.lik.iptvplayer.core.PlayEngine.AUTO]
 * 模式下**按需创建**——只有真遇到播不了的台才初始化，正常看台不占这份内存。
 *
 * ⚠️ 顺序问题（0.5.0 的坑，0.5.1 修）：
 * `MediaPlayer.attachViews()` 要求 [VLCVideoLayout] **已经挂在窗口上**，
 * 也就是它得先被加进界面、拿到有效的 SurfaceHolder。0.5.0 在构造函数里就
 * attach，此时视图还在 Composable 外面，attach 直接抛异常，被上层
 * `runCatching` 吞掉，表现就是「VLC 引擎初始化失败，仍用 ExoPlayer」——
 * 于是 VLC 这条兜底从头到尾没生效过。
 * 现在改成：构造函数只建引擎，attach 由视图真正进窗口时触发。
 */
class VlcEngine(context: Context) {

    /** 起播失败/中途断开，把原因回传给界面（和设备无关的纯文本）。 */
    var onError: (String) -> Unit = { _ -> }

    /** 真正出画面了（首次 Vout），用来收掉起播超时看门狗。 */
    var onStarted: () -> Unit = { }

    /**
     * 初始化失败的原因（空串=成功）。
     * 0.5.0 把异常吞成一句「初始化失败」，线上完全没法定位；
     * 这里保留「异常类名 + message + 底层 cause」，并显示到设置页诊断行。
     */
    var initError: String = ""
        private set

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    /** 给 Compose 挂载的画面视图。整个生命周期只有一个实例，不换父容器。 */
    val view = VLCVideoLayout(appContext).apply {
        keepScreenOn = true
        isFocusable = false
    }

    private var viewsAttached = false
    private var startWatchdog: Runnable? = null

    /** 视图还没挂上时先存着，等 attach 完成后自动起播，不让用户再按一次。 */
    private var pending: Channel? = null

    init {
        buildEngine()
        // 视图进窗口的那一刻才 attach——这才是 VLC 要求的时机
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (attachViewsIfPossible()) {
                    pending?.let { ch ->
                        pending = null
                        playNow(ch)
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /**
     * 建引擎。两套参数依次尝试：
     * 1. 完整参数（硬解优先 + 1.5 秒缓冲）；
     * 2. 空参数——个别老固件上某个 option 会让 VLC 直接拒绝启动，
     *    这时用出厂默认值反而能起来。
     */
    private fun buildEngine() {
        // 先单独探一次原生库：失败就是 ABI/安装包问题，跟参数无关，要分开报
        val soError = runCatching { System.loadLibrary("vlcjni") }.exceptionOrNull()

        val attempts = listOf(
            listOf(
                "--avcodec-hw=any",
                "--network-caching=1500",
                "--live-caching=1500",
                "--no-sub-autodetect-file",
            ),
            emptyList(),
        )
        var last: Throwable? = null
        for (opts in attempts) {
            try {
                val vlc = LibVLC(appContext, opts)
                val mp = MediaPlayer(vlc)
                mp.volume = 100
                mp.setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> cancelWatchdog()
                        MediaPlayer.Event.Vout -> {
                            cancelWatchdog()
                            onStarted()
                        }
                        MediaPlayer.Event.EncounteredError ->
                            onError("VLC 无法解码该码流（编码或地址不受支持）")
                    }
                }
                libVlc = vlc
                mediaPlayer = mp
                initError = ""
                return
            } catch (t: Throwable) {
                last = t
            }
        }
        initError = buildString {
            append(describe(last))
            if (soError != null) {
                append(" · 原生库加载失败：")
                append(describe(soError))
                append("（当前 ABI：")
                append(android.os.Build.SUPPORTED_ABIS.joinToString(","))
                append("）")
            }
        }
    }

    /** 把异常翻成「类名: message ← cause」，一路挖到底，别只留第一层。 */
    private fun describe(t: Throwable?): String {
        if (t == null) return "未知错误"
        val sb = StringBuilder()
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard++ < 4) {
            if (guard > 1) sb.append(" ← ")
            sb.append(cur.javaClass.simpleName)
            cur.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it.take(160)) }
            cur = cur.cause
        }
        return sb.toString()
    }

    /**
     * 视图在窗口上就 attach。返回是否已 attach。
     * 老固件上 SurfaceHolder 偶尔要等一帧才就绪，所以失败时允许调用方稍后重试。
     */
    private fun attachViewsIfPossible(): Boolean {
        val mp = mediaPlayer ?: return false
        if (viewsAttached) return true
        if (!view.isAttachedToWindow) return false
        return runCatching {
            // 第三个参数「显示字幕」= false，第四个「用 TextureView」= false：
            // 电视端用 SurfaceView 硬解直出，开销更低，老盒子更稳
            mp.attachViews(view, null, false, false)
            viewsAttached = true
            true
        }.getOrElse { t ->
            initError = "画面组件挂载失败：" + describe(t)
            false
        }
    }

    /**
     * 起播。
     *
     * 每个频道都新建 Media：VLC 的 Media 是一次性的，复用会在换台后
     * 拿着上一条流的解码参数，表现为「换了台还是黑屏」。
     */
    fun play(channel: Channel) {
        if (mediaPlayer == null) {
            onError("VLC 引擎未就绪：" + initError.ifBlank { "初始化失败" })
            return
        }
        if (!attachViewsIfPossible()) {
            // 视图可能还没进窗口（Compose 首帧），先记下来，attach 后自动续播；
            // 同时给一个短看门狗，真挂不上要明说，不能黑屏干等
            pending = channel
            awaitAttach(retries = 12)
            return
        }
        playNow(channel)
    }

    /** 视图进窗口通常在一两帧内，最多等约 1.2 秒；等不到就报错。 */
    private fun awaitAttach(retries: Int) {
        if (attachViewsIfPossible()) {
            pending?.let { ch ->
                pending = null
                playNow(ch)
            }
            return
        }
        if (retries <= 0) {
            pending = null
            onError("VLC 画面组件挂载失败：" + initError.ifBlank { "视图未进入窗口" })
            return
        }
        mainHandler.postDelayed({ awaitAttach(retries - 1) }, 100)
    }

    private fun playNow(channel: Channel) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        val media = Media(vlc, Uri.parse(channel.url)).apply {
            // 第二参数 false = 不强制硬解，允许失败后回落软解
            setHWDecoderEnabled(true, false)
            // #EXTVLCOPT 里解析出来的 UA / Referer，很多源靠这个过防盗链
            if (channel.userAgent.isNotBlank()) addOption(":http-user-agent=${channel.userAgent}")
            if (channel.referrer.isNotBlank()) addOption(":http-referrer=${channel.referrer}")
            addOption(":network-caching=1500")
            addOption(":live-caching=1500")
        }
        runCatching {
            mp.setMedia(media)
            media.release()
            mp.play()
        }.onFailure { onError("VLC 起播异常：" + describe(it)) }

        armWatchdog()
    }

    /**
     * 起播看门狗：20 秒还没出画面就判定失败。
     *
     * VLC 对「服务器连上了但一直不下数据」不会主动报错，表现为无限缓冲——
     * 正是用户说的「卡住不动」。这里替它兜住，好让 AUTO 模式切回 ExoPlayer 或提示换源。
     */
    private fun armWatchdog() {
        cancelWatchdog()
        val r = Runnable { onError("VLC 起播超时：20 秒没有画面，多为源地址不可用或码流异常") }
        startWatchdog = r
        mainHandler.postDelayed(r, 20_000)
    }

    private fun cancelWatchdog() {
        startWatchdog?.let { mainHandler.removeCallbacks(it) }
        startWatchdog = null
    }

    fun pause() {
        cancelWatchdog()
        runCatching { mediaPlayer?.pause() }
    }

    fun resume() {
        runCatching { mediaPlayer?.play() }
    }

    fun stop() {
        cancelWatchdog()
        pending = null
        runCatching { mediaPlayer?.stop() }
    }

    /** 引擎不再需要时调用：detach 视图再释放原生资源，顺序反了会崩在 native 层。 */
    fun release() {
        cancelWatchdog()
        pending = null
        runCatching { mediaPlayer?.setEventListener(null) }
        runCatching { mediaPlayer?.stop() }
        if (viewsAttached) runCatching { mediaPlayer?.detachViews() }
        viewsAttached = false
        runCatching { mediaPlayer?.release() }
        runCatching { libVlc?.release() }
        mediaPlayer = null
        libVlc = null
    }
}
