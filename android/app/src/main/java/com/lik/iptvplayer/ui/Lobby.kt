package com.lik.iptvplayer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lik.iptvplayer.core.Channel
import com.lik.iptvplayer.core.DecodeMode
import com.lik.iptvplayer.core.Diag
import com.lik.iptvplayer.core.LogoLoader
import com.lik.iptvplayer.core.PairInfo
import com.lik.iptvplayer.core.PlayEngine
import com.lik.iptvplayer.core.QrEncoder
import com.lik.iptvplayer.core.Source
import kotlinx.coroutines.delay

/** 大厅左侧的一级导航。按用户给的参考图，只保留这三项。 */
internal enum class LobbyTab(val label: String) {
    Channels("频道"),
    Sources("信号源"),
    Settings("设置"),
}

// 参考图里的配色：选中项是浅米色块 + 深色字，其余是浅灰字
private val NavSelectedBg = Color(0xFFEDE7DC)
private val NavSelectedFg = Color(0xFF16181D)
private val NavFocusBg = Color(0xFF2563EB)
private val LobbyBgTop = Color(0xFF0B1526)
private val LobbyBgBottom = Color(0xFF05080F)
private val CatSelected = Color(0xFFF1F5F9)

/**
 * 电视大厅：左侧导航（频道 / 信号源 / 设置）+ 分类栏 + 频道列表。
 *
 * 结构照抄市面上空壳播放器（观屿、酷九）的三栏布局，
 * 但只留用户要的三项，不做搜索和收藏——多两项就要多一层焦点跳转，
 * 遥控器上每多一次左右键都是成本。
 *
 * 手机端不走这里：手机是全屏播放 + 半屏抽屉，那套交互比大厅快。
 */
@Composable
internal fun LobbyPanel(
    isTv: Boolean,
    tab: LobbyTab,
    onTab: (LobbyTab) -> Unit,
    navFocusTrigger: Int = 0,
    blocks: List<ChannelSourceBlock>,
    activeId: String?,
    currentId: String?,
    onPick: (ChannelSourceBlock, Channel) -> Unit,
    onSwitchSource: (Source) -> Unit,
    sources: List<Source>,
    lockedId: String?,
    pairInfo: PairInfo?,
    pending: List<DraftSource>,
    message: String,
    current: Channel?,
    onLock: (Source) -> Unit,
    onEdit: (Source) -> Unit,
    onSaveDraft: (DraftSource) -> Unit,
    onDropDraft: (DraftSource) -> Unit,
    onAddManual: (String, String, String) -> String?,
    onRefresh: () -> Unit,
    onRefreshEpg: () -> Unit,
    onRefreshSource: (Source) -> Unit,
    decoder: String,
    errorCode: String = "",
    mediaInfo: String = "",
    vlcDiag: String = "",
    decodeMode: DecodeMode,
    onDecodeMode: (DecodeMode) -> Unit,
    playEngine: PlayEngine,
    onPlayEngine: (PlayEngine) -> Unit,
    keyUpNext: Boolean,
    onKeyUpNext: (Boolean) -> Unit,
    updateUrl: String,
    onUpdateUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    val navFocus = remember { LobbyTab.entries.map { FocusRequester() }.toList() }
    // 只在「侧栏刚打开」时把焦点落到当前页导航项，让方向键一进来就有落点。
    //
    // 关键：切页之后不再用 LaunchedEffect(tab) 把焦点拉回导航项。此前那套
    // 「焦点到导航项→切页→tab 变化→又把焦点拉回导航项」是双向驱动，真机上会
    // 抢焦点：用户从频道按到信号源，焦点被Effect拽回，页面就不跟着换了。
    // 现在切页只有「导航项 onFocusChanged」一个真相源，焦点到哪就显示哪页，
    // 用户往下按到信号源，焦点停在那，页面必然跟着切。
    LaunchedEffect(isTv, navFocusTrigger) {
        if (!isTv) return@LaunchedEffect
        runCatching { navFocus[tab.ordinal].requestFocus() }
    }

    // 参考图的形态是「侧边浮层」，不是独占全屏：
    // 左侧一条半透明面板（导航 + 分类 + 频道），右边留出三成屏幕看正在播的画面。
    Box(
        Modifier.fillMaxSize()
            .background(Color(0x59040A14))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Row(
            Modifier.fillMaxHeight().fillMaxWidth(if (isTv) 0.72f else 0.88f)
                .background(Brush.horizontalGradient(listOf(Color(0xF2081020), Color(0xDC081020))))
                // 吞掉面板内的点击，避免点到列表就把整个侧栏关掉
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            NavRail(
                isTv = isTv,
                current = tab,
                focusRequests = navFocus,
                onTab = onTab,
                onClose = onClose,
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    LobbyTab.Channels -> ChannelPage(
                        isTv = isTv,
                        blocks = blocks,
                    activeId = activeId,
                    currentId = currentId,
                    onPick = onPick,
                    onSwitchSource = onSwitchSource,
                )
                    LobbyTab.Sources -> SourcesPage(
                        sources = sources,
                        activeId = activeId,
                        lockedId = lockedId,
                        onSwitch = onSwitchSource,
                        onLock = onLock,
                        onEdit = onEdit,
                        onRefresh = onRefreshSource,
                    )
                    LobbyTab.Settings -> SettingsPage(
                        current = current,
                        sources = sources,
                        activeId = activeId,
                        lockedId = lockedId,
                        decoder = decoder,
                        errorCode = errorCode,
                        mediaInfo = mediaInfo,
                        vlcDiag = vlcDiag,
                        decodeMode = decodeMode,
                        onDecodeMode = onDecodeMode,
                        playEngine = playEngine,
                        onPlayEngine = onPlayEngine,
                        keyUpNext = keyUpNext,
                        onKeyUpNext = onKeyUpNext,
                        onRefresh = onRefresh,
                        onRefreshEpg = onRefreshEpg,
                        onRefreshSource = onRefreshSource,
                        updateUrl = updateUrl,
                        onUpdateUrl = onUpdateUrl,
                        pairInfo = pairInfo,
                        pending = pending,
                        onSaveDraft = onSaveDraft,
                        onDropDraft = onDropDraft,
                        isTv = isTv,
                        onAddManual = onAddManual,
                        onLock = onLock,
                        onEdit = onEdit,
                        onSwitchSource = onSwitchSource,
                    )
                }
            }
        }
    }
}

// ── 一级导航 ────────────────────────────────────────────────────────

@Composable
private fun NavRail(
    isTv: Boolean,
    current: LobbyTab,
    focusRequests: List<FocusRequester>,
    onTab: (LobbyTab) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxHeight()
            .width(if (isTv) 150.dp else 116.dp)
            .padding(top = if (isTv) 34.dp else 20.dp, bottom = if (isTv) 28.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "直播",
            color = Color(0xFF64748B),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(start = 26.dp, bottom = 18.dp),
        )
        LobbyTab.entries.forEachIndexed { i, t ->
            NavItem(
                tab = t,
                isTv = isTv,
                selected = t == current,
                focusRequester = focusRequests[t.ordinal],
                // 显式指定上下焦点：默认 2D 搜索会把「频道→信号源」的下一站算到右边内容区，
                // 导致按下键跳不出导航。绑成环形，三个导航项之间上下一定能走到。
                upRequester = focusRequests[(i - 1 + LobbyTab.entries.size) % LobbyTab.entries.size],
                downRequester = focusRequests[(i + 1) % LobbyTab.entries.size],
                // 焦点一到就切页：遥控器上下移到「信号源」，右边内容立刻跟着换，
                // 不再需要按 OK 确认。这是切页的第一真相源，没有第二套机制抢焦点。
                onFocused = { if (isTv) onTab(t) },
                // 第二真相源：上下键在导航项上自己吞掉并换页。
                // 真机上焦点链一旦错位，onFocusChanged 就哑火（高亮会动、页面不换），
                // 这条按键直通路径不依赖焦点系统，两个都通才敢说「按到就切」。
                onNavStep = { delta ->
                    val n = ((i + delta) % LobbyTab.entries.size + LobbyTab.entries.size) %
                        LobbyTab.entries.size
                    onTab(LobbyTab.entries[n])
                    runCatching { focusRequests[n].requestFocus() }
                },
                onClick = { onTab(t) },
            )
            Spacer(Modifier.height(if (isTv) 6.dp else 4.dp))
        }
        Spacer(Modifier.weight(1f))
        // 版本号摆在大厅里：换了新包一眼就能确认，不用翻设置
        Text(
            "v" + rememberVersionName(),
            color = Color(0xFF334155),
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 26.dp, bottom = 6.dp),
        )
        Text(
            if (isTv) "返回键 关闭" else "点旁边 关闭",
            color = Color(0xFF475569),
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 26.dp)
                .clickable { onClose() },
        )
    }
}

@Composable
private fun NavItem(
    tab: LobbyTab,
    isTv: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    onFocused: () -> Unit,
    onNavStep: (Int) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // 选中态跟着「当前页」走，焦点态只负责描边。
    // 以前高亮由焦点决定，焦点一旦没落到导航项上，就会出现「页面没换、高亮却在跳」的错觉。
    val bg = when {
        focused -> NavFocusBg
        selected -> NavSelectedBg
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Color.White
        selected -> NavSelectedFg
        else -> Color(0xFFB6C2D2)
    }
    Row(
        Modifier.fillMaxWidth(0.82f)
            .height(if (isTv) 54.dp else 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            // 顺序很关键：focusRequester / focusProperties 必须在真正的焦点目标之前，
            // onFocusChanged 也要在它之前才能观察到它。
            //
            // 旧写法是 .focusable() + .clickable{} 叠了两个焦点节点，
            // focusProperties 又落在 clickable 那个节点上——DPAD 焦点进了 clickable，
            // onFocusChanged 监听的却是 focusable，于是「高亮不亮、页面不换」。
            // 现在只留 clickable 一个焦点节点，其余全部排在它前面。
            .focusRequester(focusRequester)
            .focusProperties {
                if (upRequester != null) up = upRequester
                if (downRequester != null) down = downRequester
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // 上下键走「捕获阶段」：0.5.4 用 onKeyEvent（冒泡阶段），
            // 事件先被焦点节点和 ROM 的方向键导航吃掉，实机表现是「高亮动了、页面不换，
            // 必须再按一次 OK」。onPreviewKeyEvent 在焦点系统处理之前就拿到按键，
            // 直接切页 + 主动把焦点搬到新导航项，不依赖 onFocusChanged 是否可靠。
            .onPreviewKeyEvent { e ->
                if (!isTv || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { onNavStep(-1); true }
                    Key.DirectionDown -> { onNavStep(1); true }
                    else -> false
                }
            }
            .clickable { onClick() }
            // 0.5.7：导航项宽度有限，「信号源」三个字原来被挤到换行。
            // 收紧左右留白和图标间距，给文字腾出一排的宽度；手机端也一起收紧。
            .padding(horizontal = if (isTv) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(tab, fg)
        Spacer(Modifier.width(if (isTv) 8.dp else 6.dp))
        Text(
            tab.label,
            color = fg,
            fontSize = if (isTv) 19.sp else 16.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 导航图标全部自绘：只为三个小图标引整包 material-icons 不划算。 */
@Composable
private fun NavIcon(tab: LobbyTab, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val w = size.width
        val h = size.height
        when (tab) {
            // 频道：一台电视（圆角屏幕 + 两根天线）
            LobbyTab.Channels -> {
                val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.30f),
                    size = Size(w * 0.84f, h * 0.60f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.32f, h * 0.06f), Offset(w * 0.46f, h * 0.28f), w * 0.09f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.68f, h * 0.06f), Offset(w * 0.54f, h * 0.28f), w * 0.09f, StrokeCap.Round)
            }
            // 信号源：播放三角（和「源」= 片源的意思对上）
            LobbyTab.Sources -> {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.84f)
                    close()
                }
                drawPath(p, color)
            }
            // 设置：齿轮
            LobbyTab.Settings -> drawGear(color, LobbyBgTop)
        }
    }
}

// ── 频道页：分类栏 + 频道列表 ───────────────────────────────────────

@Composable
private fun ChannelPage(
    isTv: Boolean,
    blocks: List<ChannelSourceBlock>,
    activeId: String?,
    currentId: String?,
    onPick: (ChannelSourceBlock, Channel) -> Unit,
    onSwitchSource: (Source) -> Unit,
) {
    val block = blocks.firstOrNull { it.source.id == activeId }
        ?: blocks.firstOrNull()
    val channels = block?.result?.channels.orEmpty()
    val groups = block?.result?.groups.orEmpty()

    // 分类：「全部」+ 源自带的分组。切源时回到「全部」，否则会停在一个新源没有的分类上
    var category by remember(block?.source?.id) { mutableStateOf(ALL) }
    val shown = remember(channels, groups, category) {
        if (category == ALL || groups.none { it.name == category }) channels
        else groups.first { it.name == category }.channels
    }

    val catFocus = remember { FocusRequester() }
    val chFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // 右键从分类栏进节目列表时，焦点必须挂在一个真实存在的行上。
    // 老逻辑只在 ch.id == currentId 时挂 chFocus；当正在播的台不在当前分类
    // （或不属于当前源）时，没有任何行挂载，requestFocus 抛异常被吞，
    // RailItem 又无条件消费右键 → 焦点卡在分类栏，怎么按都进不去列表。
    // 修法：目标行优先当前台，不在 shown 里就退回第一行，保证永远有挂载点。
    val focusTargetId = if (shown.any { it.id == currentId }) currentId else shown.firstOrNull()?.id

    // 呼出侧栏 / 换源 / 换台：定位到正在播放的台。
    // 当前台不在新源里时必须回第一行——否则会沿用上一次的 index，
    // 用户选了第二组却看到「第 51 台」开始的错位列表。
    LaunchedEffect(block?.source?.id, currentId) {
        val idx = shown.indexOfFirst { it.id == currentId }
        runCatching { listState.scrollToItem(if (idx >= 0) idx else 0) }
    }

    // 切分类（全部 → 第二组）必须从这个组的第一个台开始。
    // 不能并进上面那条 Effect：切分类时 currentId 没变，那条不会重跑，
    // 但 shown 已经换成另一组了，滚动位置还停在上一次的 index。
    var lastCategory by remember(block?.source?.id) { mutableStateOf(category) }
    LaunchedEffect(category) {
        if (category != lastCategory) {
            runCatching { listState.scrollToItem(0) }
            lastCategory = category
        }
    }

    Row(Modifier.fillMaxSize()) {
        // 第二栏：源（多源时）+ 分类
        Column(
            Modifier.width(if (isTv) 140.dp else 116.dp).fillMaxHeight()
                .padding(top = if (isTv) 30.dp else 20.dp, bottom = 24.dp, end = 8.dp),
        ) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (blocks.size > 1) {
                    item(key = "cap-src") { RailCaption("信号源") }
                    itemsIndexed(blocks, key = { _, b -> "src-" + b.source.id }) { i, b ->
                        RailItem(
                            text = b.source.name,
                            selected = b.isActive,
                            focusRequester = if (i == 0) catFocus else null,
                            onRight = { requestFocus(chFocus) },
                            onClick = { if (!b.isActive) onSwitchSource(b.source) },
                        )
                    }
                    item(key = "gap") {
                        Box(
                            Modifier.fillMaxWidth(0.8f)
                                .height(1.dp)
                                .background(Color(0xFF1E293B))
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                item(key = "cap-cat") { RailCaption("分类") }
                item(key = "cat-all") {
                    RailItem(
                        text = "全部",
                        selected = category == ALL,
                        focusRequester = if (blocks.size <= 1) catFocus else null,
                        onRight = { requestFocus(chFocus) },
                        onClick = { category = ALL },
                    )
                }
                items(groups, key = { "cat-" + it.name }) { g ->
                    RailItem(
                        text = g.name,
                        selected = category == g.name,
                        onRight = { requestFocus(chFocus) },
                        onClick = { category = g.name },
                    )
                }
            }
        }

        // 第三栏：频道
        Column(Modifier.weight(1f).fillMaxHeight()
            .padding(top = if (isTv) 30.dp else 20.dp, bottom = 20.dp, end = if (isTv) 16.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    block?.source?.name ?: "没有源",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${shown.size} 个频道",
                    color = TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (shown.isEmpty()) {
                Text(
                    when {
                        block == null -> "还没有直播源，去「信号源」里扫码添加"
                        block.result == null -> "这个源还没缓存：到「信号源」页点「刷新当前源」"
                        else -> "这个分类下没有频道"
                    },
                    color = TextDim,
                    fontSize = 16.sp,
                )
            } else {
                val order = remember(channels) {
                    val map = HashMap<String, Int>(channels.size * 2)
                    channels.forEachIndexed { i, c -> map[c.id] = i + 1 }
                    map
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.id }) { ch ->
                        // 焦点目标：优先当前台，当前台不在本分类/本源就退回第一行
                        val isFocusTarget = ch.id == focusTargetId
                        LobbyChannelRow(
                            number = order[ch.id] ?: 0,
                            channel = ch,
                            playing = block?.isActive == true && ch.id == currentId,
                            modifier = if (isFocusTarget) Modifier.focusRequester(chFocus) else Modifier,
                            onLeft = { requestFocus(catFocus) },
                            onClick = { block?.let { onPick(it, ch) } },
                        )
                    }
                }
            }
        }
    }
}

private const val ALL = "全部"

@Composable
private fun RailCaption(text: String) {
    Text(
        text,
        color = Color(0xFF475569),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RailItem(
    text: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onRight: () -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(
                when {
                    focused -> NavFocusBg
                    selected -> Color(0x1AF1F5F9)
                    else -> Color.Transparent
                },
            )
            .focusRing(focused, shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            // 只留 clickable 一个焦点节点。原来这里还叠了 focusable()，
            // 焦点到底落在哪个节点不确定，onFocusChanged 因此时灵时不灵。
            .onPreviewKeyEvent { e ->
                // 右键进频道列表：跳成功才消费；跳失败放行给 Compose 的焦点搜索兜底。
                // 以前无条件返回 true，目标没挂载时就把事件吞了，焦点卡在分类栏。
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    onRight()
                } else {
                    false
                }
            }
            .clickable { onClick() }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中但没有焦点时用一个细竖条表示「当前分类」，焦点态整行高亮就够，不再重复
        Box(
            Modifier.width(3.dp).height(18.dp)
                .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(11.dp))
        Text(
            text,
            color = when {
                focused -> Color.White
                selected -> CatSelected
                else -> Color(0xFF94A3B8)
            },
            fontSize = 17.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 频道行：序号 + 台标 + 名称 + 当前节目。就是参考图里那一行。 */
@Composable
private fun LobbyChannelRow(
    number: Int,
    channel: Channel,
    playing: Boolean,
    modifier: Modifier,
    onLeft: () -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(
                when {
                    focused -> RowFocus
                    playing -> Color(0x331D4ED8)
                    else -> Color.Transparent
                },
            )
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            // 同上：clickable 是唯一焦点节点，不再叠加 focusable()
            .onPreviewKeyEvent { e ->
                // 左键回分类栏：跳成功才消费，跳失败让 Compose 兜底
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                    onLeft()
                } else {
                    false
                }
            }
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (number > 0) number.toString() else "·",
            color = if (playing) Accent else Color(0xFF94A3B8),
            fontSize = 15.sp,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(10.dp))
        ChannelLogo(channel.logo, channel.name, 38)
        Spacer(Modifier.width(10.dp))
        Text(
            channel.name,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        // 在播的台靠整行高亮 + 蓝色序号表示；侧栏太窄，不再塞「正在播放」四个字
        Spacer(Modifier.weight(1f))
        // 当前节目：参考图里这个位置是节目名/「网络连接失败」一类的状态
        Text(
            channel.current.ifBlank { if (channel.next.isNotBlank()) "接下来 " + channel.next else "" },
            color = if (channel.current.isBlank()) Color(0xFF64748B) else Color(0xFF86EFAC),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

/** 台标：拉不到就显示名字首字，不留空洞。 */
@Composable
private fun ChannelLogo(url: String, name: String, sizeDp: Int) {
    val bmp by produceState<Bitmap?>(initialValue = null, url) {
        value = if (url.isBlank()) null else runCatching { LogoLoader.load(url, sizeDp * 2) }.getOrNull()
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0x14FFFFFF)),
        )
    } else {
        Box(
            Modifier.size(sizeDp.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).ifBlank { "·" },
                color = Color(0xFF94A3B8),
                fontSize = (sizeDp / 2.6).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── 信号源页 ────────────────────────────────────────────────────────

@Composable
private fun SourcesPage(
    sources: List<Source>,
    activeId: String?,
    lockedId: String?,
    onSwitch: (Source) -> Unit,
    onLock: (Source) -> Unit,
    onEdit: (Source) -> Unit,
    onRefresh: (Source) -> Unit,
) {
    // 第四点：光标停在「当前正在用的源」上，不是永远落在第一条
    val activeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(activeId, sources.size) {
        val idx = sources.indexOfFirst { it.id == activeId }
        if (idx >= 0) runCatching { listState.scrollToItem(idx) }
        // 焦点不主动抢到内容区——否则上下键变成滚列表，第五点的导航切页就失灵。
        // 当前源用 isActive 高亮 + 滚动到位来体现；用户按右键进内容区时，
        // activeFocus 挂在它上面，Compose 焦点搜索会优先落到它。
    }

    Column(Modifier.fillMaxSize().padding(top = 30.dp, end = 24.dp)) {
        Text("信号源", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "已添加 ${sources.size} 条源 · 点一行切换；右键到「编辑 / 锁定」，确认键执行。",
            color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sources.size, key = { i -> sources[i].id }) { i ->
                val one = sources[i]
                SourceRow(
                    isTv = true,
                    tvFocusable = true,
                    source = one,
                    isActive = one.id == activeId,
                    isLocked = one.id == lockedId,
                    focusRequester = if (one.id == activeId || (activeId == null && i == 0)) activeFocus else null,
                    onSwitch = { onSwitch(one) },
                    onLock = { onLock(one) },
                    onEdit = { onEdit(one) },
                    onRefresh = { onRefresh(one) },
                )
            }
            if (sources.isEmpty()) {
                item {
                    Text(
                        "还没有源。到「设置」里扫二维码添加。",
                        color = TextDim, fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

/**
 * 扫码添加源：二维码 + 配对说明 + 待保存列表。
 * 信号源页和设置页都放一份——用户说「设置里面就要能扫码添加源」，
 * 与其做一个点了再跳的按钮，不如直接把二维码摆在那里。
 */
@Composable
private fun AddSourceQrBlock(
    isTv: Boolean,
    pairInfo: PairInfo?,
    pending: List<DraftSource>,
    onSaveDraft: (DraftSource) -> Unit,
    onDropDraft: (DraftSource) -> Unit,
    onAddManual: (String, String, String) -> String?,
) {
    val qr = remember(pairInfo?.url) { pairInfo?.let { QrEncoder.bitmap(it.url, 420) } }
    var formOpen by remember { mutableStateOf(false) }
    var formName by remember { mutableStateOf("") }
    var formUrl by remember { mutableStateOf("") }
    var formEpg by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    val fieldFont = if (isTv) 16.sp else 14.sp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("添加直播源", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        // 扫码和手填并列：没带手机时也要能直接输地址，不能逼着人去另一台设备
        TextButton(onClick = { formOpen = !formOpen }) {
            Text(if (formOpen) "收起手动添加" else "手动添加源", fontSize = if (isTv) 14.sp else 13.sp)
        }
        if (formOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = formName,
                    onValueChange = { formName = it },
                    label = { Text("名称", fontSize = fieldFont) },
                    singleLine = true,
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(fontSize = fieldFont),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formUrl,
                    onValueChange = { formUrl = it },
                    label = { Text("M3U 地址（http / https）", fontSize = fieldFont) },
                    singleLine = true,
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(fontSize = fieldFont),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formEpg,
                    onValueChange = { formEpg = it },
                    label = { Text("节目单地址（可留空）", fontSize = fieldFont) },
                    singleLine = true,
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(fontSize = fieldFont),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val err = onAddManual(formName.trim(), formUrl.trim(), formEpg.trim())
                        if (err == null) {
                            formName = ""; formUrl = ""; formEpg = ""; formError = ""
                            formOpen = false
                        } else {
                            formError = err
                        }
                    }) { Text("保存", fontSize = if (isTv) 14.sp else 13.sp) }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = {
                        formOpen = false; formError = ""
                        formName = ""; formUrl = ""; formEpg = ""
                    }) { Text("取消", fontSize = if (isTv) 14.sp else 13.sp) }
                }
                if (formError.isNotBlank()) {
                    Text(formError, color = Color(0xFFFCA5A5), fontSize = if (isTv) 13.sp else 12.sp)
                }
            }
        }
        Text("扫码添加", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (qr != null && pairInfo != null) {
            // 侧栏比全屏窄，二维码与说明上下排才不会横向溢出
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "添加源二维码",
                modifier = Modifier.size(150.dp).background(Color.White).padding(6.dp),
            )
            Text("配对码 ${pairInfo.code}", color = Color.White, fontSize = 18.sp)
            Text(
                "手机连同一个 WiFi，用相机扫码填订阅地址；确定后先出现在「待保存」，再点保存。",
                color = TextDim, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        } else {
            Text("正在准备二维码…", color = TextDim, fontSize = 13.sp)
        }
        if (pending.isNotEmpty()) {
            Text("待保存（${pending.size}）", color = Accent, fontSize = 14.sp)
            pending.forEach { d ->
                DraftRow(true, d, { onSaveDraft(d) }, { onDropDraft(d) })
            }
        }
    }
}

// ── 设置页 ──────────────────────────────────────────────────────────

@Composable
private fun SettingsPage(
    isTv: Boolean,
    current: Channel?,
    sources: List<Source>,
    activeId: String?,
    lockedId: String?,
    decoder: String,
    errorCode: String = "",
    mediaInfo: String = "",
    vlcDiag: String = "",
    decodeMode: DecodeMode,
    onDecodeMode: (DecodeMode) -> Unit,
    playEngine: PlayEngine,
    onPlayEngine: (PlayEngine) -> Unit,
    keyUpNext: Boolean,
    onKeyUpNext: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRefreshEpg: () -> Unit,
    onRefreshSource: (Source) -> Unit,
    updateUrl: String,
    onUpdateUrl: (String) -> Unit,
    pairInfo: PairInfo?,
    pending: List<DraftSource>,
    onSaveDraft: (DraftSource) -> Unit,
    onDropDraft: (DraftSource) -> Unit,
    onAddManual: (String, String, String) -> String?,
    onLock: (Source) -> Unit,
    onEdit: (Source) -> Unit,
    onSwitchSource: (Source) -> Unit,
) {
    val context = LocalContext.current
    val crashLog = remember { Diag.lastCrash(context) }
    val deviceInfo = remember { Diag.deviceInfo(context) }
    // 不依赖 BuildConfig（AGP 8 默认不再生成），直接问系统要 versionName
    val versionName = remember {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    // 用 LazyColumn 而不是 Column+verticalScroll：
    // 遥控器只能靠焦点移动来滚，verticalScroll 不响应方向键，
    // 内容一超出屏幕下半截就永远够不到（尤其加了更新块之后）。
    // 0.5.5 起按功能拆成五张卡片：播放 / 信号源 / 在线更新 / 诊断 / 关于。
    // 原来所有设置平铺在一列里，用户说「看着都挤在一块，很乱」。
    LazyColumn(
        Modifier.fillMaxSize().padding(top = 30.dp, end = 56.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 顶部锚点：LazyColumn 只会滚到「有焦点的 item」。标题原来是不可聚焦的
        // 纯 Text，焦点落到下面的按钮后就再也回不到最上面（用户实机反馈）。
        item {
            FocusableBlock {
                Column {
                    Text("设置", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "现在：${current?.name ?: "未播放"}　·　源：${sources.firstOrNull { it.id == activeId }?.name ?: "无"}",
                        color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ① 播放：解码方式 / 播放引擎 / 换台键
        item {
            SettingCard("播放") {
                LabeledRow("解码方式") {
                    DecodeMode.entries.forEach { m ->
                        ModeChip(m.label, m == decodeMode, isTv = isTv) { onDecodeMode(m) }
                    }
                }
                LabeledRow("播放引擎") {
                    PlayEngine.entries.forEach { e ->
                        ModeChip(e.label, e == playEngine, isTv = isTv) { onPlayEngine(e) }
                    }
                }
                Text(
                    when (playEngine) {
                        PlayEngine.AUTO -> "自动：默认 ExoPlayer，6 秒没画面或解码失败自动换 VLC；谁真放出画面就记住谁，下次直接用。手动切过也算数。"
                        PlayEngine.EXO -> "ExoPlayer：启动快、省内存，但播不了 MPEG-2 视频和 AC3 音频的老源。"
                        PlayEngine.VLC -> "VLC：兼容性最强，几乎所有源都能播；内存占用更高，老盒子可能略卡。"
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                )
                LabeledRow("换台键") {
                    ModeChip("上 = 下一台", keyUpNext, isTv = isTv) { onKeyUpNext(true) }
                    ModeChip("上 = 上一台", !keyUpNext, isTv = isTv) { onKeyUpNext(false) }
                }
            }
        }

        // ② 信号源：已添加的源 + 扫码 / 手填
        item {
            SettingCard("信号源") {
                Text(
                    "已添加 ${sources.size} 条源 · 点一行切换；右键到「编辑 / 锁定」，确认键执行。",
                    color = TextDim, fontSize = 13.sp,
                )
                if (sources.isEmpty()) {
                    Text("还没有源，用下面的扫码或手动添加。", color = TextDim, fontSize = 13.sp)
                } else {
                    sources.forEach { one ->
                        SourceRow(
                            isTv = isTv,
                            tvFocusable = isTv,
                            source = one,
                            isActive = one.id == activeId,
                            isLocked = one.id == lockedId,
                            onSwitch = { onSwitchSource(one) },
                            onLock = { onLock(one) },
                            onEdit = { onEdit(one) },
                            onRefresh = { onRefreshSource(one) },
                        )
                    }
                }
                // 两个刷新按钮并排：一个连订阅带节目单整套重拉，一个只刷填入的节目单
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRefresh) { Text("刷新全部信号源", fontSize = 14.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRefreshEpg) { Text("刷新节目单", fontSize = 14.sp) }
                }
                AddSourceQrBlock(isTv, pairInfo, pending, onSaveDraft, onDropDraft, onAddManual)
            }
        }

        // ③ 在线更新
        item {
            SettingCard("在线更新") {
                UpdateSection(isTv = true, url = updateUrl, onUrlChange = onUpdateUrl)
            }
        }

        // ④ 诊断：只看不按的文字集中在一起，不再插在可操作项中间打断焦点。
        // 内部没有按钮，必须让外壳可聚焦，否则遥控器滚不到这一块。
        item {
            SettingCard("诊断", shellFocusable = true) {
                if (mediaInfo.isNotBlank()) {
                    Text("当前编码：$mediaInfo", color = Color(0xFF93C5FD), fontSize = 12.sp)
                }
                if (decoder.isNotBlank()) {
                    val soft = decoder.startsWith("c2.android.") || decoder.startsWith("OMX.google.")
                    Text(
                        "解码：$decoder（${if (soft) "软解，会掉帧" else "硬解"}）",
                        color = if (soft) Color(0xFFF87171) else Color(0xFF4ADE80),
                        fontSize = 12.sp,
                    )
                }
                if (errorCode.isNotBlank()) {
                    val decoderIssue = errorCode.contains("DECODER", ignoreCase = true)
                    Text(
                        "最近错误：$errorCode（${if (decoderIssue) "解码器" else "多为网络/源"}）",
                        color = Color(0xFFFCA5A5), fontSize = 12.sp,
                    )
                }
                if (vlcDiag.isNotBlank()) {
                    Text("VLC 引擎：$vlcDiag", color = Color(0xFFFCA5A5), fontSize = 11.sp)
                }
                Text("启动耗时：${Diag.BootTiming.summary()}", color = Color(0xFF93C5FD), fontSize = 11.sp)
            }
        }

        // ⑤ 关于：同样是纯文字，外壳可聚焦才能滚到
        item {
            SettingCard("关于", shellFocusable = true) {
                Text(deviceInfo, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 4)
                Text("版本 $versionName", color = Color(0xFF64748B), fontSize = 11.sp)
                if (crashLog != null) {
                    Text(
                        "上次崩溃记录：" + crashLog.lineSequence().take(6).joinToString("\n"),
                        color = Color(0xFFFCA5A5), fontSize = 10.sp, maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDim, fontSize = 15.sp, modifier = Modifier.width(110.dp))
        content()
    }
}

/**
 * 设置页的一张卡片：独立圆角背景 + 标题。
 *
 * 0.5.8 方案 B——外壳默认**不可聚焦**，只做视觉容器。
 * 旧版给外壳加了 focusable()，结果遥控器焦点先落到这个大 Box 上：
 * 它没有 clickable，按 OK 没反应；上下键又容易整块跳过内部按钮。
 * 检查更新后动态出现的「下载并安装」因此基本到不了（用户实机反馈）。
 * 现在焦点直接落在内部按钮上，上下键在按钮之间走，OK 立刻生效。
 *
 * 只有内部完全没有可聚焦控件的纯文字卡片（诊断 / 关于）才传 shellFocusable = true，
 * 否则整块不可聚焦、LazyColumn 永远滚不到它。
 */
@Composable
private fun SettingCard(
    title: String,
    shellFocusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth(0.97f)
            .clip(shape)
            .background(if (focused) Color(0xFF17263B) else Color(0xFF101A2B))
            .focusRing(focused, shape)
            // focusable 紧跟 onFocusChanged，确保高亮状态能被观察到；
            // 不可聚焦的外壳后面没有 focus node，focused 恒为 false，不高亮也不抢焦点。
            .then(if (shellFocusable) Modifier.focusable() else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * 给纯文字块一个可聚焦的壳，带细微高亮。
 *
 * LazyColumn 的滚动完全由焦点驱动：焦点落到哪个 item，它就滚到哪。
 * 不可聚焦的 Text 永远拿不到焦点，于是列表顶部/底部这些纯文字区就滚不过去
 * （实机反馈「按到顶也看不到最上面、看不到下面」）。包一层 focusable 后，
 * 遥控器上下键能逐块走到，滚动自然到位。
 *
 * 只在 TV 侧栏正文里用——手机是触摸滚动，多几个焦点框反而是干扰。
 */
@Composable
private fun FocusableBlock(content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (focused) Color(0x332563EB) else Color.Transparent)
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) { content() }
}

/**
 * 把焦点请求包一层：节点可能还没挂载，直接 requestFocus 会抛异常。
 * 返回是否成功——调用方据此决定要不要消费按键：跳失败就放行给 Compose
 * 自己的焦点搜索兜底，不能「吞了事件又没跳成」，那会让焦点彻底卡死。
 */
private fun requestFocus(fr: FocusRequester): Boolean =
    runCatching { fr.requestFocus() }.isSuccess

/** 系统里读到的版本名（不依赖 BuildConfig）。 */
@Composable
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
}
