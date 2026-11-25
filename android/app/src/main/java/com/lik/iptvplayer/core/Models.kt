package com.lik.iptvplayer.core

/** 一个订阅源。epgUrl 为空表示「自动发现」，不硬编码任何服务地址。 */
data class Source(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String = "",
    val demo: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOk: Long = 0L,
    val channelCount: Int = 0,
)

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val url: String,
    val userAgent: String = "",
    val referrer: String = "",
    val current: String = "",
    val currentStart: Long = 0L,
    val currentStop: Long = 0L,
    val next: String = "",
)

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String,
)

data class Playlist(
    val declaredEpgUrl: String?,
    val channels: List<Channel>,
    val warnings: List<String>,
)

data class Epg(
    val channels: Map<String, String>,
    val byName: Map<String, String>,
    val programmes: Map<String, List<Programme>>,
)

data class NowNext(
    val current: Programme?,
    val next: Programme?,
    val matched: Boolean,
)

data class ChannelGroup(val name: String, val channels: List<Channel>)

data class RefreshResult(
    val sourceId: String,
    val subscriptionUrl: String,
    val declaredEpgUrl: String?,
    val epgUrl: String?,
    val epgError: String?,
    val epgChannelCount: Int,
    val epgMatchedCount: Int,
    val channels: List<Channel>,
    val groups: List<ChannelGroup>,
    val warnings: List<String>,
    val fetchedAt: Long,
)
