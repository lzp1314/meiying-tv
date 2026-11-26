package com.lik.iptvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 */
class VlcEngine(context: Context) {

    /** 起播失败/中途断开，把原因回传给界面（和设备无关的纯文本）。 */
    var onError: (String) -> Unit = { _ -> }

    /** 真正出画面了（首次 Vout），用来收掉起播超时看门狗。 */
    var onStarted: () -> Unit = { }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val libVlc = LibVLC(
        appContext,
        listOf(
            // 硬解优先，硬解不成就自动回落软解——老盒子软解 1080p 会掉帧，
            // 但只要硬解能走就不要走软解。
            "--avcodec-hw=any",
            // 直播缓冲 1.5 秒：太小抗不住抖动，太大换台迟钝。VLC 自己的默认偏保守。
            "--network-caching=1500",
            "--live-caching=1500",
            "--no-sub-autodetect-file",
            // 不设 --no-drop-late-frames：直播必须允许丢晚到的帧，
            // 否则解码跟不上时延迟会越积越大，越看越偏
        ),
    )

    val mediaPlayer = MediaPlayer(libVlc)

    /** 给 Compose 挂载的画面视图。整个生命周期只有一个实例，不换父容器。 */
    val view = VLCVideoLayout(appContext).apply {
        keepScreenOn = true
        isFocusable = false
    }

    private var startWatchdog: Runnable? = null

    /** 画面视图有没有成功挂上。挂不上就等于没有出画面的通道，必须明说而不是黑屏干等。 */
    private var viewAttached = false

    init {
        // 第三个参数是「显示字幕」，第四个是「用 TextureView」。
        // 电视端用 SurfaceView（false）：硬解直出、开销更低，老盒子更稳。
        viewAttached = runCatching { mediaPlayer.attachViews(view, null, false, false) }.isSuccess
        mediaPlayer.volume = 100
        mediaPlayer.setEventListener { event ->
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
    }

    /**
     * 起播。
     *
     * 每个频道都新建 Media：VLC 的 Media 是一次性的，复用会在换台后
     * 拿着上一条流的解码参数，表现为「换了台还是黑屏」。
     */
    fun play(channel: Channel) {
        if (!viewAttached) {
            // 视图没挂上时 play() 只会安静地什么都不显示，提前失败更好排查
            onError("VLC 画面组件挂载失败（attachViews），该机型无法使用 VLC 引擎")
            return
        }
        val media = Media(libVlc, Uri.parse(channel.url)).apply {
            // 第二参数 false = 不强制硬解，允许失败后回落软解
            setHWDecoderEnabled(true, false)
            // #EXTVLCOPT 里解析出来的 UA / Referer，很多源靠这个过防盗链
            if (channel.userAgent.isNotBlank()) addOption(":http-user-agent=${channel.userAgent}")
            if (channel.referrer.isNotBlank()) addOption(":http-referrer=${channel.referrer}")
            addOption(":network-caching=1500")
            addOption(":live-caching=1500")
        }
        runCatching {
            mediaPlayer.setMedia(media)
            media.release()
            mediaPlayer.play()
        }.onFailure { onError("VLC 起播异常：${it.message ?: it.javaClass.simpleName}") }

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
        runCatching { mediaPlayer.pause() }
    }

    fun resume() {
        runCatching { mediaPlayer.play() }
    }

    fun stop() {
        cancelWatchdog()
        runCatching { mediaPlayer.stop() }
    }

    /** 引擎不再需要时调用：detach 视图再释放原生资源，顺序反了会崩在 native 层。 */
    fun release() {
        cancelWatchdog()
        runCatching { mediaPlayer.setEventListener(null) }
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }
}
