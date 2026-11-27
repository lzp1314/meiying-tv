package com.lik.iptvplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.lik.iptvplayer.core.Channel
import com.lik.iptvplayer.core.DecodeMode
import com.lik.iptvplayer.core.Diag
import com.lik.iptvplayer.core.LogoLoader
import com.lik.iptvplayer.core.PairInfo
import com.lik.iptvplayer.core.PairingServer
import com.lik.iptvplayer.core.PlayEngine
import com.lik.iptvplayer.core.QrEncoder
import com.lik.iptvplayer.core.RefreshResult
import com.lik.iptvplayer.core.Source
import com.lik.iptvplayer.core.SourceRepository
import com.lik.iptvplayer.player.PlaybackIssue
import com.lik.iptvplayer.player.PlayerController
import com.lik.iptvplayer.player.VlcEngine
import com.lik.iptvplayer.player.Warmer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Activity 与 Compose 之间的按键桥：在 dispatchKeyEvent 里先拦，避免和焦点系统抢事件。 */
class KeyBridge {
    var onKey: ((Int) -> Boolean)? = null
}

/**
 * libVLC 引擎的惰性持有器。
 *
 * 刻意用普通可变字段而不是 Compose State：引擎只在按键回调、播放回调里读写，
 * 写 State 会在组合期间触发重组告警；它也不需要驱动界面刷新——
 * 界面关心的是「这一轮走不走 VLC」，那是另一份 boolean 状态。
 */
internal class VlcHolder {
    var engine: VlcEngine? = null
}

internal enum class Panel { Lobby }

/** 手机端推送过来的、还没落盘的源。用户确认后才写进 SourceStore。 */
internal data class DraftSource(val name: String, val url: String, val epg: String = "")

/** 换台时屏幕左上角闪一下的提示：频道序号 + 台标 + 名称 + 当前/下一节目。 */
private data class ChannelOsd(val index: Int, val channel: Channel)

internal val CardBg = Color(0xFF101A2B)
internal val RowBg = Color(0xFF16223A)
internal val RowActive = Color(0xFF16304F)
internal val RowFocus = Color(0xFF1D4ED8)
// 频道抽屉里的行用半透明底，跟抽屉的通透感一致，不会一块块发实
internal val ChRowBg = Color(0x5916223A)
internal val ChRowActive = Color(0x9916304F)
internal val TextDim = Color(0xFF94A3B8)
internal val Accent = Color(0xFF38BDF8)

/** 聚焦时的醒目描边色：比纯背景高亮更容易在电视上辨认光标位置。 */
internal val FocusRing = Color(0xFF60A5FA)

/**
 * 电视端统一焦点描边：聚焦时加一圈亮蓝边 + 轻微提亮背景。
 * 只描边不改布局尺寸，避免焦点在列表里跳动。
 */
internal fun Modifier.focusRing(focused: Boolean, shape: RoundedCornerShape): Modifier =
    this.border(
        width = if (focused) 2.dp else 0.dp,
        color = if (focused) FocusRing else Color.Transparent,
        shape = shape,
    )

/**
 * 手机端与 TV 端共用的播放外壳。
 *
 * 关键结构：PlayerView 只在这一个 Box 里常驻，**永远不换父容器**。
 * 旧版本把同一个 PlayerView 分别挂到「小窗」和「全屏」两处，切换时会抛
 * "The specified child already has a parent" 闪退，这里从结构上消除。
 * 设置界面是盖在上面的浮层，不参与播放器的挂载。
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    repo: SourceRepository,
    isTv: Boolean,
    keys: KeyBridge,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by repo.sources.collectAsState()
    val lockedId by repo.lockedId.collectAsState()

    val controller = remember { PlayerController(context, repo.prefs.decodeMode) }
    // 解码方式：跟着偏好走，改了立刻重开当前台（解码器只在起播时选定）
    var decodeMode by remember { mutableStateOf(repo.prefs.decodeMode) }
    val playerView = remember {
        PlayerView(context).apply {
            player = controller.player
            // 直播不需要进度条；关掉自带控制条，避免和「点击出设置」抢触摸
            useController = false
            keepScreenOn = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
    // libVLC 引擎（第二引擎）：只在真遇到 ExoPlayer 播不了的台时才创建，
    // 平时不初始化——它常驻内存 30–60MB，老盒子承受不起白占。
    val vlcHolder = remember { VlcHolder() }
    // 这一轮播放实际走的引擎。true = VLC 画面在上、ExoPlayer 视图隐藏。
    var usingVlc by remember { mutableStateOf(false) }
    var playEngine by remember { mutableStateOf(repo.prefs.playEngine) }

    /**
     * VLC 初始化/挂载失败的完整原因。0.5.0 只有一句「初始化失败」，
     * 分不清是原生库加载不了、还是画面组件挂不上——那两种修法完全不同。
     * 现在把它显示在设置页诊断行里，用户截个图就能定位。
     */
    var vlcDiag by remember { mutableStateOf("") }

    /**
     * 本次播放的引擎试错轨迹，只在 AUTO 模式下有意义：
     * - [vlcTriedThisPlay]：这一轮已经试过 VLC。试过就不再切，避免 ExoPlayer↔VLC 乒乓；
     * - [exoRetriedAfterVlc]：已经从 VLC 退回 ExoPlayer 了，这是最后一试，再失败就报错。
     * 换台时两个都归零（新的一次播放，从头再来）。
     */
    var vlcTriedThisPlay by remember { mutableStateOf(false) }
    var exoRetriedAfterVlc by remember { mutableStateOf(false) }

    /**
     * VLC 播失败后要回退的频道。非空表示「请用 ExoPlayer 再播一次」。
     * 不能直接在 VLC 的错误回调里调 startEngine：局部函数在 lambda 里
     * 只能引用它之前声明的东西，而 startEngine 定义在后面。
     */
    var exoFallbackRequest by remember { mutableStateOf<Channel?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            Warmer.schedule(null)
            vlcHolder.engine?.release()
            vlcHolder.engine = null
            controller.release()
        }
    }

    // 暂停状态：OK / 双击 / 退后台都会改它；换台时重置为播放
    var paused by remember { mutableStateOf(false) }

    // 退到后台（按 Home、切应用、锁屏）立即停播，避免手机退出后声音还在响。
    // 官方规范：播放器所在 Activity 进 onStop 就该停；这里只 pause 不 release，
    // 回到前台画面还在、按一次 OK 就能续播。看门狗由 controller.pause() 一并撤销。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                Warmer.schedule(null)   // 退后台：别在用户看不见时发预热请求
                controller.pause()
                vlcHolder.engine?.pause()
                paused = true
            }
        }
        (lifecycleOwner as? LifecycleOwner)?.lifecycle?.addObserver(observer)
        onDispose { (lifecycleOwner as? LifecycleOwner)?.lifecycle?.removeObserver(observer) }
    }

    // 实际启用的解码器。老盒子上「画面一顿一顿」多半是走了软解，
    // 把名字显示在设置里就能直接判定，不用连 adb 看日志。
    var decoderName by remember { mutableStateOf("") }
    // 当前流的编码摘要（H.264 1280×720 · AC3）。黑屏/卡住/报错时用户报的现象都一样，
    // 只有这一行能区分到底是哪类解码问题。
    var mediaInfo by remember { mutableStateOf("") }
    SideEffect {
        controller.onDecoder = { name -> decoderName = name }
        controller.onTracks = { info -> mediaInfo = info }
    }

    // toast 要先于下面的错误回调定义：回调里要写它
    var toast by remember { mutableStateOf<String?>(null) }

    // 返回键二次确认：正常播放时按一次只提示，2 秒内再按才真退出。
    // 侧栏/出错界面里返回键另有含义，见下面的按键桥。
    var backArmed by remember { mutableStateOf(false) }
    LaunchedEffect(backArmed) {
        if (backArmed) {
            delay(2000)
            backArmed = false
        }
    }

    // 播放异常状态：重试中给轻提示，3 次失败后常驻屏幕；错误码始终保留供 OSD/设置显示
    var fatalError by remember { mutableStateOf<PlaybackIssue?>(null) }
    var errorCode by remember { mutableStateOf("") }

    // 进应用即全屏：手机端同时锁横屏，TV 端只隐藏系统栏
    FullscreenEffect(enabled = true, landscape = !isTv)

    var panel by remember { mutableStateOf<Panel?>(null) }
    // 电视大厅当前在哪一页。手机端不用大厅，靠抽屉 + 齿轮
    var lobbyTab by remember { mutableStateOf(LobbyTab.Channels) }
    // 进设置面板时清掉待执行的预热：用户此刻不在看台，3 秒后没必要再替他发请求
    LaunchedEffect(panel, lobbyTab) {
        if (panel == Panel.Lobby && lobbyTab == LobbyTab.Settings) Warmer.schedule(null)
    }
    var activeId by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<RefreshResult?>(null) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var loading by remember { mutableStateOf(false) }
    /** 没有缓存又拉取失败时的错误文案；非空就占据主界面并给重试入口 */
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    // 换台提示：电视上换台完全看不到当前是第几台、也没有台标，这里补上
    var osd by remember { mutableStateOf<ChannelOsd?>(null) }
    // 数字键缓冲：连续按的几位数字攒在这里，1.6 秒没新输入就跳台
    var digitBuf by remember { mutableStateOf("") }
    var osdLogo by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    // 遥控器上下键方向：true = 上键下一个台
    var keyUpNext by remember { mutableStateOf(repo.prefs.keyUpNext) }
    var pairInfo by remember { mutableStateOf<PairInfo?>(null) }
    // 正在编辑的源；非空时弹编辑框
    var editing by remember { mutableStateOf<Source?>(null) }
    val pending = remember { mutableStateListOf<DraftSource>() }

    // 频道列表要列出「所有源」：当前源用内存结果，其余源读本地快照（不联网，打开即出）
    var otherChannels by remember { mutableStateOf<Map<String, RefreshResult>>(emptyMap()) }
    // 0.5.4 把 panel / lobbyTab 也放在 key 里，导致每次切页都把每个源的快照
    // 重新读一遍、解析一遍（几 MB JSON）。老盒子上就是一次明显卡顿。
    // 快照只取决于「有哪些源」和「哪个是当前源」，跟面板开没开无关。
    LaunchedEffect(sources, activeId) {
        val map = mutableMapOf<String, RefreshResult>()
        for (s in sources) {
            if (s.id == activeId) continue
            runCatching { repo.loadCached(s.id) }.getOrNull()?.let { map[s.id] = it }
        }
        otherChannels = map
    }
    val channelBlocks = remember(sources, activeId, result, otherChannels) {
        sources.map { s ->
            ChannelSourceBlock(
                source = s,
                result = if (s.id == activeId) result else otherChannels[s.id],
                isActive = s.id == activeId,
            )
        }
    }

    // 手势用：起始位置决定调什么，累计量放数组里避免每帧重组
    val gestureAcc = remember { floatArrayOf(0f, 0f) }
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    // 当前亮度（Window 级，不改系统设置，退出应用自动恢复）
    var brightness by remember {
        mutableStateOf(
            runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f),
        )
    }

    val deviceLabel = if (isTv) "电视" else "手机"

    // 手机扫码推送过来的源：先进「待保存」，用户确认才落盘
    val pairing = remember {
        PairingServer(deviceLabel) { url, name, epg ->
            scope.launch {
                pending.add(DraftSource(name, url, epg))
                message = "已收到「$name」，确认后点保存"
                toast = "收到新源：$name"
            }
            true
        }
    }
    DisposableEffect(Unit) { onDispose { pairing.stop() } }

    // 设置面板打开时才开配对服务，关掉就停，缩小暴露窗口
    DisposableEffect(panel) {
        if (panel == Panel.Lobby) {
            val info = pairing.start()
            pairInfo = info
            if (info == null) message = "配对服务启动失败：端口被占用，可改用本机扫码或手填地址"
        }
        onDispose {
            pairing.stop()
            pairInfo = null
        }
    }

    /**
     * 切换解码方式。解码器是起播时选定的，所以必须重开当前台才生效；
     * 直播没有进度可保留，重开即可。
     */
    fun setDecodeMode(m: DecodeMode) {
        if (decodeMode == m) return
        decodeMode = m
        repo.prefs.decodeMode = m
        controller.decodeMode = m
        val ch = current
        if (usingVlc) {
            // VLC 自己按 --avcodec-hw=any 走硬解并自动回落软解，不吃这个开关
            toast = "解码：${m.label} · 仅对 ExoPlayer 生效"
            return
        }
        if (ch != null) {
            controller.replay(ch)
            toast = "解码：${m.label} · 已重新起播"
        } else {
            toast = "解码：${m.label} · 下次起播生效"
        }
    }

    // ── 引擎调度 ────────────────────────────────────────────────────

    /**
     * 拿到 libVLC 引擎，第一次用的时候才创建。
     *
     * 它就是「ExoPlayer 吃不下的台」的兜底：MPEG-2 视频、AC3/EAC3 音频、
     * PMT 与内容不符的非标准 TS，这些台在 ExoPlayer 上必然黑屏或报错。
     */
    fun ensureVlc(): VlcEngine {
        vlcHolder.engine?.let { return it }
        val e = VlcEngine(context.applicationContext)
        e.onStarted = {
            fatalError = null
            // VLC 真正出画面才算「它对这台有效」，记下来供 AUTO 下次直接用。
            // 注意：不是「切到 VLC」就记，必须等 Vout 回调——这是唯一可信的成功信号。
            val ch = current
            val sid = activeId
            if (ch != null && sid != null) repo.prefs.rememberEngine(sid, ch, PlayEngine.VLC)
        }
        // VLC 的分辨率走 Vout 事件回传；ExoPlayer 走 controller.onTracks。
        // 两条路都写 mediaInfo，换台 OSD 和设置诊断共用同一份，不用维护两套状态。
        e.onVideoSize = { text -> mediaInfo = text }
        e.onError = { msg ->
            // 分清「引擎自己没起来」和「这条流播不动」：
            // 前者换台也没用，得看设置里的诊断；后者才是换台/换源。
            val engineBroken = msg.startsWith("VLC 引擎未就绪") || msg.startsWith("VLC 画面组件挂载失败")
            val ch = current
            val sid = activeId
            // AUTO 下 VLC 也不行：回 ExoPlayer 再试一次。只回一次，避免两个引擎乒乓。
            // 引擎本身没起来（ABI/视图问题）就别回去了，换回去也一样播不了。
            val canFallback = repo.prefs.playEngine == PlayEngine.AUTO &&
                !engineBroken && !exoRetriedAfterVlc && ch != null
            if (canFallback) {
                exoRetriedAfterVlc = true
                if (sid != null) repo.prefs.noteEngineFailure(sid, ch!!, PlayEngine.VLC)
                toast = "VLC 也播不了，回 ExoPlayer 再试一次"
                exoFallbackRequest = ch
            } else {
                fatalError = PlaybackIssue(
                    code = if (engineBroken) "VLC_INIT_FAILED" else "VLC_FAILED",
                    message = msg,
                    stage = PlaybackIssue.Stage.GAVE_UP,
                    attempt = 0,
                    hint = if (engineBroken) {
                        "VLC 引擎没起来，这台机器先用 ExoPlayer；原因见「设置 → VLC 引擎」那一行"
                    } else {
                        "ExoPlayer 和 VLC 都播不了这个台，多为地址失效或码流已损坏，建议换台或换源"
                    },
                )
            }
        }
        vlcHolder.engine = e
        return e
    }

    /**
     * 用指定引擎起播。切引擎时一定先停另一边：
     * 两个播放器同时开着会抢音频输出（表现为一个有声一个静音）并白占解码器。
     */
    fun startEngine(vlc: Boolean, ch: Channel) {
        if (vlc) {
            // VLC 初始化可能失败（个别机型的 ABI 或系统库不匹配）。
            // 失败就老老实实退回 ExoPlayer——宁可少一个引擎，也不能让本来能播的台变黑屏。
            val built = runCatching { ensureVlc() }
            val engine = built.getOrNull()
            if (engine == null) {
                // 0.5.0 只吐一句「初始化失败」，异常被 getOrNull() 吞掉，线上无从定位。
                // 0.5.1 起把真实原因（异常类名 + message + cause）显示出来并存进诊断行。
                val t = built.exceptionOrNull()
                vlcDiag = t?.let { e ->
                    buildString {
                        append(e.javaClass.simpleName)
                        e.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    }
                }?.ifBlank { null } ?: "构造 VlcEngine 时抛出异常（无 message）"
                toast = "VLC 引擎初始化失败，仍用 ExoPlayer（详见设置页诊断）"
                startEngine(false, ch)
                return
            }
            // 引擎对象建好了，但 native 层可能仍然没起来（buildEngine 两档全失败时 initError 非空）。
            // 这时绝不能切 usingVlc：画面层是空的，用户只会看到黑屏加一句「引擎未就绪」。
            // 直接退回 ExoPlayer，至少这个台还有机会播。
            if (engine.initError.isNotBlank()) {
                vlcDiag = engine.initError
                toast = "VLC 引擎没起来，改用 ExoPlayer（原因见设置页）"
                startEngine(false, ch)
                return
            }
            controller.stop()
            usingVlc = true
            // ExoPlayer 的编码/解码器诊断对 VLC 不成立，留着只会误导排查
            mediaInfo = ""
            decoderName = "VLC 引擎"
            engine.play(ch)
        } else {
            vlcHolder.engine?.stop()
            usingVlc = false
            // 个别源的地址会让 ExoPlayer 直接抛参数异常，兜住，别把整个界面带崩
            runCatching { controller.play(ch) }
                .onFailure { toast = "这个台播不了：${it.message ?: "地址无效"}" }
        }
    }

    /**
     * 执行「VLC 失败 → 回退 ExoPlayer」。放在 startEngine 之后才能引用它。
     * 走状态中转只多一帧，换来的是在任何时候触发都能可靠执行。
     */
    LaunchedEffect(exoFallbackRequest) {
        val ch = exoFallbackRequest ?: return@LaunchedEffect
        exoFallbackRequest = null
        startEngine(false, ch)
    }

    /**
     * 切换播放引擎并立即重开当前台。
     *
     * 必须真的重开——两个引擎各自持有解码器和音频输出，
     * 只改设置不切换的话，屏幕上是旧引擎的画面，用户会以为没生效。
     *
     * 手动选择会被记成这个台的引擎记忆：用户说哪个行，就按哪个来。
     * 选「自动」则清掉记忆，让 AUTO 重新判断。
     */
    fun setPlayEngine(e: PlayEngine) {
        if (playEngine == e) return
        playEngine = e
        repo.prefs.playEngine = e
        val ch = current
        val sid = activeId
        if (ch == null) {
            toast = "引擎：${e.label}"
            return
        }
        if (sid != null) {
            if (e == PlayEngine.AUTO) {
                repo.prefs.forgetEngine(sid, ch)
            } else {
                repo.prefs.rememberEngine(sid, ch, e)
            }
        }
        // 手动切换是新的一轮试错，把 AUTO 的兜底轨迹归零
        vlcTriedThisPlay = e == PlayEngine.VLC
        exoRetriedAfterVlc = false
        val wantVlc = when (e) {
            PlayEngine.VLC -> true
            PlayEngine.EXO -> false
            PlayEngine.AUTO -> sid?.let { repo.prefs.engineOf(it, ch) } == PlayEngine.VLC
        }
        startEngine(wantVlc, ch)
        toast = "引擎：${e.label} · 已重新起播"
    }

    /** 播放/暂停切换：直播暂停是停在同一帧，恢复即继续，不需要重新拉流。 */
    fun togglePause() {
        if (current == null) return
        if (paused) {
            if (usingVlc) vlcHolder.engine?.resume() else controller.resume()
            paused = false
            toast = "继续播放"
        } else {
            if (usingVlc) vlcHolder.engine?.pause() else controller.pause()
            paused = true
            toast = "已暂停 · OK / 双击继续"
        }
    }

    fun playChannel(ch: Channel, sourceId: String?) {
        current = ch
        // 换台即视为新的一次播放：清掉上一台残留的致命错误提示
        fatalError = null
        // 换台一定从播放态起播，不继承上一台的暂停状态
        paused = false
        if (sourceId != null) {
            repo.markUsed(sourceId)
            repo.prefs.setLastChannel(sourceId, ch)
        }
        // 固定一份列表快照：序号和「下一个台」都从它算，避免两次读到不同状态
        val list = result?.channels.orEmpty()
        val idx = list.indexOfFirst { it.id == ch.id }
        // 换台提示：电视上原来换完台屏幕没有任何反馈，不知道切到第几台了
        osd = ChannelOsd(if (idx >= 0) idx + 1 else 0, ch)

        // 引擎选择：强制 VLC / 强制 ExoPlayer 都照做；AUTO 先查这个台**成功过**的引擎
        // （只记出过画面的，不记失败），没记忆就一律 ExoPlayer 先试——
        // 它对 HLS 直播最稳、起播最快，VLC 只当兜底。
        val pref = repo.prefs.playEngine
        val remembered = sourceId?.let { repo.prefs.engineOf(it, ch) }
        val wantVlc = when (pref) {
            PlayEngine.VLC -> true
            PlayEngine.EXO -> false
            PlayEngine.AUTO -> remembered == PlayEngine.VLC
        }
        // 直接以 VLC 起播时，这一轮就算「VLC 已试过」，后面不能再切回 VLC
        vlcTriedThisPlay = wantVlc
        exoRetriedAfterVlc = false
        startEngine(wantVlc, ch)

        // P3 预热：只预热同一分组内的下一个台。跨分组说明用户在跳组，不猜。
        // 3 秒去抖和失败静默都在 Warmer 里；这里算错了也只是少命中一次，不影响播放。
        val next = if (idx >= 0) list.getOrNull(idx + 1) else null
        Warmer.schedule(next?.takeIf { it.group == ch.group })
    }

    // 播放异常回调放在这里，因为它要调 startEngine（AUTO 模式自动换引擎）。
    SideEffect {
        controller.onIssue = { ev ->
            errorCode = ev.code
            when (ev.stage) {
                PlaybackIssue.Stage.RETRYING ->
                    toast = "${ev.kind}问题 · ${ev.code}，${ev.attempt}/3 次后重连"
                PlaybackIssue.Stage.STALL_RECOVER ->
                    // 音频解码不支持时给出的是处置说明，不要被「卡顿」文案盖掉
                    toast = if (ev.code == "AUDIO_CODEC_UNSUPPORTED") ev.message else "卡顿 15 秒 · 自动重连中"
                PlaybackIssue.Stage.GAVE_UP -> {
                    val ch = current
                    // AUTO：ExoPlayer 这轮还没试过 VLC、且不是「地址根本打不开」这类
                    // 换引擎也没用的问题，就换 VLC 再试一次。
                    // 只在这一轮内兜底，不写任何「这个台以后都用 VLC」的永久标记——
                    // 那正是 0.5.2 把能播的台锁死在 VLC 上的原因。
                    val autoSwitch = repo.prefs.playEngine == PlayEngine.AUTO &&
                        !usingVlc && ch != null && ev.switchEngineMayHelp && !vlcTriedThisPlay
                    if (autoSwitch) {
                        vlcTriedThisPlay = true
                        toast = "ExoPlayer 播不了（${ev.kind}），改用 VLC 再试"
                        startEngine(true, ch!!)
                    } else {
                        fatalError = ev
                    }
                }
            }
        }
        // ExoPlayer 真出画面 = 这个引擎对这个台有效，记下来供 AUTO 下次直接用
        controller.onFirstFrame = {
            Diag.BootTiming.markFirstFrame(if (usingVlc) "VLC" else "ExoPlayer")
            val ch = current
            val sid = activeId
            if (ch != null && sid != null) repo.prefs.rememberEngine(sid, ch, PlayEngine.EXO)
            fatalError = null
        }
    }

    /**
     * 数字键换台。
     *
     * 序号用的就是频道列表里显示的那个号（从 1 开始、按整份列表排），
     * 所以「按 1 回到 1 频道」和肉眼看到的编号完全一致。
     */
    fun jumpToNumber(text: String) {
        val n = text.toIntOrNull() ?: return
        val list = result?.channels.orEmpty()
        if (list.isEmpty()) return
        if (n < 1 || n > list.size) {
            toast = "没有第 $n 台（共 ${list.size} 个频道）"
            return
        }
        playChannel(list[n - 1], activeId)
    }

    fun pressDigit(d: Char) {
        // 最多 3 位：四位数已经超出任何频道的编号范围，再攒只会误判
        digitBuf = (digitBuf + d).takeLast(3)
    }

    // 数字键：1.6 秒内没有新输入就跳台；期间继续按数字则接着拼接。
    // 用 LaunchedEffect 而不是定时器，是因为 key 一变它自己会取消上一次计时。
    LaunchedEffect(digitBuf) {
        if (digitBuf.isEmpty()) return@LaunchedEffect
        delay(1_600)
        val text = digitBuf
        digitBuf = ""
        jumpToNumber(text)
    }

    /**
     * 换台提示 3.2 秒后自动收起（0.5.8 修复常驻 bug）。
     *
     * 旧写法把「拉台标」和「延时收起」串在同一个协程里，且 delay 排在
     * LogoLoader.load() 之后：台标请求一旦排队（并发限 3）或卡在网络上，
     * delay 根本轮不到执行，OSD 就永远挂在左下角。
     * 现在收起计时只看换台 id，和台标加载完全解耦——无论台标多慢都按时消失。
     */
    LaunchedEffect(osd?.channel?.id) {
        val id = osd?.channel?.id ?: return@LaunchedEffect
        delay(3_200)
        if (osd?.channel?.id == id) osd = null
    }

    /** 台标单独拉：走内存缓存，反复切台不重复下载；1.6 秒硬超时，绝不拖住收起。 */
    LaunchedEffect(osd?.channel?.id, osd?.channel?.logo) {
        val shown = osd ?: return@LaunchedEffect
        val logoUrl = shown.channel.logo
        if (logoUrl.isBlank()) return@LaunchedEffect
        val bmp = withTimeoutOrNull(1_600) { LogoLoader.load(logoUrl, 120) }
        if (bmp != null && osd?.channel?.id == shown.channel.id) {
            osdLogo = shown.channel.id to bmp
        }
    }

    /** 把缓存时长说成人话，显示在设置面板里，方便判断数据新旧。 */
    fun ageText(ms: Long): String = when {
        ms < 0 -> "本地缓存"
        ms < 60_000 -> "刚刚更新"
        ms < 3_600_000 -> "${ms / 60_000} 分钟前更新"
        ms < 86_400_000 -> "${ms / 3_600_000} 小时前更新"
        else -> "${ms / 86_400_000} 天前更新"
    }

    fun startPlayback(r: RefreshResult) {
        // 用稳定引用找回上次的台；找不到才回退第一个（旧版按 ch-序号找，换序就串台）
        val ch = repo.prefs.findLastChannel(r.sourceId, r.channels) ?: r.channels.firstOrNull()
        if (ch != null) playChannel(ch, r.sourceId)
    }

    /**
     * 加载某个源。
     *
     * 顺序刻意是「先缓存、后网络」：
     * 1. 内存 -> 磁盘快照，命中就立刻渲染频道列表并起播，不用等下载；
     * 2. 缓存两小时内视为新鲜，直接收工不联网（避免每次进应用都重拉）；
     * 3. 缓存过期或用户手动刷新才后台静默更新，正在播的台不打断。
     */
    fun load(id: String, autoplay: Boolean, force: Boolean = false) {
        activeId = id
        Warmer.schedule(null)   // 切源：上一个源排队的预热立即作废，不给新源添乱
        repo.markUsed(id)
        loadError = null
        scope.launch {
            val cacheStart = SystemClock.elapsedRealtime()
            val cached = repo.loadCached(id)
            Diag.BootTiming.markCache(SystemClock.elapsedRealtime() - cacheStart)
            if (cached != null) {
                result = cached
                message = "${cached.channels.size} 个频道 · ${ageText(repo.cacheAge(id))}"
                if (autoplay) startPlayback(cached)
            }

            if (!force && cached != null && !repo.isStale(id)) return@launch

            // 有缓存时静默刷新：不显示「正在加载」，画面照常播
            loading = cached == null
            if (cached == null) message = "正在拉取频道与节目单…"
            val refreshStart = SystemClock.elapsedRealtime()
            repo.refresh(id).fold(
                onSuccess = { r ->
                    loading = false
                    loadError = null
                    result = r
                    message = buildString {
                        append("${r.channels.size} 个频道")
                        if (r.epgError != null) append(" · 节目单不可用（${r.epgError}）")
                        else append(" · 节目单匹配 ${r.epgMatchedCount} 个")
                    }
                    // 刷新不该打断正在看的台：能对上就只更新节目单，对不上才重选
                    val keep = current?.id?.let { cid -> r.channels.firstOrNull { it.id == cid } }
                    if (keep != null) current = keep else if (autoplay) startPlayback(r)
                },
                onFailure = { e ->
                    loading = false
                    val text = "拉取失败：${e.message ?: e.javaClass.simpleName}"
                    if (cached != null) {
                        // 有缓存就继续用，失败只在设置里留一行说明
                        message = "沿用本地缓存 · $text"
                    } else {
                        message = text
                        // 不能只在设置里写一行——主界面会一直显示「正在加载频道…」，
                        // 用户以为卡死了。这里把失败摆到屏幕上并给一个重试入口。
                        loadError = text
                    }
                },
            )
            Diag.BootTiming.markRefresh(SystemClock.elapsedRealtime() - refreshStart)
        }
    }

    /**
     * 刷新一条源，但不切过去、不打断正在播的台（0.5.7）。
     *
     * 和 [load] 的区别：load 会切 activeId、可能重启播放；这里只更新快照。
     * repo.refresh 内部会写缓存并更新 sources 列表，调用方只需把结果同步到界面。
     * @param quiet 静默模式：批量刷新时不逐条弹 toast，由调用方最后统一提示
     */
    suspend fun refreshOne(s: Source, quiet: Boolean = false): Boolean {
        val r = repo.refresh(s.id).getOrNull() ?: return false
        if (s.id == activeId) {
            // 当前源：只把节目单/频道列表换成新的，current 若能对上就原地更新，
            // 绝不重新起播——用户点刷新时正在看的台不能断。
            result = r
            val keep = current?.id?.let { cid -> r.channels.firstOrNull { it.id == cid } }
            if (keep != null) current = keep
        } else {
            otherChannels = otherChannels + (s.id to r)
        }
        if (!quiet) toast = "已刷新「${s.name}」"
        return true
    }

    /** 刷新所有已添加的源：串行，一个完成再下一个，不打断当前播放。 */
    fun refreshAllSources() {
        val all = repo.sources.value.toList()
        if (all.isEmpty()) {
            toast = "还没有添加源，先扫码添加"
            return
        }
        scope.launch {
            toast = "正在刷新 ${all.size} 条源（1/${all.size}）…"
            var ok = 0
            all.forEachIndexed { i, s ->
                if (refreshOne(s, quiet = true)) ok++
                if (i < all.size - 1) toast = "正在刷新 ${all.size} 条源（${i + 2}/${all.size}）…"
            }
            toast = "刷新完成：$ok/${all.size} 条源"
        }
    }

    /** 只刷新一条源（设置/信号源页每行「刷新」按钮调用）。 */
    fun refreshSource(s: Source) {
        scope.launch { refreshOne(s) }
    }

    /**
     * 只重拉所有源的节目单，不重下 M3U 订阅（0.5.8）。
     * 和 [refreshAllSources] 的区别：订阅列表不动，只换 EPG 三张表，
     * 所以用户的手填节目单地址改动后能最快生效，也不会因为订阅服务器慢而卡住。
     */
    fun refreshAllEpg() {
        val all = repo.sources.value.toList()
        if (all.isEmpty()) {
            toast = "还没有添加源，先扫码添加"
            return
        }
        scope.launch {
            toast = "正在刷新 ${all.size} 条源的节目单（1/${all.size}）…"
            var ok = 0
            all.forEachIndexed { i, s ->
                val r = repo.refreshEpg(s.id).getOrNull()
                if (r != null) {
                    ok++
                    if (s.id == activeId) {
                        result = r
                        val keep = current?.id?.let { cid -> r.channels.firstOrNull { it.id == cid } }
                        if (keep != null) current = keep
                    } else {
                        otherChannels = otherChannels + (s.id to r)
                    }
                }
                if (i < all.size - 1) toast = "正在刷新节目单（${i + 2}/${all.size}）…"
            }
            toast = "节目单刷新完成：$ok/${all.size} 条源"
        }
    }

    fun switchTo(s: Source) {
        val locked = repo.lockedId.value
        if (locked != null && locked != s.id) {
            val lockName = sources.firstOrNull { it.id == locked }?.name ?: "已锁定的源"
            message = "已锁定「$lockName」，先解锁才能切换"
            toast = "已锁定「$lockName」，先解锁"
            return
        }
        current = null
        result = null
        panel = null
        load(s.id, autoplay = true)
    }

    fun toggleLock(s: Source) {
        if (repo.lockedId.value == s.id) {
            repo.setLocked(null)
            message = "已解锁「${s.name}」"
            toast = "已解锁：${s.name}"
        } else {
            repo.setLocked(s.id)
            message = "已锁定「${s.name}」：每次启动固定用它，切换其它源需先解锁"
            toast = "已锁定：${s.name}"
            if (activeId != s.id) {
                current = null
                result = null
                load(s.id, autoplay = true)
            }
        }
    }

    fun saveDraft(d: DraftSource) {
        val added = repo.add(d.name, d.url, d.epg)
        if (added == null) {
            message = "地址不合法，仅支持 http / https"
            return
        }
        pending.remove(d)
        message = "已保存「${added.name}」"
        toast = "已保存：${added.name}"
        if (activeId == null || current == null) load(added.id, autoplay = true)
    }

    fun step(delta: Int) {
        val list = result?.channels.orEmpty()
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == current?.id }
        val next = if (idx < 0) 0 else ((idx + delta) % list.size + list.size) % list.size
        playChannel(list[next], activeId)
    }

    /**
     * 亮度按「屏高比例」连续调：手指滑过整屏高度约等于 120% 的变化量。
     * 只改当前窗口的 screenBrightness，不动系统设置——退出应用自动还原。
     */
    fun applyBrightness(delta: Float) {
        val v = (brightness + delta).coerceIn(0.03f, 1f)
        brightness = v
        activity?.let { a ->
            val attrs = a.window.attributes
            attrs.screenBrightness = v
            a.window.attributes = attrs
        }
        toast = "亮度 ${(v * 100).roundToInt()}%"
    }

    /**
     * 音量走 STREAM_MUSIC。手指移动是连续的，档位是整数，
     * 所以先把不足一档的量攒在 gestureAcc[1] 里，攒够再动一格，避免来回抖。
     */
    fun applyVolume(deltaFraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        gestureAcc[1] += deltaFraction * max
        val whole = gestureAcc[1].toInt()
        if (whole == 0) return
        gestureAcc[1] -= whole
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (cur + whole).coerceIn(0, max)
        if (target != cur) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
        toast = "音量 $target / $max"
    }

    // 首次进入：自动选源并直接开播
    LaunchedEffect(Unit) {
        val s = repo.activeSource()
        if (s == null) {
            if (isTv) {
                lobbyTab = LobbyTab.Sources
                panel = Panel.Lobby
            } else {
                lobbyTab = LobbyTab.Settings
                panel = Panel.Lobby
            }
            message = "还没有直播源：用另一台手机扫下面的二维码添加"
        } else {
            load(s.id, autoplay = true)
        }
    }

    // 打开侧栏时用 trigger 把焦点明确拉到「当前页」的导航项（第四点：光标停在当前项）。
    // 切页本身由导航项的 onFocusChanged 驱动，不在这里干预。
    var lobbyNavFocusTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(panel) {
        if (panel == Panel.Lobby) lobbyNavFocusTrigger++
    }

    // 呼出侧栏：菜单键 / 左键都走这里，默认落在频道页。
    fun openLobby(tab: LobbyTab = LobbyTab.Channels) {
        lobbyTab = tab
        panel = Panel.Lobby
    }

    /**
     * 遥控器按键。分两个世界：
     * - 侧栏打开：所有方向键 / OK 一律放行给 Compose 焦点系统。切页交给导航项的
     *   onFocusChanged（焦点到哪就切哪页），这是「按到信号源就出信号源页」的唯一真相源。
     *   之前按键桥也切一次页、两套机制互相打架，正是反复失灵的根因，这里彻底去掉。
     * - 播放态：上下换台、CH±换台、OK 暂停/继续、菜单键（和三横线键同为 KEYCODE_MENU）呼出侧栏。
     */
    SideEffect {
        keys.onKey = { key ->
            when {
                // 侧栏打开：方向键与 OK 全放行；菜单键再按一次关闭（开关语义）
                panel == Panel.Lobby -> when (key) {
                    KeyEvent.KEYCODE_MENU -> { panel = null; true }
                    // 返回键 = 关侧栏。以前这里返回 false，事件落到 Activity 就把整个 App 退了；
                    // 消费掉 ACTION_DOWN 后系统不会再走 onBackPressed（onKeyUp 需要 tracking，而 tracking
                    // 只在 onKeyDown 里才开始），所以这一个 true 就够。
                    KeyEvent.KEYCODE_BACK -> { panel = null; true }
                    else -> false
                }
                // 出错时把按键让给「重试 / 再试一次」按钮，否则按 OK 只会切暂停。
                // 但菜单键必须留个口子——错误提示里写着「到设置里把引擎切到 VLC」，
                // 不给开设置的话那句提示就成了废话。
                loadError != null || fatalError != null -> when (key) {
                    KeyEvent.KEYCODE_MENU -> { openLobby(LobbyTab.Settings); true }
                    // 出错界面的返回 = 回频道列表换台，不是退出 App
                    KeyEvent.KEYCODE_BACK -> { openLobby(LobbyTab.Channels); true }
                    else -> false
                }
                // 数字键换台：按 1 = 第 1 台；1、0 = 第 10 台。
                // 遥控器有两条数字键码（主键盘区与数字小键盘），两种都收。
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    pressDigit('0' + (key - KeyEvent.KEYCODE_0)); true
                }
                key in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    pressDigit('0' + (key - KeyEvent.KEYCODE_NUMPAD_0)); true
                }
                // 上/下键的换台方向可切换：不同遥控器、不同人的习惯不一样
                key == KeyEvent.KEYCODE_DPAD_UP -> {
                    step(if (keyUpNext) 1 else -1); true
                }
                key == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    step(if (keyUpNext) -1 else 1); true
                }
                // CH+ / CH- 不跟随上面的设置，物理语义就是「下一个 / 上一个」
                key == KeyEvent.KEYCODE_CHANNEL_UP -> {
                    step(1); true
                }
                key == KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    step(-1); true
                }
                // OK：正在输频道号就立即跳台，否则控制暂停/继续
                key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER -> {
                    if (digitBuf.isNotEmpty()) {
                        val text = digitBuf
                        digitBuf = ""
                        jumpToNumber(text)
                    } else {
                        togglePause()
                    }
                    true
                }
                // 菜单键 / 三横线键呼出侧栏（KEYCODE_MENU = 82，两种遥控器都发这个码）
                key == KeyEvent.KEYCODE_MENU -> {
                    openLobby(); true
                }
                // 左键也保留呼出，作为没有菜单键遥控器的备选
                key == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    openLobby(); true
                }
                // 正常播放时的返回：第一次只提示，2 秒内再按一次才真的退出。
                // 遥控器很容易误碰返回键，直接退 App 体验太差。
                key == KeyEvent.KEYCODE_BACK -> {
                    if (backArmed) {
                        backArmed = false
                        false
                    } else {
                        backArmed = true
                        toast = "再按一次返回键退出"
                        true
                    }
                }
                else -> false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { keys.onKey = null } }

    BackHandler(enabled = panel != null) { panel = null }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2500)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // media3 播放视图**常驻**：父容器与位置永不变化。
        // 历史上把同一个 PlayerView 挪到另一个父容器会抛
        // "The specified child already has a parent" 闪退，所以这里只切可见性、不换挂载点。
        // 用 INVISIBLE 而不是 alpha：SurfaceView 只有不可见时才真正让出显示层。
        AndroidView(
            factory = { playerView },
            modifier = Modifier.fillMaxSize(),
            update = { it.visibility = if (usingVlc) View.INVISIBLE else View.VISIBLE },
        )
        if (usingVlc) {
            // VLC 的画面视图按需进场/离场，不会和 media3 同时渲染
            AndroidView(
                factory = { vlcHolder.engine?.view ?: View(context) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (panel == null) {
            // 暂停角标：直播暂停停在同一帧，画面上必须给个明确提示，否则以为卡住了
            if (paused && current != null) {
                Text(
                    "⏸ 已暂停",
                    color = Color.White,
                    fontSize = if (isTv) 30.sp else 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(
                            end = if (isTv) 48.dp else 20.dp,
                            top = if (isTv) 32.dp else 18.dp,
                        )
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xCC1E293B))
                        .padding(horizontal = if (isTv) 24.dp else 16.dp, vertical = if (isTv) 12.dp else 8.dp),
                )
            }

            // 数字键输入提示：遥控器按了数字就地显示，1.6 秒后自动跳台
            if (digitBuf.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopStart)
                        .padding(
                            start = if (isTv) 48.dp else 20.dp,
                            top = if (isTv) 32.dp else 18.dp,
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC0B1220))
                        .padding(horizontal = if (isTv) 24.dp else 16.dp, vertical = if (isTv) 12.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "跳到",
                        color = TextDim,
                        fontSize = if (isTv) 18.sp else 14.sp,
                    )
                    Text(
                        digitBuf,
                        color = Accent,
                        fontSize = if (isTv) 34.sp else 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    Text(
                        "_",
                        color = Accent,
                        fontSize = if (isTv) 30.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 一层手势：轻点左半屏出频道、右半屏出设置；竖滑按起始位置分区（左换台/中亮度/右音量）
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectPlayerGestures(
                        onTap = { pos ->
                            when {
                                // 点屏幕任意位置都出同一套侧栏：左边落频道页，右边落设置页。
                                // 齿轮两步已取消——用户反馈多余，直接点更快。
                                pos.x < size.width / 2f -> {
                                    lobbyTab = LobbyTab.Channels
                                    panel = Panel.Lobby
                                }
                                else -> {
                                    lobbyTab = LobbyTab.Settings
                                    panel = Panel.Lobby
                                }
                            }
                        },
                        onDoubleTap = { togglePause() },
                        onDragStart = { _ ->
                            gestureAcc[0] = 0f
                            gestureAcc[1] = 0f
                        },
                        onDrag = { zone, dyFraction ->
                            when (zone) {
                                // 上滑（负）切下一个台，下滑切上一个；滑过约 1/7 屏高算一次
                                ZONE_CHANNEL -> {
                                    gestureAcc[0] += dyFraction
                                    if (abs(gestureAcc[0]) >= 0.14f) {
                                        step(if (gestureAcc[0] < 0f) 1 else -1)
                                        gestureAcc[0] = 0f
                                    }
                                }
                                ZONE_BRIGHT -> applyBrightness(-dyFraction * 1.2f)
                                else -> applyVolume(-dyFraction * 1.2f)
                            }
                        },
                        onDragEnd = {
                            gestureAcc[0] = 0f
                            gestureAcc[1] = 0f
                        },
                    )
                },
            )

            if (current != null) {
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = if (isTv) 48.dp else 20.dp, vertical = if (isTv) 32.dp else 16.dp),
                ) {
                    Text(
                        current?.name.orEmpty(),
                        color = Color.White,
                        fontSize = if (isTv) 26.sp else 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        current?.current.orEmpty().ifBlank { " " },
                        color = Color(0xFF86EFAC),
                        fontSize = if (isTv) 16.sp else 13.sp,
                    )
                    Text(
                        current?.next.orEmpty().let { if (it.isBlank()) "" else "接下来：$it" },
                        color = TextDim,
                        fontSize = if (isTv) 14.sp else 12.sp,
                    )
                }
                Text(
                    if (isTv) "菜单键 侧栏 · OK 暂停 · 上下键 换台"
                    else "双击 暂停 · 点左 频道 · 点右 设置 · 左滑换台 · 中亮度 右音量",
                    color = Color(0x99FFFFFF),
                    fontSize = if (isTv) 14.sp else 11.sp,
                    // 提示挪到左上：右上角留给齿轮，两者不能叠在一起
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 32.dp else 14.dp),
                )
            }

            val err = loadError
            if (err != null && current == null) {
                // 失败要摆到屏幕上，并给一个能点的重试——否则只剩「正在加载频道…」一句，
                // 用户无法判断是网络慢还是已经失败
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = if (isTv) 80.dp else 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "加载失败",
                        color = Color.White,
                        fontSize = if (isTv) 22.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        err,
                        color = Color(0xFFFCA5A5),
                        fontSize = if (isTv) 15.sp else 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "如果一直失败，多半是订阅地址不可达或盒子内存不足；"
                            + "可以先在另一台手机上加好源，再回到本机重试。",
                        color = TextDim,
                        fontSize = if (isTv) 13.sp else 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { activeId?.let { load(it, autoplay = true, force = true) } },
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        Text("重试", fontSize = if (isTv) 16.sp else 14.sp)
                    }
                }
            } else if (loading && current == null) {
                Text(
                    "正在加载频道…",
                    color = Color.White,
                    fontSize = if (isTv) 20.sp else 16.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 播放连续失败 3 次：不再静默重试，常驻屏幕告诉用户错误码和原因
            val fatal = fatalError
            if (fatal != null && panel == null && current != null && err == null) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = if (isTv) 80.dp else 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            fatal.code == "VLC_INIT_FAILED" -> "播放失败 · VLC 引擎没起来"
                            fatal.attempt > 0 -> "播放失败 · 已重试 ${fatal.attempt} 次"
                            else -> "播放失败"
                        },
                        color = Color(0xFFFCA5A5),
                        fontSize = if (isTv) 22.sp else 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // 带上 HTTP 码：403 和 404 的处置完全不同，用户看一眼就知道是该换源还是刷新
                        "${fatal.kind}问题：${fatal.codeText}",
                        color = Color.White,
                        fontSize = if (isTv) 16.sp else 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        fatal.message,
                        color = TextDim,
                        fontSize = if (isTv) 13.sp else 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        fatal.hint.ifBlank {
                            if (fatal.kind == "解码器") "到「设置」换一种解码方式（软解/硬解）再试。"
                            else "检查订阅地址是否可达，或换一个直播源。"
                        },
                        color = TextDim,
                        fontSize = if (isTv) 13.sp else 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                fatalError = null
                                current?.let { ch ->
                                    if (usingVlc) vlcHolder.engine?.play(ch) else controller.replay(ch)
                                }
                            },
                        ) {
                            Text("再试一次", fontSize = if (isTv) 16.sp else 14.sp)
                        }
                        // 出错时最常用的动作其实是「换个台」。没有这个按钮，
                        // 遥控器上只能靠返回键——而返回键以前是直接退 App。
                        TextButton(
                            onClick = { openLobby(LobbyTab.Channels) },
                            modifier = Modifier.padding(start = 6.dp),
                        ) {
                            Text("换台", fontSize = if (isTv) 16.sp else 14.sp)
                        }
                        // 一键换引擎：比让用户自己摸进设置里找「播放引擎」快得多，
                        // 也是这台机器上最可能真正解决问题的一步。
                        if (usingVlc) {
                            // VLC 也播不了时给个一键回头路。以前只能进设置里改，
                            // 而设置里改完又会被旧的脏记忆拽回 VLC，等于没有退路。
                            TextButton(
                                onClick = {
                                    val ch = current ?: return@TextButton
                                    fatalError = null
                                    // 用户明确选了 ExoPlayer，那就记住——下次 AUTO 别再把这个台
                                    // 送回 VLC。ExoPlayer 若仍播不了，AUTO 的兜底还会再切 VLC。
                                    activeId?.let { repo.prefs.rememberEngine(it, ch, PlayEngine.EXO) }
                                    toast = "换回 ExoPlayer 试播"
                                    startEngine(false, ch)
                                },
                                modifier = Modifier.padding(start = 10.dp),
                            ) {
                                Text("换回 ExoPlayer", fontSize = if (isTv) 16.sp else 14.sp)
                            }
                        } else if (playEngine != PlayEngine.VLC) {
                            TextButton(
                                onClick = {
                                    val ch = current ?: return@TextButton
                                    fatalError = null
                                    // 不预写引擎记忆：等 VLC 真出画面，onStarted 会记下来。
                                    // 这里只标「这一轮试过 VLC」，防止兜底再绕回来。
                                    vlcTriedThisPlay = true
                                    toast = "改用 VLC 引擎试播"
                                    startEngine(true, ch)
                                },
                                modifier = Modifier.padding(start = 10.dp),
                            ) {
                                Text("改用 VLC 试播", fontSize = if (isTv) 16.sp else 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // 三栏侧栏：频道 / 信号源 / 设置 + 分类 + 频道列表。手机与电视共用同一套界面。
        if (panel == Panel.Lobby) {
            LobbyPanel(
                isTv = isTv,
                tab = lobbyTab,
                onTab = { lobbyTab = it },
                navFocusTrigger = lobbyNavFocusTrigger,
                blocks = channelBlocks,
                activeId = activeId,
                currentId = current?.id,
                onPick = { block, ch ->
                    if (block.source.id != activeId) {
                        if (lockedId != null && lockedId != block.source.id) {
                            val lockName = sources.firstOrNull { it.id == lockedId }?.name ?: "其他源"
                            toast = "已锁定「$lockName」，先去设置解锁"
                        } else {
                            result = block.result
                            activeId = block.source.id
                            playChannel(ch, block.source.id)
                            panel = null
                        }
                    } else {
                        playChannel(ch, activeId)
                        panel = null
                    }
                },
                // 大厅里切源不关面板：留在原地继续挑台，比"关掉再点开"顺手
                onSwitchSource = { s ->
                    when {
                        lockedId != null && lockedId != s.id -> {
                            val lockName = sources.firstOrNull { it.id == lockedId }?.name ?: "其他源"
                            toast = "已锁定「$lockName」，先解锁再切"
                        }
                        s.id != activeId -> {
                            current = null
                            result = null
                            load(s.id, autoplay = true)
                        }
                    }
                },
                sources = sources,
                lockedId = lockedId,
                pairInfo = pairInfo,
                pending = pending,
                message = message,
                current = current,
                onLock = { toggleLock(it) },
                onEdit = { editing = it },
                onSaveDraft = { saveDraft(it) },
                onDropDraft = { d ->
                    pending.remove(d)
                    message = "已丢弃「${d.name}」"
                },
                onAddManual = { n, u, e ->
                    val added = repo.add(n, u, e)
                    if (added == null) {
                        "地址不合法，仅支持 http / https"
                    } else {
                        message = "已保存「${added.name}」"
                        toast = "已添加：${added.name}"
                        // 当前没有在播的台时，新源直接顶上去开播
                        if (activeId == null || current == null) load(added.id, autoplay = true)
                        null
                    }
                },
                // 0.5.7：这个按钮从「只刷当前源」改成「串行刷新所有源」，不打断正在播的台
                onRefresh = { refreshAllSources() },
                // 0.5.8：只刷节目单，不重下订阅
                onRefreshEpg = { refreshAllEpg() },
                onRefreshSource = { s -> refreshSource(s) },
                decoder = decoderName,
                errorCode = errorCode,
                mediaInfo = mediaInfo,
                vlcDiag = vlcDiag,
                decodeMode = decodeMode,
                onDecodeMode = { setDecodeMode(it) },
                playEngine = playEngine,
                onPlayEngine = { setPlayEngine(it) },
                keyUpNext = keyUpNext,
                onKeyUpNext = { v ->
                    keyUpNext = v
                    repo.prefs.keyUpNext = v
                    toast = if (v) "上键 = 下一个台" else "上键 = 上一个台"
                },
                updateUrl = repo.prefs.updateUrl,
                onUpdateUrl = { repo.prefs.updateUrl = it },
                onClose = { panel = null },
            )
        }

        editing?.let { src ->
            EditSourceDialog(
                source = src,
                isTv = isTv,
                onSave = { n, u, e ->
                    val updated = repo.update(src.id, n, u, e)
                    editing = null
                    if (updated == null) {
                        message = "地址不合法，仅支持 http / https"
                        toast = "地址不合法，没保存"
                    } else {
                        toast = "已更新：${updated.name}"
                        message = "已更新「${updated.name}」"
                        // 改的可能就是当前在用的源：按新地址重拉一次
                        if (src.id == activeId) load(src.id, autoplay = true)
                    }
                },
                onDelete = {
                    repo.remove(src.id)
                    editing = null
                    toast = "已删除：${src.name}"
                    if (src.id == activeId) {
                        activeId = null
                        current = null
                        result = null
                        val next = repo.activeSource()
                        if (next != null) {
                            load(next.id, autoplay = true)
                        } else {
                            lobbyTab = LobbyTab.Settings
                            panel = Panel.Lobby
                        }
                    }
                },
                onDismiss = { editing = null },
            )
        }

        // 换台提示：左下角浮一下，3 秒收起。序号 + 台标 + 当前节目都在里面
        osd?.let { o ->
            if (panel == null) {
                ChannelOsdCard(
                    osd = o,
                    logo = osdLogo?.takeIf { it.first == o.channel.id }?.second,
                    errorCode = errorCode,
                    videoInfo = mediaInfo,
                    engineName = if (usingVlc) "VLC" else "ExoPlayer",
                    isTv = isTv,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(
                            start = if (isTv) 44.dp else 14.dp,
                            bottom = if (isTv) 44.dp else 20.dp,
                        ),
                )
            }
        }

        // 底部节目进度条（0.5.6 加，0.5.7 改为跟 OSD 一起 3 秒隐藏）：
        // 用户嫌它常驻挡画面，现在只在换台那 3.2 秒里随 OSD 一起出现、一起收起。
        // 直播没有传统时间轴，这条只表示「这一档节目播了多少、还剩多久」。
        if (panel == null && current != null && fatalError == null && osd != null) {
            ProgramProgressBar(
                channel = current!!,
                isTv = isTv,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (isTv) 12.dp else 8.dp),
            )
        }

        toast?.let { t ->
            Text(
                t,
                color = Color.White,
                fontSize = if (isTv) 18.sp else 14.sp,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = if (isTv) 40.dp else 24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xCC1E293B))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * 换台提示卡片：序号 + 台标 + 频道名 + 当前/下一节目。
 *
 * 电视端原来换完台屏幕上没有任何反馈，看不出切到了第几台、也没有台标。
 * 没有台标时把序号画进方块里，保证「第几台」永远读得到。
 */
@Composable
private fun ChannelOsdCard(
    osd: ChannelOsd,
    logo: Bitmap?,
    errorCode: String,
    videoInfo: String,
    engineName: String,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    val ch = osd.channel
    val textMax = if (isTv) 380.dp else 200.dp
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE60B1220))
            .padding(horizontal = if (isTv) 18.dp else 12.dp, vertical = if (isTv) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (isTv) 72.dp else 50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x3316223A)),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                Image(
                    bitmap = logo.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(5.dp),
                )
            } else {
                Text(
                    if (osd.index > 0) osd.index.toString() else "—",
                    color = Accent,
                    fontSize = if (isTv) 24.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(Modifier.size(if (isTv) 16.dp else 10.dp, 1.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 有台标时序号挪到台名前面，不浪费那块方块
                if (osd.index > 0 && logo != null) {
                    Text(
                        osd.index.toString(),
                        color = Accent,
                        fontSize = if (isTv) 20.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(Modifier.size(if (isTv) 10.dp else 6.dp, 1.dp))
                }
                Text(
                    ch.name,
                    color = Color.White,
                    fontSize = if (isTv) 21.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = textMax),
                )
            }
            Text(
                ch.current.ifBlank { "暂无节目信息" },
                color = if (ch.current.isBlank()) TextDim else Color(0xFF86EFAC),
                fontSize = if (isTv) 14.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = textMax),
            )
            if (ch.next.isNotBlank()) {
                Text(
                    "接下来：${ch.next}",
                    color = TextDim,
                    fontSize = if (isTv) 12.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = textMax),
                )
            }
            // 分辨率跟着 OSD 一起闪 3 秒：ExoPlayer 与 VLC 都上报，换台后内部自动更新。
            // 只要分辨率行，不把整段诊断文字塞进 OSD——那会挡住画面。
            if (videoInfo.isNotBlank()) {
                Text(
                    "$videoInfo · $engineName",
                    color = Accent,
                    fontSize = if (isTv) 12.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = textMax),
                )
            }
            // 最近一次错误码直接摆到换台提示上：下次卡住无需连 adb 就知道是 IO 还是解码器
            if (errorCode.isNotBlank()) {
                Text(
                    "错误：$errorCode",
                    color = Color(0xFFFCA5A5),
                    fontSize = if (isTv) 12.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = textMax),
                )
            }
        }
    }
}

/**
 * 底部节目进度条：显示当前档节目播了多少、还剩多久（0.5.6）。
 *
 * 直播流没有起点终点，做不了传统可拖动时间轴。这里用 EPG 的 currentStart/currentStop
 * 算「这一档节目的进度」，每秒推进一次。没有 EPG 的源明确写「无节目单」，不摆一条空条
 * 让人误以为坏了。整个条半透明、贴底居中，不挡画面。
 */
@Composable
private fun ProgramProgressBar(
    channel: Channel,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    val start = channel.currentStart
    val stop = channel.currentStop
    val hasEpg = start > 0 && stop > start
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start, stop) {
        // 有 EPG 时每秒推进；没有就只跑一次，后面 pure text 不需要刷新
        while (hasEpg) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val progress: Float
    val caption: String
    if (hasEpg) {
        progress = ((now - start).toFloat() / (stop - start).toFloat()).coerceIn(0f, 1f)
        val leftMin = ((stop - now) / 60_000).coerceAtLeast(0)
        val elapsedMin = ((now - start) / 60_000).coerceAtLeast(0)
        caption = buildString {
            append(timeFmt.format(start)).append("–").append(timeFmt.format(stop))
            append("　已播 ").append(elapsedMin).append(" 分")
            if (leftMin > 0) append(" · 剩 ").append(leftMin).append(" 分")
        }
    } else {
        progress = 0f
        caption = "无节目单（该源未提供 EPG）"
    }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB30B1220))
            .padding(horizontal = if (isTv) 18.dp else 12.dp, vertical = if (isTv) 8.dp else 6.dp)
            .widthIn(min = if (isTv) 360.dp else 220.dp, max = if (isTv) 620.dp else 380.dp),
    ) {
        Text(
            caption,
            color = if (hasEpg) Color(0xFFE2E8F0) else TextDim,
            fontSize = if (isTv) 13.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.fillMaxWidth()
                .padding(top = 6.dp)
                .height(if (isTv) 5.dp else 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            if (hasEpg) {
                Box(
                    Modifier.fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Accent),
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    isTv: Boolean,
    sources: List<Source>,
    activeId: String?,
    lockedId: String?,
    pairInfo: PairInfo?,
    pending: List<DraftSource>,
    message: String,
    current: Channel?,
    onSwitch: (Source) -> Unit,
    onLock: (Source) -> Unit,
    onEdit: (Source) -> Unit,
    onSaveDraft: (DraftSource) -> Unit,
    onDropDraft: (DraftSource) -> Unit,
    onRefresh: () -> Unit,
    onRefreshSource: (Source) -> Unit,
    onChannels: () -> Unit,
    onClose: () -> Unit,
    decoder: String,
    decodeMode: DecodeMode,
    onDecodeMode: (DecodeMode) -> Unit,
    keyUpNext: Boolean,
    onKeyUpNext: (Boolean) -> Unit,
    updateUrl: String,
    onUpdateUrl: (String) -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    // 遥控器场景下必须真的把焦点落到面板里，否则方向键点不动；节点可能尚未挂载，重试几次
    LaunchedEffect(Unit) {
        repeat(4) {
            if (runCatching { closeFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(60)
        }
    }
    val context = LocalContext.current
    val crashLog = remember { Diag.lastCrash(context) }
    val deviceInfo = remember { Diag.deviceInfo(context) }
    var showCrash by remember { mutableStateOf(false) }
    val qr = remember(pairInfo?.url) { pairInfo?.let { QrEncoder.bitmap(it.url, 360) } }
    val titleSize = if (isTv) 24.sp else 19.sp
    val bodySize = if (isTv) 15.sp else 13.sp

    Box(
        Modifier.fillMaxSize().background(Color(0xF00B1220))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(if (isTv) 0.76f else 0.94f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                // 面板内部点击不触发「点空白关闭」
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = if (isTv) 26.dp else 18.dp, vertical = if (isTv) 20.dp else 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置", color = Color.White, fontSize = titleSize, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) {
                    Text("关闭", fontSize = bodySize)
                }
            }
            Text(
                "现在：${current?.name ?: "未播放"}　·　源：${sources.firstOrNull { it.id == activeId }?.name ?: "无"}",
                color = TextDim, fontSize = bodySize,
            )
            // 把系统版本与内存摆出来：老盒子问题排查全靠这两行
            Text(
                deviceInfo,
                color = Color(0xFF64748B),
                fontSize = if (isTv) 11.sp else 9.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // 解码方式：软解是「画面一顿一顿」的头号原因，摆出来一眼可辨
            if (decoder.isNotBlank()) {
                val soft = decoder.startsWith("c2.android.") || decoder.startsWith("OMX.google.")
                Text(
                    (if (soft) "解码：$decoder（软解，会掉帧）" else "解码：$decoder（硬解）"),
                    color = if (soft) Color(0xFFF87171) else Color(0xFF4ADE80),
                    fontSize = if (isTv) 11.sp else 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 解码方式三选：有的盒子硬解有 bug（花屏、音画不同步），得能手动切软解；
            // 更多时候是要反过来锁死硬解，避免固件排序不当导致 1080p 走软解掉帧。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "解码方式",
                    color = TextDim,
                    fontSize = bodySize,
                    modifier = Modifier.padding(end = 10.dp),
                )
                DecodeMode.entries.forEach { m ->
                    ModeChip(
                        label = m.label,
                        selected = m == decodeMode,
                        isTv = isTv,
                        onClick = { onDecodeMode(m) },
                    )
                }
            }
            // 上下键方向：遥控器不同、手感不同，做成一键切换
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "换台键",
                    color = TextDim,
                    fontSize = bodySize,
                    modifier = Modifier.padding(end = 10.dp),
                )
                ModeChip(
                    label = "上=下一台",
                    selected = keyUpNext,
                    isTv = isTv,
                    onClick = { onKeyUpNext(true) },
                )
                ModeChip(
                    label = "上=上一台",
                    selected = !keyUpNext,
                    isTv = isTv,
                    onClick = { onKeyUpNext(false) },
                )
            }
            VSpace(8)

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SectionTitle("切换直播源", isTv) }
                items(sources, key = { "src-" + it.id }) { s ->
                    SourceRow(
                        isTv = isTv,
                        source = s,
                        isActive = s.id == activeId,
                        isLocked = s.id == lockedId,
                        onSwitch = { onSwitch(s) },
                        onLock = { onLock(s) },
                        onEdit = { onEdit(s) },
                        onRefresh = { onRefreshSource(s) },
                    )
                }
                if (sources.isEmpty()) {
                    item { Text("还没有源，用下面的二维码添加", color = TextDim, fontSize = bodySize) }
                }

                item { SectionTitle("添加直播源（扫码）", isTv) }
                item {
                    Text(
                        "用另一台手机的相机（或微信扫一扫）扫下面的二维码，在打开的网页里填订阅地址，点确定后就会出现在这里，再点保存即可。",
                        color = TextDim, fontSize = bodySize,
                    )
                }
                item {
                    val info = pairInfo
                    if (qr != null && info != null) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "添加源二维码",
                                modifier = Modifier
                                    .size(if (isTv) 190.dp else 132.dp)
                                    .background(Color.White)
                                    .padding(6.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text("配对码 ${info.code}", color = Color.White, fontSize = titleSize)
                                Text(
                                    info.url, color = TextDim,
                                    fontSize = if (isTv) 13.sp else 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "手机与本机需在同一局域网；设置关闭后二维码失效，重开会换新码。",
                                    color = TextDim, fontSize = if (isTv) 12.sp else 11.sp,
                                )
                            }
                        }
                    } else {
                        Text("正在准备二维码…", color = TextDim, fontSize = bodySize)
                    }
                }

                if (pending.isNotEmpty()) {
                    item { SectionTitle("待保存（${pending.size}）", isTv) }
                    items(pending) { d ->
                        DraftRow(isTv, d, { onSaveDraft(d) }, { onDropDraft(d) })
                    }
                }

                item { VSpace(4) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onChannels) { Text("频道列表", fontSize = bodySize) }
                        TextButton(onClick = onRefresh) { Text("刷新当前源", fontSize = bodySize) }
                    }
                }
                if (message.isNotBlank()) {
                    item { Text(message, color = Accent, fontSize = if (isTv) 13.sp else 11.sp) }
                }
                if (crashLog != null) {
                    item {
                        TextButton(onClick = { showCrash = true }) {
                            Text(
                                "上次崩溃记录（点开看设备与堆栈）",
                                color = Color(0xFFFCA5A5),
                                fontSize = if (isTv) 12.sp else 10.sp,
                            )
                        }
                    }
                }
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0B1526))
                            .padding(12.dp),
                    ) {
                        UpdateSection(isTv = isTv, url = updateUrl, onUrlChange = onUpdateUrl)
                    }
                }
            }
        }
    }

    if (showCrash && crashLog != null) {
        AlertDialog(
            onDismissRequest = { showCrash = false },
            title = { Text("上次崩溃记录", fontSize = if (isTv) 18.sp else 15.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(crashLog, fontSize = if (isTv) 11.sp else 9.sp, color = TextDim)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Diag.clear(context)
                    showCrash = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showCrash = false }) { Text("关闭") }
            },
        )
    }
}

/** 频道列表里的一段：一个源 + 它的频道（可能还没缓存过） */
internal data class ChannelSourceBlock(
    val source: Source,
    val result: RefreshResult?,
    val isActive: Boolean,
)

/**
 * 频道列表：贴着左边的一条半透明抽屉，宽约屏幕 1/4，高满屏。
 * 不做整屏遮罩——列表在旁边滚，右边画面还看得见，切台时心里有数。
 *
 * 多源时按源分段**全部列出**（观屿、酷九这类空壳播放器都是这个路子）：
 * 不必先切源再找台，直接在列表里点任何源的任何台，点完自动切到那个源。
 */
@Composable
private fun ChannelPanel(
    isTv: Boolean,
    blocks: List<ChannelSourceBlock>,
    currentId: String?,
    onPick: (ChannelSourceBlock, Channel) -> Unit,
    onBack: () -> Unit,
) {
    val firstBlock = blocks.firstOrNull { it.result?.channels?.isNotEmpty() == true }
    val firstFocus = remember { FocusRequester() }
    val firstSourceId = firstBlock?.source?.id
    val firstId = firstBlock?.result?.channels?.firstOrNull()?.id
    // 首个频道拿过焦点后就不再重试，避免每次数据变化都抢一次焦点
    LaunchedEffect(firstSourceId, firstId) {
        repeat(4) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(60)
        }
    }
    val bodySize = if (isTv) 15.sp else 13.sp
    val total = blocks.sumOf { it.result?.channels?.size ?: 0 }
    // 频道序号：台多的时候靠数字定位比扫名字快，跨源统一连续编号
    val order = remember(blocks) {
        val map = HashMap<String, Int>(1024)
        var n = 0
        blocks.forEach { b ->
            b.result?.channels?.forEach { ch ->
                n++
                map[b.source.id + "|" + ch.id] = n
            }
        }
        map
    }

    Box(Modifier.fillMaxSize()) {
        // 抽屉右侧的透明区域：点一下收起，不发暗也不挡画面
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onBack() } },
        )
        Column(
            Modifier.fillMaxHeight()
                // 用户要的「屏幕 1/4」：多源后要放得下源名，手机略放宽一点
                .fillMaxWidth(if (isTv) 0.30f else 0.38f)
                .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                .background(Color(0xD60B1220)) // 84% 不透明，透出一点画面
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = if (isTv) 16.dp else 10.dp, end = if (isTv) 12.dp else 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = if (isTv) 14.dp else 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "频道 $total",
                    color = Color.White,
                    fontSize = if (isTv) 20.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text("收起", fontSize = bodySize) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                blocks.forEach { b ->
                    item(key = "src-" + b.source.id) { SourceHeader(b, isTv) }
                    val r = b.result
                    if (r == null) {
                        item(key = "none-" + b.source.id) {
                            Text(
                                if (b.isActive) "正在加载…" else "未缓存 · 到设置里点「刷新当前源」",
                                color = TextDim,
                                fontSize = if (isTv) 12.sp else 10.sp,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                            )
                        }
                    } else {
                        r.groups.forEach { g ->
                            item(key = "g-" + b.source.id + "-" + g.name) {
                                SectionTitle(g.name, isTv)
                            }
                            items(
                                g.channels,
                                key = { b.source.id + "|" + g.name + "|" + it.id },
                            ) { ch ->
                                ChannelRow(
                                    isTv = isTv,
                                    number = order[b.source.id + "|" + ch.id] ?: 0,
                                    channel = ch,
                                    playing = b.isActive && ch.id == currentId,
                                    modifier = if (b.source.id == firstSourceId && ch.id == firstId) {
                                        Modifier.focusRequester(firstFocus)
                                    } else {
                                        Modifier
                                    },
                                    onClick = { onPick(b, ch) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 每个源的分段标题：源名 + 台数，当前源用亮色标出来。 */
@Composable
private fun SourceHeader(block: ChannelSourceBlock, isTv: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (isTv) 14.dp else 9.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            block.source.name,
            color = if (block.isActive) Accent else Color.White,
            fontSize = if (isTv) 15.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            "  ${block.result?.channels?.size ?: 0}",
            color = TextDim,
            fontSize = if (isTv) 12.sp else 10.sp,
        )
        if (block.isActive) {
            Text(
                "  当前",
                color = Accent,
                fontSize = if (isTv) 11.sp else 9.sp,
            )
        }
    }
}

/** 八齿齿轮：齿是旋转的圆角矩形，盘面中心挖一个圆孔。 */
internal fun DrawScope.drawGear(color: Color, holeColor: Color) {
    val c = center
    val r = size.minDimension / 2f
    val teeth = 8
    repeat(teeth) { i ->
        rotate(degrees = i * 360f / teeth) {
            drawRoundRect(
                color = color,
                topLeft = Offset(c.x - r * 0.15f, c.y - r),
                size = Size(r * 0.30f, r * 0.44f),
                cornerRadius = CornerRadius(r * 0.10f, r * 0.10f),
            )
        }
    }
    drawCircle(color = color, radius = r * 0.62f)
    drawCircle(color = holeColor, radius = r * 0.24f)
}

/**
 * 解码方式的胶囊按钮。
 * 用 focusable + onFocusChanged 手写焦点态，而不是只用 clickable：
 * 电视端靠方向键移动焦点，没有可聚焦节点就永远选不中。
 */
@Composable
internal fun ModeChip(label: String, selected: Boolean, isTv: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        color = when {
            selected -> Color(0xFF0B1220)
            focused -> Color.White
            else -> TextDim
        },
        fontSize = if (isTv) 14.sp else 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(shape)
            .background(
                when {
                    selected -> Accent
                    focused -> RowFocus
                    else -> Color(0x3316223A)
                },
            )
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = if (isTv) 16.dp else 12.dp, vertical = if (isTv) 8.dp else 6.dp),
    )
}

@Composable
internal fun SectionTitle(text: String, isTv: Boolean) {
    Text(
        text,
        color = Accent,
        fontSize = if (isTv) 15.sp else 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
internal fun SourceRow(
    isTv: Boolean,
    tvFocusable: Boolean = false,
    source: Source,
    isActive: Boolean,
    isLocked: Boolean,
    focusRequester: FocusRequester? = null,
    onSwitch: () -> Unit,
    onLock: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
) {
    // 四个控件各自可聚焦：源名 / 刷新 / 编辑 / 锁定。
    // 遥控器左右键在它们之间自然移动，确认键执行——不靠任何手动 requestFocus 跳转，
    // 那种做法在真机上焦点一乱就按不到按钮。
    var nameFocused by remember { mutableStateOf(false) }
    var refreshFocused by remember { mutableStateOf(false) }
    var editFocused by remember { mutableStateOf(false) }
    var lockFocused by remember { mutableStateOf(false) }
    val anyFocused = nameFocused || refreshFocused || editFocused || lockFocused
    val bg = when {
        anyFocused -> RowFocus
        isActive -> RowActive
        else -> RowBg
    }
    val rowShape = RoundedCornerShape(10.dp)
    val chipShape = RoundedCornerShape(6.dp)
    val focusMod = if (tvFocusable) Modifier.focusable() else Modifier
    Row(
        Modifier.fillMaxWidth()
            .clip(rowShape)
            .background(bg)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 控件 1：源名（点=切换）
        Column(
            Modifier.weight(1f)
                .clip(chipShape)
                .onFocusChanged { nameFocused = it.isFocused }
                .focusRing(nameFocused && tvFocusable, chipShape)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(focusMod)
                .clickable { onSwitch() }
                .padding(4.dp),
        ) {
            Text(
                source.name,
                color = if (nameFocused) Color.White else Color.White,
                fontSize = if (isTv) 16.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val bits = buildList {
                if (isActive) add("正在使用")
                if (isLocked) add("已锁定")
                if (source.channelCount > 0) add("${source.channelCount} 个频道")
            }
            Text(
                if (bits.isEmpty()) "点击切换" else bits.joinToString(" · "),
                color = if (isLocked) Color(0xFFFCD34D) else TextDim,
                fontSize = if (isTv) 12.sp else 11.sp,
            )
        }
        Spacer(Modifier.width(6.dp))
        // 控件 2：刷新（0.5.7）——只刷这一条源，不切源、不打断正在播的台
        Box(
            Modifier.clip(chipShape)
                .background(if (refreshFocused) Color(0x33FFFFFF) else Color.Transparent)
                .focusRing(refreshFocused && tvFocusable, chipShape)
                .onFocusChanged { refreshFocused = it.isFocused }
                .then(focusMod)
                .clickable { onRefresh() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) { Text("刷新", color = Color(0xFF86EFAC), fontSize = if (isTv) 13.sp else 12.sp) }
        Spacer(Modifier.width(4.dp))
        // 控件 3：编辑
        Box(
            Modifier.clip(chipShape)
                .background(if (editFocused) Color(0x33FFFFFF) else Color.Transparent)
                .focusRing(editFocused && tvFocusable, chipShape)
                .onFocusChanged { editFocused = it.isFocused }
                .then(focusMod)
                .clickable { onEdit() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Text("编辑", color = Color.White, fontSize = if (isTv) 13.sp else 12.sp) }
        Spacer(Modifier.width(4.dp))
        // 控件 3：锁定 / 解锁
        Box(
            Modifier.clip(chipShape)
                .background(if (lockFocused) Color(0x33FFFFFF) else Color.Transparent)
                .focusRing(lockFocused && tvFocusable, chipShape)
                .onFocusChanged { lockFocused = it.isFocused }
                .then(focusMod)
                .clickable { onLock() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                if (isLocked) "🔒 解锁" else "锁定",
                color = if (isLocked) Color(0xFFFCD34D) else Color.White,
                fontSize = if (isTv) 13.sp else 12.sp,
            )
        }
    }
}

/** 编辑源：名称 / 订阅地址 / 节目单地址，另带删除（两次确认，防误删）。 */
@Composable
private fun EditSourceDialog(
    source: Source,
    isTv: Boolean,
    onSave: (name: String, url: String, epg: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var url by remember(source.id) { mutableStateOf(source.url) }
    var epg by remember(source.id) { mutableStateOf(source.epgUrl) }
    var confirmDelete by remember(source.id) { mutableStateOf(false) }
    val bodySize = if (isTv) 15.sp else 13.sp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑直播源", fontSize = if (isTv) 20.sp else 17.sp) },
        text = {
            // 手机横屏高度有限，三个输入框必须能滚，否则键盘一弹就顶出屏幕
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("M3U 订阅地址") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = epg, onValueChange = { epg = it },
                    label = { Text("节目单地址（留空自动发现）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(
                        if (confirmDelete) "再按一次彻底删除" else "删除这个源",
                        color = Color(0xFFF87171),
                        fontSize = bodySize,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, url, epg) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun DraftRow(isTv: Boolean, draft: DraftSource, onSave: () -> Unit, onDrop: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) RowFocus else Color(0xFF1F2E1A))
            .onFocusChanged { focused = it.isFocused }
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                draft.name, color = Color.White,
                fontSize = if (isTv) 15.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                draft.url, color = TextDim,
                fontSize = if (isTv) 12.sp else 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSave) { Text("保存", fontSize = if (isTv) 13.sp else 12.sp) }
        TextButton(onClick = onDrop) { Text("丢弃", fontSize = if (isTv) 13.sp else 12.sp) }
    }
}

@Composable
private fun ChannelRow(
    isTv: Boolean,
    number: Int,
    channel: Channel,
    playing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> RowFocus
                    playing -> ChRowActive
                    else -> ChRowBg
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = if (isTv) 12.dp else 9.dp, vertical = if (isTv) 9.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号：电视上离开沙发看不清小字，数字比名字更好认
        Text(
            if (number > 0) number.toString() else "·",
            color = if (playing) Accent else TextDim,
            fontSize = if (isTv) 15.sp else 12.sp,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.widthIn(min = if (isTv) 34.dp else 24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                (if (playing) "▶ " else "") + channel.name,
                color = Color.White,
                fontSize = if (isTv) 16.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                channel.current.ifBlank { "—" },
                color = if (channel.current.isBlank()) TextDim else Color(0xFF86EFAC),
                fontSize = if (isTv) 12.sp else 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 电视端多显示一行下一档节目，节目单信息更完整
            if (isTv && channel.next.isNotBlank()) {
                Text(
                    "接下来：${channel.next}",
                    color = TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun VSpace(dp: Int) {
    Box(Modifier.size(0.dp, dp.dp))
}

// ── 手势 ─────────────────────────────────────────────────────────────
private const val ZONE_CHANNEL = 0
private const val ZONE_BRIGHT = 1
private const val ZONE_VOLUME = 2

/**
 * 一层手势搞定「点」和「竖滑」，刻意不复用 detectTapGestures + detectVerticalDragGestures：
 * 两者串在同一个 Modifier 上时，滑完松手会被点击检测同时判成 tap，
 * 调完音量就莫名弹出设置面板。这里自己按 down 位置与位移判定，互斥干净。
 *
 * 分区按**按下时**的 x 决定，滑动过程中不切换区域，避免手指飘到别处改错东西。
 */
private suspend fun PointerInputScope.detectPlayerGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: () -> Unit,
    onDragStart: (Int) -> Unit,
    onDrag: (zone: Int, dyFraction: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val doubleTapMs = 280L
    var lastTapAt = -doubleTapMs
    var pendingTap: kotlinx.coroutines.Job? = null
    coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val zone = when {
            down.position.x < size.width / 3f -> ZONE_CHANNEL
            down.position.x < size.width * 2f / 3f -> ZONE_BRIGHT
            else -> ZONE_VOLUME
        }
        var slopAcc = 0f
        var dragging = false
        var isTap = false
        val tapPos = down.position
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) break
            if (!change.pressed) {
                isTap = !dragging
                break
            }
            val dy = change.positionChange().y
            if (!dragging) {
                slopAcc += dy
                if (abs(slopAcc) > viewConfiguration.touchSlop) {
                    dragging = true
                    onDragStart(zone)
                    change.consume()
                }
            } else {
                onDrag(zone, dy / size.height.toFloat())
                change.consume()
            }
        }
        if (dragging) {
            onDragEnd()
        } else if (isTap) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTapAt <= doubleTapMs) {
                // 第二击：取消排队中的单击，执行双击（暂停/继续）
                pendingTap?.cancel()
                pendingTap = null
                lastTapAt = -doubleTapMs
                onDoubleTap()
            } else {
                // 第一击：延迟执行单击，给第二击留判定窗口；拖动时已排除，不会误触
                lastTapAt = now
                val pos = tapPos
                pendingTap = launch {
                    delay(doubleTapMs)
                    onTap(pos)
                }
            }
        }
    }
    }
}

/** Compose 里的 Context 常常被包了几层，逐层拆到 Activity 为止。 */
private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
