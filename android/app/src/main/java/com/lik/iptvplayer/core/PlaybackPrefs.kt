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
 * 播放偏好：锁定源 / 上次使用的源 / 每个源上次看的频道 / 解码方式。
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

    /** 频道 id 只在单个源内唯一（ch-1 每个源都有），所以按源分别记。 */
    fun lastChannel(sourceId: String): String? =
        prefs.getString("last_ch_$sourceId", null)?.ifBlank { null }

    fun setLastChannel(sourceId: String, channelId: String) {
        prefs.edit().putString("last_ch_$sourceId", channelId).apply()
    }

    /** 解码方式：重启、覆盖安装都保留，用户不用每次重设。 */
    var decodeMode: DecodeMode
        get() = DecodeMode.of(prefs.getString(KEY_DECODE_MODE, null))
        set(v) = prefs.edit().putString(KEY_DECODE_MODE, v.key).apply()

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
        const val KEY_UP_NEXT = "key_up_next"
        const val KEY_UPDATE_URL = "update_url"
        const val KEY_LAST_SEEN = "last_seen_version"
    }
}
