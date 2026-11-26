package com.lik.iptvplayer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.lik.iptvplayer.core.Channel
import com.lik.iptvplayer.core.DecodeMode
import com.lik.iptvplayer.core.Network

/**
 * 一次播放异常/卡顿恢复的完整信息，回传给 UI 显示。
 *
 * 用户排查问题的关键是把 errorCodeName 摆到屏幕上：
 * ERROR_CODE_IO_* 是网络/源地址问题，带 DECODER / ERROR_CODE_DECODER_* 是解码器问题，
 * 两者修法完全不同，不能只给一句「播放失败」。
 */
data class PlaybackIssue(
    val code: String,
    val message: String,
    val stage: Stage,
    val attempt: Int, // 第几次重试（1..3）；卡顿恢复为 0
    /** 给用户的处置建议。空字符串表示用默认文案（沿用旧行为）。 */
    val hint: String = "",
    /** HTTP 状态码（能挖到时带上）。403 和 404 的修法完全不同，必须让用户看见。 */
    val http: Int? = null,
) {
    enum class Stage { RETRYING, GAVE_UP, STALL_RECOVER }

    /** 上屏用的错误码：`ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 403)` */
    val codeText: String get() = if (http != null) "$code (HTTP $http)" else code

    val kind: String = when {
        code.contains("DECODER", ignoreCase = true) -> "解码器"
        code.contains("IO", ignoreCase = true) || code.contains("NETWORK", ignoreCase = true) -> "网络/源"
        stage == Stage.STALL_RECOVER -> "卡顿"
        else -> "播放"
    }

    /**
     * 是不是「换个引擎就能好」的失败。
     *
     * IO_* 是地址/网络层面打不开，VLC 也一样打不开，换引擎没意义；
     * 其余（解码器缺失、码流不合法、无限卡顿）恰恰是 ExoPlayer 的能力边界，
     * 交给 VLC 往往能播——AUTO 模式据此决定要不要自动换引擎。
     */
    val switchEngineMayHelp: Boolean =
        stage == Stage.GAVE_UP && !code.contains("IO_", ignoreCase = true)
}

/**
 * Media3 播放控制。按官方 HLS 文档：
 * - 用 DefaultMediaSourceFactory 自动区分 m3u8 / ts，不手写 HLS 解析；
 * - 直播流不硬设 LiveConfiguration；
 * - 每个频道按 #EXTVLCOPT 解析出的 UA / Referer 构建数据源；
 * - Activity 销毁时必须调用 release()。
 *
 * 针对老年份运营商盒子（1GB 内存、Android 9 固件）做的调整：
 * 1. 解码器可选（自动 / 硬解 / 软解），默认硬解优先；
 * 2. 打开解码器回退（硬解初始化失败时换一个，而不是死磕同一个）；
 * 3. 缓冲从默认的 50 秒降到 15/30 秒（1GB 内存扛不住 50 秒缓冲的反复回收）。
 * 4. 出错自动重开（1/2/4 秒退避，最多 3 次），卡死看门狗（15 秒不动就重开）。
 */
@UnstableApi
class PlayerController(
    context: Context,
    initialMode: DecodeMode = DecodeMode.AUTO,
) {
    private val defaultAgent = "IPTVPlayer/0.1 (Android)"
    var onError: (String) -> Unit = { _ -> }
    /** 结构化回调：重试中 / 放弃 / 卡顿重连，UI 据此显示提示与错误码。 */
    var onIssue: (PlaybackIssue) -> Unit = { _ -> }

    /** 最近一次错误码（errorCodeName），设置面板常驻显示，下次卡住能直接看到。 */
    @Volatile
    var lastErrorCode: String = ""

    /** 实际启用的解码器名，用于验证走的是硬解还是软解（软解名通常含 c2.android / OMX.google）。 */
    var onDecoder: (String) -> Unit = { _ -> }

    /**
     * 当前流的编码摘要，例如 `H.264 1280×720 · AC3`。
     *
     * 用户报「黑屏 / 卡住 / 报错」时，光有错误码没法判断是哪一类——
     * 把视频编码、分辨率、音频编码摆到设置页，一张截图就能定位到底是
     * MPEG-2 没解码器、还是 AC3 没解码器、还是码流本身坏了。
     */
    var onTracks: (String) -> Unit = { _ -> }

    /** 最近一条流的视频/音频 MIME，供解码失败时判断「是不是本机根本没这个解码器」。 */
    @Volatile
    var videoMime: String = ""
        private set

    @Volatile
    var audioMime: String = ""
        private set

    /** 已经因为音频解码不了而关掉音频轨（每条流只尝试一次，避免死循环）。 */
    @Volatile
    private var audioBypassed = false

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 请求头兜底档位（防盗链专用）。
     *
     * 国内源 403 的绝大多数原因是 UA / Referer 校验：
     * 频道自带 #EXTVLCOPT 就先用它，被拒了再依次换「播放器 UA」「浏览器 UA + 站内 Referer」。
     * 这是 TiviMate / Perfect Player 这类成熟播放器的常规做法——
     * 对 403 原地重试同一个地址毫无意义，换请求头才有用。
     */
    private var profileIndex = 0
    private var profiles: List<NetProfile> = emptyList()

    private data class NetProfile(val ua: String?, val referer: String?)

    /** 起播时刻与首帧时刻：用来识别「有声音没画面 / 纯黑屏」这一类 VLC 才救得回的失败。 */
    private var preparedAt = 0L
    private var firstFrameAt = 0L

    /**
     * 当前解码方式。**可以随时改**：选择器每次起播都会重读这个值，
     * 所以改完调用 [replay] 就生效，不需要重建 player、不需要重启应用。
     */
    @Volatile
    var decodeMode: DecodeMode = initialMode

    /**
     * 解码器选择：
     * - 自动：硬解排前面，软解留作兜底
     * - 硬解：只留硬解。没有可用的硬解器时退回全部（宁可软解出画面，也不黑屏），
     *   设置面板会把实际使用的解码器名标出来，用户能立刻看到没生效
     * - 软解：软解排前面，用于绕开个别盒子上硬解的 bug
     */
    private val codecSelector = object : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (all.size < 2) return all
            return when (decodeMode) {
                DecodeMode.HARDWARE -> all.filterNot { it.softwareOnly }.ifEmpty { all }
                DecodeMode.SOFTWARE -> all.sortedBy { if (it.softwareOnly) 0 else 1 }
                DecodeMode.AUTO -> all.sortedBy { if (it.softwareOnly) 1 else 0 }
            }
        }
    }

    /**
     * 默认的 LoadControl 是 min=max=50 秒，直播场景下这套值有两个害处：
     * 延迟大，且在 1GB 内存的盒子上会不断回收旧分片、把 CPU 拖进 GC。
     * 直播真正需要的是「够抗抖动但不囤积」，15/30 秒是这类盒子的经验平衡点。
     */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            15_000, // 最小缓冲：低于这个就补
            30_000, // 最大缓冲：到这里停止下载，防止无限囤积
            1_500,  // 起播门槛：够一两个分片就出画面，不等 2.5 秒
            3_000,  // 卡顿后恢复门槛
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(0, false) // 直播不回看，不留后退缓冲
        .build()

    // LoadErrorHandlingPolicy 属于 DefaultMediaSourceFactory，ExoPlayer.Builder 没有这个 API
    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(
            DefaultRenderersFactory(appContext)
                // 硬解初始化失败时允许换下一个候选解码器，而不是直接抛错卡住
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector),
        )
        .setLoadControl(loadControl)
        .setTrackSelector(DefaultTrackSelector(appContext))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(Network.client))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5)),
        )
        .build()
        .also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onPlayerErrorInternal(error)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    reportTracks(tracks)
                }
            })
            exo.addAnalyticsListener(object : AnalyticsListener {
                // Media3 的这段回调按轨道拆成了 onAudio/onVideo，视频才是我们要看的
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializationDurationMs: Long,
                ) {
                    onDecoder(decoderName)
                }

                /**
                 * 首帧真正上屏。这是「到底有没有画面」的唯一可信信号：
                 * 有声音没画面、TS 解出音频却解不出视频时，播放状态一直是 READY，
                 * 光看 buffering 判断不出来，只能靠这个回调。
                 */
                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long,
                ) {
                    if (firstFrameAt == 0L) firstFrameAt = android.os.SystemClock.elapsedRealtime()
                }
            })
        }

    // ── 自动恢复状态 ────────────────────────────────────────────────
    private var currentChannel: Channel? = null
    private var retryAttempt = 0
    private val retryDelays = longArrayOf(1_000L, 2_000L, 4_000L)
    private var lastPosition = -1L
    private var stallMs = 0L
    private var healthyMs = 0L // 连续正常播放时长：满 20 秒把重试额度还回去
    private val watchdog = object : Runnable {
        override fun run() {
            checkStall()
            mainHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    /** 把 mime 翻译成台标上看得懂的名字；认不出的就取斜杠后半段。 */
    private fun shortCodec(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/mpeg2", "video/mp2v" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "EAC3"
        "audio/ac4" -> "AC4"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2", "audio/mp2" -> "MP2"
        else -> mime.substringAfter('/').take(12)
    }

    /**
     * 本机有没有这个编码的解码器。
     * 查不到就是「装多少参数都没用」，只能换引擎或换台——这个判断要准确，
     * 否则会把「源地址失效」误报成「解码器缺失」，用户白折腾。
     */
    private fun hasDecoder(mime: String): Boolean {
        if (mime.isBlank()) return true
        return runCatching { MediaCodecUtil.getDecoderInfo(mime, false, false) }.getOrNull() != null
    }

    private fun reportTracks(tracks: Tracks) {
        var video = ""
        var audio = ""
        for (group in tracks.groups) {
            if (group.length == 0) continue
            val format = group.getTrackFormat(0)
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    videoMime = format.sampleMimeType.orEmpty()
                    video = buildString {
                        append(shortCodec(videoMime))
                        if (format.width > 0 && format.height > 0) append(" ${format.width}×${format.height}")
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    audioMime = format.sampleMimeType.orEmpty()
                    audio = shortCodec(audioMime)
                }
            }
        }
        val text = listOf(video, audio).filter { it.isNotBlank() }.joinToString(" · ")
        if (text.isNotBlank()) onTracks(text)
    }

    /**
     * 轨道约束，每次起播都重新下发：
     * - 最高 1080p / 12Mbps：master playlist 里若同时有 4K 变体，默认选择器会挑它，
     *   老盒子解不动就表现为「黑屏 / 卡死」。压到 1080p 能直接消掉这一类。
     * - 音频轨按 [audioBypassed] 开关：AC3 这类本机没解码器时关掉音频保画面。
     */
    private fun applyTrackParams() {
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(1920, 1080)
                .setMaxVideoBitrate(12_000_000)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioBypassed)
                .build()
        }
    }

    /** 从异常链里挖 HTTP 状态码（403 防盗链 / 404 源失效 / 429 限流，修法完全不同）。 */
    private fun httpStatus(error: Throwable): Int? {
        var cur: Throwable? = error
        var guard = 0
        while (cur != null && guard++ < 6) {
            if (cur is HttpDataSource.InvalidResponseCodeException) return cur.responseCode
            // 部分版本把它包在 IOException 里，message 形如 "Response code: 403"
            val m = cur.message
            if (m != null && m.contains("Response code:", true)) {
                m.substringAfter("Response code:", "").trim().toIntOrNull()?.let { return it }
            }
            cur = cur.cause
        }
        return null
    }

    /**
     * 这一路流可以用的请求头组合，按「频道自带 → 播放器 UA → 浏览器 UA + 站内 Referer」排。
     * 站点根地址用作 Referer：绝大多数防盗链只校验来源域名，不校验完整路径。
     */
    private fun buildProfiles(ch: Channel): List<NetProfile> {
        val origin = runCatching {
            val u = java.net.URI(ch.url)
            val port = if (u.port > 0) ":" + u.port else ""
            "${u.scheme}://${u.host}$port/"
        }.getOrNull()
        return listOf(
            NetProfile(ch.userAgent.ifBlank { null }, ch.referrer.ifBlank { null }),
            NetProfile("VLC/3.0.21 LibVLC/3.0.21", origin),
            NetProfile(
                "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                origin,
            ),
        )
    }

    private fun onPlayerErrorInternal(error: PlaybackException) {
        val code = error.errorCodeName
        lastErrorCode = code
        healthyMs = 0L // 出错即中断连续播放，重新攒健康时长
        val msg = error.message ?: "播放失败"
        val ch = currentChannel
        val status = httpStatus(error)

        // 音频解码器初始化失败：不要整体放弃，把音频轨关掉继续出画面。
        // 国内源大量用 AC3/EAC3，而盒子往往只有 AAC 解码器——一关音频就活了。
        val failMime = decoderFailMime(error)
        if (failMime != null && failMime.startsWith("audio/") && !audioBypassed && ch != null) {
            audioBypassed = true
            applyTrackParams()
            onIssue(
                PlaybackIssue(
                    code = "AUDIO_CODEC_UNSUPPORTED",
                    message = "音频编码 ${shortCodec(failMime)} 本机不支持，已关闭音频继续播放",
                    stage = PlaybackIssue.Stage.STALL_RECOVER,
                    attempt = 0,
                    hint = "想听声音需要用 VLC 引擎（设置 → 播放引擎）",
                ),
            )
            retryAttempt = 0
            doPlay(ch)
            return
        }

        if (ch != null && status != null) {
            // ① 401/403 防盗链：换请求头，而不是原地址重试
            if (status == 401 || status == 403) {
                if (profileIndex + 1 < profiles.size) {
                    profileIndex++
                    freshFirstFrame()
                    onIssue(
                        PlaybackIssue(
                            code = code,
                            message = "源要求防盗链校验，正在换一组请求头重试（第 $profileIndex 组）",
                            stage = PlaybackIssue.Stage.RETRYING,
                            attempt = profileIndex,
                            http = status,
                        ),
                    )
                    // 请求头换完立刻重开，不需要退避——被拒的是身份，不是网络
                    mainHandler.postDelayed({ doPlay(ch) }, 250)
                    return
                }
                giveUp(code, "HTTP $status 防盗链：三组请求头都被拒绝", ch, status,
                    "这个源开了防盗链：换一个源；或把引擎切到 VLC 再试（VLC 的请求特征不同）")
                return
            }
            // ② 404/410 源已失效：重试一万次也没用，直接说清楚
            if (status == 404 || status == 410) {
                giveUp(code, "HTTP $status：这个频道的地址已经失效", ch, status,
                    "源提供者换了地址：刷新订阅（设置 → 刷新）或换一个源，别在这个台上耗时间")
                return
            }
            // ③ 429 限流：等 3 秒给一次机会，还是不行就放弃
            if (status == 429) {
                if (retryAttempt < 1) {
                    retryAttempt = 1
                    onIssue(PlaybackIssue(code, "请求太频繁被限流（HTTP 429），3 秒后再试", PlaybackIssue.Stage.RETRYING, 1, http = status))
                    mainHandler.postDelayed({ doPlay(ch) }, 3_000)
                    return
                }
                giveUp(code, "HTTP 429：源站持续限流", ch, status, "这个源限制了访问频率：换源，或过几分钟再试")
                return
            }
            // ④ 其它 4xx：地址/参数本身有问题，直接说清楚
            if (status in 400..499) {
                giveUp(code, "HTTP $status：服务端拒绝了这个请求", ch, status,
                    "地址可能已失效或需要特殊参数：换一个源")
                return
            }
        }

        if (ch == null || retryAttempt >= retryDelays.size) {
            // 3 次都失败：不再静默重试，把错误码和处置建议交给 UI 常驻显示
            onIssue(PlaybackIssue(code, msg, PlaybackIssue.Stage.GAVE_UP, retryAttempt, buildHint(code), status))
            onError(code + " / " + msg)
            return
        }
        retryAttempt++
        val wait = retryDelays[retryAttempt - 1]
        onIssue(PlaybackIssue(code, msg, PlaybackIssue.Stage.RETRYING, retryAttempt, http = status))
        // 必须在主线程重开；Handler 回调默认就在主线程
        mainHandler.postDelayed({ restart(ch, resetRetry = false) }, wait)
    }

    /** 统一走「放弃」：把错误码、HTTP 码、处置建议一次给全。 */
    private fun giveUp(code: String, message: String, ch: Channel?, http: Int?, hint: String) {
        onIssue(
            PlaybackIssue(
                code = code,
                message = message,
                stage = PlaybackIssue.Stage.GAVE_UP,
                attempt = retryAttempt,
                hint = hint,
                http = http,
            ),
        )
        onError(code + " / " + message)
    }

    /** 从异常链里挖出「哪个 mime 的解码器起不来」，挖不到返回 null。 */
    private fun decoderFailMime(error: PlaybackException): String? {
        var cause: Throwable? = error
        var guard = 0
        while (cause != null && guard++ < 4) {
            if (cause is MediaCodecRenderer.DecoderInitializationException) return cause.mimeType
            cause = cause.cause
        }
        return null
    }

    /**
     * 把错误码翻译成能照做的建议。
     * 用户看到的是一句人话，而不是 ERROR_CODE_DECODER_INIT_FAILED。
     */
    private fun buildHint(code: String): String = when {
        code.contains("DECODER_QUERY_FAILED", true) ->
            "本机完全没有这个编码的解码器" +
                (videoMime.takeIf { it.isNotBlank() }?.let { "（${shortCodec(it)}）" } ?: "") +
                "，请到设置里把播放引擎切到 VLC"
        code.contains("DECODER_INIT_FAILED", true) ->
            "解码器起不来（${videoMime.takeIf { it.isNotBlank() }?.let { shortCodec(it) } ?: "未知编码"}），" +
                "先在设置里换「解码方式」，仍不行就把引擎切到 VLC"
        code.contains("BAD_HTTP_STATUS", true) ->
            "源地址返回错误（多为 403 防盗链 / 404 已失效），换一个源或联系源提供者"
        code.contains("IO_", true) ->
            "网络打不开这个地址：检查网络是否连通、地址是否过期"
        code.contains("PARSING_CONTAINER", true) ->
            "码流结构不标准（TS 的 PMT 与内容不符），ExoPlayer 无法解析，请把引擎切到 VLC"
        code.contains("UNSUPPORTED", true) ->
            "这个格式 ExoPlayer 不支持，请把引擎切到 VLC"
        else -> ""
    }

    /** 每 3 秒采样：处于 buffering 且播放位置连续 15 秒不前进，就主动重开。 */
    private fun checkStall() {
        val ch = currentChannel ?: return
        if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) {
            // 不在缓冲态时，单独看「是不是根本没出过画面」
            detectBlackScreen()
            resetStall()
            return
        }
        val pos = player.currentPosition
        if (pos > lastPosition) {
            lastPosition = pos
            stallMs = 0L
            // 播满 20 秒说明这条流现在是好的，把之前用掉的重试次数还回去。
            // 否则一天里断几次后就永久失去自愈能力——那正是用户最烦的「再也不自动恢复」。
            healthyMs += SAMPLE_INTERVAL_MS
            if (healthyMs >= HEALTHY_RESET_MS && retryAttempt > 0) {
                retryAttempt = 0
                healthyMs = 0L
            }
            detectBlackScreen()
            return
        }
        stallMs += SAMPLE_INTERVAL_MS
        if (stallMs >= STALL_LIMIT_MS) {
            stallMs = 0L
            lastPosition = -1L
            onIssue(
                PlaybackIssue(
                    code = "STALL_NO_PROGRESS",
                    message = "缓冲卡住 15 秒，自动重连",
                    stage = PlaybackIssue.Stage.STALL_RECOVER,
                    attempt = 0,
                ),
            )
            // 卡顿恢复重置重试计数：这是新的一次卡顿，不该被旧错误拖累
            restart(ch, resetRetry = true)
        }
    }

    /**
     * 黑屏专项：起播 10 秒还没出过一帧，判定「解码器吐不出画面」。
     *
     * 这一类失败 ExoPlayer 不会报错——状态是 READY、声音可能有、就是黑着。
     * 老盒子（国科微 GK6323 / OMX 硬解）解 1080i 或高 profile H.264 时最典型。
     * 判成 GAVE_UP 后 AUTO 模式会自动切到 VLC，这才是真正能救回来的那一步。
     */
    private fun detectBlackScreen() {
        val ch = currentChannel ?: return
        if (!player.playWhenReady) return   // 暂停中不算黑屏
        if (firstFrameAt != 0L) return
        if (preparedAt == 0L) return
        if (player.playbackState != Player.STATE_READY &&
            player.playbackState != Player.STATE_BUFFERING
        ) return
        val waited = android.os.SystemClock.elapsedRealtime() - preparedAt
        if (waited < BLACK_LIMIT_MS) return
        // 只报一次：把 preparedAt 清掉当哨兵，避免每 3 秒刷一遍
        preparedAt = 0L
        onIssue(
            PlaybackIssue(
                code = "NO_FIRST_FRAME_10S",
                message = "起播 10 秒没有出画面（黑屏）",
                stage = PlaybackIssue.Stage.GAVE_UP,
                attempt = retryAttempt,
                hint = "多半是硬解解不动这个码流：把播放引擎切到 VLC，或把解码方式改成软解",
            ),
        )
    }

    /** 换请求头重开时把首帧计时重新算，别让上一组的黑屏判定误伤这一组。 */
    private fun freshFirstFrame() {
        firstFrameAt = 0L
        preparedAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun resetStall() {
        lastPosition = -1L
        stallMs = 0L
    }

    private fun restart(ch: Channel, resetRetry: Boolean) {
        if (resetRetry) retryAttempt = 0
        doPlay(ch)
    }

    fun play(channel: Channel) {
        // 手动换台是全新的一次播放，清掉上一台的错误/卡顿累积
        retryAttempt = 0
        lastErrorCode = ""
        // 换台必须把「静音保画面」的开关放回去：上一个台的 AC3 关过音频，
        // 不还原的话下一个 AAC 的台也会没声音。
        audioBypassed = false
        // 请求头档位也归零：新频道从它自己的 UA/Referer 开始试
        profileIndex = 0
        profiles = emptyList()
        resetStall()
        doPlay(channel)
    }

    private fun doPlay(channel: Channel) {
        currentChannel = channel
        // 每次起播都重新下发轨道约束（含音频轨开关状态）
        applyTrackParams()
        // 首帧计时：10 秒没画面就判黑屏（见 detectBlackScreen）
        preparedAt = android.os.SystemClock.elapsedRealtime()
        firstFrameAt = 0L

        // 请求头档位：0 = 频道自带，1/2 = 防盗链兜底
        if (profiles.isEmpty() || profileIndex == 0) profiles = buildProfiles(channel)
        val prof = profiles.getOrElse(profileIndex) { profiles.last() }
        val factory = OkHttpDataSource.Factory(Network.client)
            .setUserAgent(prof.ua ?: channel.userAgent.ifBlank { defaultAgent })
        val referer = prof.referer ?: channel.referrer.ifBlank { null }
        // 一次给全：setDefaultRequestProperties 是「整表替换」，分两次调用会把 Referer 冲掉
        val headers = mutableMapOf("Cache-Control" to "no-cache")
        if (referer != null) headers["Referer"] = referer
        factory.setDefaultRequestProperties(headers)
        val item = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaId(channel.id)
            .apply {
                if (channel.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()
        player.setMediaSource(
            DefaultMediaSourceFactory(factory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
                .createMediaSource(item),
        )
        player.prepare()
        player.playWhenReady = true
        resetStall()
        // 看门狗只跑一份：重开时先撤掉旧的周期任务再挂新的
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, SAMPLE_INTERVAL_MS)
    }

    /**
     * 换解码方式后重开当前频道。
     *
     * 解码器是在起播时选定的，只改 [decodeMode] 对已经在播的流不生效——
     * 必须重新 prepare 让选择器再走一遍。直播没有进度可保留，重开即可。
     */
    fun replay(channel: Channel?) {
        val ch = channel ?: return
        // 用户手动「再试一次」也是全新一轮：把静音兜底还原，重新给音频一次机会
        audioBypassed = false
        // 请求头也从头试：上次可能是网络抖动，不代表这个 UA 一定不行
        profileIndex = 0
        profiles = emptyList()
        restart(ch, resetRetry = true)
    }

    /**
     * 暂停：只停播放不释放解码器，OK/双击可立即恢复。
     * 同时撤掉看门狗——暂停期间不算「缓冲卡死」，否则退到后台会被误判重连。
     */
    fun pause() {
        player.playWhenReady = false
        resetStall()
        mainHandler.removeCallbacks(watchdog)
    }

    /** 恢复播放：重启看门狗，让断线自愈继续生效。 */
    fun resume() {
        player.playWhenReady = true
        resetStall()
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, SAMPLE_INTERVAL_MS)
    }

    val isPaused: Boolean get() = !player.playWhenReady

    fun stop() {
        player.stop()
        mainHandler.removeCallbacks(watchdog)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        player.release()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 3_000L
        const val STALL_LIMIT_MS = 15_000L
        const val HEALTHY_RESET_MS = 20_000L
        /** 起播后多久还没有首帧就判黑屏。10 秒足够慢速源起播，又不会让用户干等。 */
        const val BLACK_LIMIT_MS = 10_000L
    }
}
