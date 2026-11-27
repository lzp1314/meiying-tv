package com.lik.iptvplayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 拉订阅 -> 解析 M3U -> 自动发现并拉取 EPG -> 按 tvg-id 对表。 */
class SourceRepository(
    private val store: SourceStore,
    val prefs: PlaybackPrefs,
    private val snapshots: SnapshotStore,
) {
    private val _sources = MutableStateFlow(store.list())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    /** 锁定的源；非空时启动固定用它，切换其它源需先解锁。 */
    private val _lockedId = MutableStateFlow<String?>(null)
    val lockedId: StateFlow<String?> = _lockedId.asStateFlow()

    /** 内存里缓存「快照」而不是现成的结果：每次取用都按当前时间重算节目单。 */
    private val cache = ConcurrentHashMap<String, Snapshot>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()

    init {
        // 升级安装后源可能被删过，锁定 id 指向不存在的源时清掉，避免启动直接进设置
        val locked = prefs.lockedSourceId
        _lockedId.value = if (locked != null && _sources.value.any { it.id == locked }) locked else null
        if (locked != null && _lockedId.value == null) prefs.lockedSourceId = null
        snapshots.prune(_sources.value.map { it.id }.toSet())
    }

    /**
     * 取缓存：先内存，内存没有再读磁盘文件。
     * 磁盘读在 IO 线程，冷启动第一次会多花几十毫秒，但远快于重新下载。
     */
    suspend fun loadCached(id: String): RefreshResult? = withContext(Dispatchers.IO) {
        cache[id]?.let { return@withContext it.toResult() }
        val snap = snapshots.load(id) ?: return@withContext null
        cache[id] = snap
        fetchedAt[id] = snap.fetchedAt
        snap.toResult()
    }

    /** 缓存是否老到值得联网重拉。没有缓存时视为过期。 */
    fun isStale(id: String, now: Long = System.currentTimeMillis()): Boolean {
        val at = fetchedAt[id] ?: cache[id]?.fetchedAt ?: return true
        return now - at > FRESH_MS
    }

    fun cacheAge(id: String, now: Long = System.currentTimeMillis()): Long {
        val at = fetchedAt[id] ?: cache[id]?.fetchedAt ?: return -1L
        return now - at
    }

    fun add(name: String, url: String, epgUrl: String = ""): Source? {
        val normalized = M3uParser.normalizeSourceUrl(url) ?: return null
        val source = Source(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "未命名源" },
            url = normalized,
            epgUrl = epgUrl.trim(),
        )
        store.put(source)
        _sources.value = store.list()
        return source
    }

    /** 编辑已有源：名称 / 订阅地址 / 节目单地址；成功后清缓存，下次刷新按新地址走。 */
    fun update(id: String, name: String, url: String, epgUrl: String): Source? {
        val old = _sources.value.firstOrNull { it.id == id } ?: return null
        val normalized = M3uParser.normalizeSourceUrl(url) ?: return null
        val updated = old.copy(
            name = name.trim().ifBlank { old.name },
            url = normalized,
            epgUrl = epgUrl.trim(),
        )
        store.put(updated)
        dropCache(id)
        _sources.value = store.list()
        return updated
    }

    fun remove(id: String) {
        store.remove(id)
        dropCache(id)
        _sources.value = store.list()
        if (_lockedId.value == id) setLocked(null)
        if (prefs.lastSourceId == id) prefs.lastSourceId = null
    }

    private fun dropCache(id: String) {
        cache.remove(id)
        fetchedAt.remove(id)
        snapshots.remove(id)
    }

    fun setLocked(id: String?) {
        prefs.lockedSourceId = id
        _lockedId.value = id
    }

    fun markUsed(id: String) {
        prefs.lastSourceId = id
    }

    /** 启动该用哪个源：锁定源 > 上次用的源 > 第一个。 */
    fun activeSource(): Source? {
        val all = _sources.value
        return _lockedId.value?.let { id -> all.firstOrNull { it.id == id } }
            ?: prefs.lastSourceId?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull()
    }

    suspend fun refresh(id: String): Result<RefreshResult> = withContext(Dispatchers.IO) {
        runCatching {
            val src = _sources.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("订阅源不存在")

            val playlist = M3uParser.parse(Network.text(src.url))
            if (playlist.channels.isEmpty()) throw IllegalStateException("订阅内容里没有解析到频道")

            // 节目单地址的优先级：用户手填 > 播放列表声明 > 同源推导 > 内置候选
            var usedEpgUrl = src.epgUrl.ifBlank {
                M3uParser.resolveEpgUrl(src.url, playlist.declaredEpgUrl).orEmpty()
            }
            var epg: Epg? = null
            var epgError: String? = null

            val wantedIds = playlist.channels
                .mapNotNull { it.tvgId.trim().takeIf(String::isNotEmpty) }
                .toSet()
            val wantedNames = playlist.channels.map { it.name }.toSet()
            val limit = if (Diag.isLowRam) Network.EPG_MAX_BYTES_LOW_RAM else Network.EPG_MAX_BYTES

            // 内置候选：源自己没带节目单时自动试这几个公开源，用户不用手填。
            // 按体积从小到大排，1GB 内存的盒子上优先命中 1MB 那个，解析最快。
            val candidates = buildList {
                if (usedEpgUrl.isNotBlank()) add(usedEpgUrl)
                // 用户手填了地址就只信它，不再到处乱试
                if (src.epgUrl.isBlank()) addAll(BUILTIN_EPG.filter { it != usedEpgUrl })
            }

            for (candidate in candidates) {
                try {
                    val parsed = Network.stream(candidate, limit) { input ->
                        XmltvParser.parseStream(input, wantedIds, wantedNames)
                    }
                    // 兜底：源的频道标识和节目单完全对不上时，退回「全量解析」再试一次。
                    // 宁可多占内存，也好过用户看到一整屏「暂无节目信息」。
                    val usable = if (parsed.programmes.isEmpty() &&
                        (wantedIds.isNotEmpty() || wantedNames.isNotEmpty())
                    ) {
                        Network.stream(candidate, limit) { input -> XmltvParser.parseStream(input) }
                    } else {
                        parsed
                    }
                    if (usable.programmes.isEmpty()) {
                        epgError = "节目单里没有能对上本源频道的节目"
                        continue
                    }
                    epg = usable
                    usedEpgUrl = candidate
                    epgError = null
                    break
                } catch (t: Throwable) {
                    // 关键：OutOfMemoryError 不是 Exception。原来只 catch Exception，
                    // 一个超大节目单就能让整个应用闪退，这里必须按 Throwable 兜住。
                    epgError = t.message?.take(80) ?: t.javaClass.simpleName
                }
            }

            val now = System.currentTimeMillis()
            val snap = Snapshot(
                sourceId = src.id,
                subscriptionUrl = src.url,
                declaredEpgUrl = playlist.declaredEpgUrl,
                epgUrl = usedEpgUrl.ifBlank { null },
                epgError = epgError,
                warnings = playlist.warnings,
                epgByTvg = epg?.channels.orEmpty(),
                epgByName = epg?.byName.orEmpty(),
                programmes = trimProgrammes(epg, now),
                channels = playlist.channels.map {
                    it.copy(current = "", currentStart = 0L, currentStop = 0L, next = "")
                },
                fetchedAt = now,
            )

            cache[id] = snap
            fetchedAt[id] = now
            snapshots.save(snap)
            // 内置候选命中后把地址写回源里：下次直接用，不再逐个试
            val autoEpg = if (src.epgUrl.isBlank() && epg != null) usedEpgUrl else src.epgUrl
            store.put(src.copy(lastOk = now, channelCount = snap.channels.size, epgUrl = autoEpg))
            _sources.value = store.list()
            snap.toResult(now)
        }
    }

    /**
     * 只重拉节目单，不碰 M3U 订阅（0.5.8「刷新节目单」按钮）。
     *
     * 场景：用户手填了 EPG 地址，订阅列表没变，只想让节目单更新。
     * 走 [refresh] 会把订阅也重下一遍（可能几 MB、老盒子上很慢），这里省掉那一步；
     * 频道列表沿用现有缓存，只换 EPG 三张表并按当前时间重算 now/next。
     * 完全没有缓存时退化成 [refresh]——没有频道就没法对表。
     */
    suspend fun refreshEpg(id: String): Result<RefreshResult> = withContext(Dispatchers.IO) {
        val src = _sources.value.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalArgumentException("订阅源不存在"))
        val old = cache[id] ?: snapshots.load(id)
        if (old == null) return@withContext refresh(id)

        runCatching {
            val wantedIds = old.channels
                .mapNotNull { it.tvgId.trim().takeIf(String::isNotEmpty) }
                .toSet()
            val wantedNames = old.channels.map { it.name }.toSet()
            val limit = if (Diag.isLowRam) Network.EPG_MAX_BYTES_LOW_RAM else Network.EPG_MAX_BYTES

            // 地址优先级：用户手填 > 上次成功用的（自动发现回写）> 播放列表声明
            var usedEpgUrl = src.epgUrl.ifBlank { old.epgUrl.orEmpty() }
            if (usedEpgUrl.isBlank()) usedEpgUrl = old.declaredEpgUrl.orEmpty()
            var epg: Epg? = null
            var epgError: String? = null

            val candidates = buildList {
                if (usedEpgUrl.isNotBlank()) add(usedEpgUrl)
                // 用户手填了就只信它，不再到处乱试（与 refresh 保持同一策略）
                if (src.epgUrl.isBlank()) addAll(BUILTIN_EPG.filter { it != usedEpgUrl })
            }

            for (candidate in candidates) {
                try {
                    val parsed = Network.stream(candidate, limit) { input ->
                        XmltvParser.parseStream(input, wantedIds, wantedNames)
                    }
                    val usable = if (parsed.programmes.isEmpty() &&
                        (wantedIds.isNotEmpty() || wantedNames.isNotEmpty())
                    ) {
                        Network.stream(candidate, limit) { input -> XmltvParser.parseStream(input) }
                    } else {
                        parsed
                    }
                    if (usable.programmes.isEmpty()) {
                        epgError = "节目单里没有能对上本源频道的节目"
                        continue
                    }
                    epg = usable
                    usedEpgUrl = candidate
                    epgError = null
                    break
                } catch (t: Throwable) {
                    // OutOfMemoryError 不是 Exception，必须按 Throwable 兜住
                    epgError = t.message?.take(80) ?: t.javaClass.simpleName
                }
            }

            val now = System.currentTimeMillis()
            val snap = old.copy(
                epgUrl = usedEpgUrl.ifBlank { null },
                epgError = epgError,
                epgByTvg = epg?.channels.orEmpty(),
                epgByName = epg?.byName.orEmpty(),
                programmes = trimProgrammes(epg, now),
                fetchedAt = now,
            )
            cache[id] = snap
            fetchedAt[id] = now
            snapshots.save(snap)
            val autoEpg = if (src.epgUrl.isBlank() && epg != null) usedEpgUrl else src.epgUrl
            if (autoEpg != src.epgUrl) {
                store.put(src.copy(epgUrl = autoEpg))
                _sources.value = store.list()
            }
            snap.toResult(now)
        }
    }

    /**
     * 快照只留「过去 2 小时 ~ 未来」的节目，并且有总量上限。
     * 不设上限的话，一次全量节目单能写出十几 MB JSON，序列化时字符串本身就能把
     * 1GB 内存的盒子撑爆——那台机器上「加载数据源闪退」就是这么来的。
     */
    private fun trimProgrammes(epg: Epg?, now: Long): Map<String, List<Programme>> {
        if (epg == null) return emptyMap()
        val from = now - 2 * 3_600_000L
        val kept = epg.programmes.mapNotNull { (key, list) ->
            val alive = list.filter { it.stop >= from }
            if (alive.isEmpty()) null else key to alive
        }
        if (kept.isEmpty()) return emptyMap()

        val perChannelCap = if (Diag.isLowRam) 20 else 60
        val totalCap = if (Diag.isLowRam) 3_000 else 12_000
        val per = minOf(perChannelCap, maxOf(8, totalCap / kept.size))
        return kept.associate { (key, list) ->
            key to list.sortedBy { it.start }.take(per)
        }
    }

    private companion object {
        /**
         * 24 小时内拉过就不再联网。电视常隔天才开，2 小时太短，几乎每次都触发
         * 后台重拉整套 EPG（3 个候选逐个试，老盒子上很慢）。直播流本身随时可用，
         * 节目单旧一天不影响观看；手动「刷新当前源」仍可强制更新。
         */
        const val FRESH_MS = 24 * 3_600_000L

        /**
         * 内置节目单候选。源自己不带节目单时按顺序试，命中即停。
         * 体积小的排前面：老盒子解析 1MB 远比 7MB 轻松。
         * 这些是公开服务，随时可能失效，所以是「候选」而不是硬依赖；
         * 全部失败时应用照常只显示频道，不影响播放。
         */
        val BUILTIN_EPG = listOf(
            "http://epg.51zmt.top:8000/e.xml",
            "https://laobaiepg.laobaitv.net/guide_mainland.xml",
            "https://epg.pw/xmltv/epg_CN.xml",
        )
    }
}
