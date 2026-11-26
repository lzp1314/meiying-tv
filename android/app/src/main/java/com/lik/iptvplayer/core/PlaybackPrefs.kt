package com.lik.iptvplayer.core

import android.content.Context

/**
 * 解码方式。
 *
 * - AUTO：硬件解码器优先，硬解全部失败时才回落软件解码（默认，兼容性最好）
 * - HARDWARE：只挑硬件解码器。老盒子上画面「一顿一顿」时手动指定它，
 *   可以确定不会误用软件解码；该机型没有硬解器时会报错而不是偷偷软解
 * - SOFTWARE：只用软件解码。个别盒子硬解有 bug（花屏、音画不同步）时用它绕开
 */
enum class DecodeMode(val key: String, val label: String) {
    AUTO("auto", "自动"),
    HARDWARE("hw", "硬解"),
    SOFTWARE("sw", "软解"),
    ;

    companion object {
        fun of(key: String?): DecodeMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 播放引擎。
 *
 * 两个引擎各有硬边界，所以必须能切：
 * - [EXO]：Media3/ExoPlayer。轻、启动快、内存占用低，能播主流 H.264 + AAC。
 *   但**没有内置软件解码器**，遇到 MPEG-2 视频、AC3/EAC3 音频、非标准 TS
 *   会直接报 ERROR_CODE_DECODER_INIT_FAILED 或卡死。
 * - [VLC]：libVLC。自带全套软解与容错，能播上述那些台；
 *   代价是内存占用更高，老盒子上纯软解 1080p 高码率会掉帧。
 * - [AUTO]：默认 ExoPlayer（它对 HLS 直播最稳、起播最快）；6 秒没出画面、
 *   或解码器报错，自动改用 VLC 再试；VLC 也失败会退回 ExoPlayer 重试一次。
 *   哪个引擎真正放出画面就记住哪个，下次这个台直接用记的那个。
 *   手动切过引擎也会被记下——用户的选择优先于自动判断。
 */
enum class PlayEngine(val key: String, val label: String) {
    AUTO("auto", "自动"),
    EXO("exo", "ExoPlayer"),
    VLC("vlc", "VLC"),
    ;

    companion object {
        fun of(key: String?): PlayEngine = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 播放偏好：锁定源 / 上次使用的源 / 每个源上次看的频道 / 解码方式 / 播放引擎。
 * 单独一份 SharedPreferences，与源列表（SourceStore）解耦。
 * 覆盖安装（包名与签名不变）时自动保留，用户不需要重新设置。
 */
class PlaybackPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_playback", Context.MODE_PRIVATE)

    var lockedSourceId: String?
        get() = prefs.getString(KEY_LOCKED, null)?.ifBlank { null }
        set(v) = prefs.edit().putString(KEY_LOCKED, v).apply()

    var lastSourceId: String?
        get() = prefs.getString(KEY_LAST_SOURCE, null)?.ifBlank { null }
        set(v) = prefs.edit().putString(KEY_LAST_SOURCE, v).apply()

    /**
     * 上次看的频道。按源分别记，因为 ch-1 这种 id 每个源都有。
     *
     * 早期存的是 `ch-N` 序号，而序号由 M3U 顺序生成——订阅一换顺序就串台。
     * 现在存稳定引用（流地址主干 > tvgId > 名称），读取时用同样的规则回找。
     */
    fun lastChannel(sourceId: String): String? =
        prefs.getString("last_ch_$sourceId", null)?.ifBlank { null }

    fun setLastChannel(sourceId: String, channel: Channel) {
        prefs.edit().putString("last_ch_$sourceId", channelKey(channel)).apply()
    }

    /** 频道的稳定身份：流地址去掉鉴权参数，其次 tvgId，最后名称。 */
    fun channelKey(ch: Channel): String {
        val urlKey = ch.url.substringBefore('?').trim()
        if (urlKey.isNotEmpty()) return "url:$urlKey"
        if (ch.tvgId.isNotBlank()) return "tvg:${ch.tvgId}"
        return "name:${ch.name}"
    }

    /**
     * 从频道列表里找回上次看的台。
     * 先按稳定引用精确匹配，再按 tvgId / 名称兜底，最后兼容旧版存的 `ch-N` 序号。
     */
    fun findLastChannel(sourceId: String, channels: List<Channel>): Channel? {
        val ref = lastChannel(sourceId) ?: return null
        val exact = channels.firstOrNull { channelKey(it) == ref }
        if (exact != null) return exact
        val byTvg = channels.firstOrNull { it.tvgId.isNotBlank() && "tvg:${it.tvgId}" == ref }
        if (byTvg != null) return byTvg
        val byName = channels.firstOrNull { "name:${it.name}" == ref }
        if (byName != null) return byName
        // 旧数据：ch-N 序号
        return channels.firstOrNull { it.id == ref }
    }

    /** 解码方式：重启、覆盖安装都保留，用户不用每次重设。 */
    var decodeMode: DecodeMode
        get() = DecodeMode.of(prefs.getString(KEY_DECODE_MODE, null))
        set(v) = prefs.edit().putString(KEY_DECODE_MODE, v.key).apply()

    /** 播放引擎：默认自动（ExoPlayer 优先，坏台自动切 VLC）。 */
    var playEngine: PlayEngine
        get() = PlayEngine.of(prefs.getString(KEY_PLAY_ENGINE, null))
        set(v) = prefs.edit().putString(KEY_PLAY_ENGINE, v.key).apply()

    /**
     * 每个频道**真正出过画面**的引擎。
     *
     * 0.5.0～0.5.2 记的是「在 ExoPlayer 上失败过」——这是个单向、永久的判决：
     * 网络抖动一次就把台判给 VLC，而当时根本没验证 VLC 能不能播；用户手动切回
     * ExoPlayer 也不改这个标记，于是下次又送回 VLC，来回死循环。
     *
     * 现在改成只记成功：谁把画面放出来了，就记谁。失败不写记忆，只做计数，
     * 连续两次失败才清掉旧记忆重新试——宁可多试一次，也不能把能播的台锁死。
     */
    fun engineOf(sourceId: String, channel: Channel): PlayEngine? {
        val key = channelKey(channel)
        return engineMemo(sourceId).firstOrNull { it.startsWith("$key\t") }
            ?.substringAfter('\t')
            ?.let { PlayEngine.of(it) }
            ?.takeIf { it != PlayEngine.AUTO }
    }

    /** 记录一次成功（出过画面）。只在真有画面的回调里调用。 */
    fun rememberEngine(sourceId: String, channel: Channel, engine: PlayEngine) {
        if (engine == PlayEngine.AUTO) return
        val key = channelKey(channel)
        val next = engineMemo(sourceId).filterNot { it.startsWith("$key\t") }.toMutableSet()
        next.add("$key\t${engine.key}")
        prefs.edit().putStringSet(memoKey(sourceId), next).apply()
        // 有成功记录，失败计数从头算
        clearFailCount(sourceId, key, engine)
    }

    /** 主动忘掉某个台的引擎记忆（用户把引擎切回「自动」时用）。 */
    fun forgetEngine(sourceId: String, channel: Channel) {
        val key = channelKey(channel)
        val next = engineMemo(sourceId).filterNot { it.startsWith("$key\t") }.toSet()
        prefs.edit().putStringSet(memoKey(sourceId), next).apply()
        for (e in PlayEngine.entries) clearFailCount(sourceId, key, e)
    }

    /**
     * 记一次失败。同一频道 + 同一引擎连续失败 [ENGINE_FAIL_LIMIT] 次，
     * 说明这条记忆不可信，直接删掉，下次 AUTO 回到 ExoPlayer 重新试。
     */
    fun noteEngineFailure(sourceId: String, channel: Channel, engine: PlayEngine) {
        if (engine == PlayEngine.AUTO) return
        val key = channelKey(channel)
        val n = failCount(sourceId, key, engine) + 1
        if (n >= ENGINE_FAIL_LIMIT) {
            forgetEngine(sourceId, channel)
            return
        }
        val all = failSet(sourceId).filterNot { it.startsWith("$key\t${engine.key}\t") }.toMutableSet()
        all.add("$key\t${engine.key}\t$n")
        prefs.edit().putStringSet(failKey(sourceId), all).apply()
    }

    private fun engineMemo(sourceId: String): Set<String> =
        prefs.getStringSet(memoKey(sourceId), null)?.toSet() ?: emptySet()

    private fun failSet(sourceId: String): Set<String> =
        prefs.getStringSet(failKey(sourceId), null)?.toSet() ?: emptySet()

    private fun failCount(sourceId: String, key: String, engine: PlayEngine): Int =
        failSet(sourceId).firstOrNull { it.startsWith("$key\t${engine.key}\t") }
            ?.substringAfterLast('\t')?.toIntOrNull() ?: 0

    private fun clearFailCount(sourceId: String, key: String, engine: PlayEngine) {
        val all = failSet(sourceId)
        val next = all.filterNot { it.startsWith("$key\t${engine.key}\t") }.toSet()
        if (next.size != all.size) prefs.edit().putStringSet(failKey(sourceId), next).apply()
    }

    private fun memoKey(sourceId: String) = "engine_ok_$sourceId"
    private fun failKey(sourceId: String) = "engine_fail_$sourceId"

    /**
     * 清掉 0.5.2 及更早版本留下的 `vlc_only_*` 脏数据。
     *
     * 那批数据是「ExoPlayer 失败过一次」就算数，没有任何成功验证，留着只会
     * 继续把能播的台往 VLC 上推。升级时一次性清空，让 AUTO 从头学一遍。
     * 用独立 flag 保证只跑一次。
     */
    private fun migrateLegacyVlcOnly() {
        if (prefs.getBoolean(KEY_MIGRATED_ENGINE, false)) return
        val stale = prefs.all.keys.filter { it.startsWith("vlc_only_") }
        val edit = prefs.edit()
        stale.forEach { edit.remove(it) }
        edit.putBoolean(KEY_MIGRATED_ENGINE, true).apply()
    }

    init {
        migrateLegacyVlcOnly()
    }

    /**
     * 遥控器上/下键的换台方向。
     * true = 上键切下一个台（默认，用户按顺手的习惯），false = 上键切上一个台。
     * CH+ / CH- 不受它影响，始终是「下一个 / 上一个」。
     */
    var keyUpNext: Boolean
        get() = prefs.getBoolean(KEY_UP_NEXT, true)
        set(v) = prefs.edit().putBoolean(KEY_UP_NEXT, v).apply()

    /**
     * 在线更新的信息地址（Gitee 上的 update.json 直链）。
     * 留空则用 [UpdateChecker.DEFAULT_URL]；用户改过就优先用改的。
     */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, null)?.ifBlank { null } ?: UpdateChecker.DEFAULT_URL
        set(v) = prefs.edit().putString(KEY_UPDATE_URL, v.trim()).apply()

    /** 上次检查到的新版本号，避免每次进设置都重新联网。 */
    var lastSeenVersion: Int
        get() = prefs.getInt(KEY_LAST_SEEN, 0)
        set(v) = prefs.edit().putInt(KEY_LAST_SEEN, v).apply()

    private companion object {
        const val KEY_LOCKED = "locked_source_id"
        const val KEY_LAST_SOURCE = "last_source_id"
        const val KEY_DECODE_MODE = "decode_mode"
        const val KEY_PLAY_ENGINE = "play_engine"
        const val KEY_UP_NEXT = "key_up_next"
        const val KEY_UPDATE_URL = "update_url"
        const val KEY_LAST_SEEN = "last_seen_version"
        const val KEY_MIGRATED_ENGINE = "migrated_engine_memo_v1"

        /** 同一频道 + 同一引擎连败几次就认定记忆失效。2 次即清，别让脏记忆长期生效。 */
        const val ENGINE_FAIL_LIMIT = 2
    }
}
