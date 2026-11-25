package com.lik.iptvplayer.core

import java.net.URI

/**
 * M3U 解析：与 prototype/lib/parse.js 的 parseM3u / resolveEpgUrl 逐条对应。
 * 关键点：
 * 1. 先剥离所有 key="value" 属性，再取逗号后的显示名，属性里的逗号不会截断名字。
 * 2. #EXTVLCOPT 的 UA / Referer 归属紧随其后的频道（Media3 会自动应用）。
 * 3. EPG 地址优先取 M3U 头声明，否则同源推导 /epg.xml 并保留访问密码路径段。
 */
object M3uParser {
    private val ATTR = Regex("([\\w:.-]+)\\s*=\\s*\"([^\"]*)\"")
    private val FILE_LIKE = Regex("\\.(m3u8?|txt|xml|json)$", RegexOption.IGNORE_CASE)

    internal fun parseAttrs(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(s)) out[m.groupValues[1].lowercase()] = m.groupValues[2]
        return out
    }

    private class Pending(
        val attrs: Map<String, String>,
        val name: String,
        var userAgent: String = "",
        var referrer: String = "",
    )

    fun parse(text: String): Playlist {
        var declared: String? = null
        val channels = mutableListOf<Channel>()
        val warnings = mutableListOf<String>()
        var pending: Pending? = null
        var seq = 0

        for (raw in text.split(Regex("\r?\n"))) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = parseAttrs(line)
                    declared = a["x-tvg-url"] ?: a["url-tvg"]
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val a = parseAttrs(line)
                    val rest = ATTR.replace(line, "")
                    val comma = rest.indexOf(',')
                    val name = if (comma >= 0) rest.substring(comma + 1).trim() else ""
                    pending = Pending(a, name)
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val kv = line.substringAfter(':', "")
                    val eq = kv.indexOf('=')
                    if (eq > 0 && pending != null) {
                        val value = kv.substring(eq + 1).trim()
                        when (kv.substring(0, eq).lowercase()) {
                            "http-user-agent" -> pending!!.userAgent = value
                            "http-referrer", "http-referer" -> pending!!.referrer = value
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    if (p == null) {
                        warnings += "孤立的流地址：${line.take(60)}"
                    } else {
                        val a = p.attrs
                        val display = p.name.ifEmpty { a["tvg-name"].orEmpty() }.ifEmpty { line }
                        channels += Channel(
                            id = "ch-${++seq}",
                            name = display,
                            group = a["group-title"].takeUnless { it.isNullOrEmpty() } ?: "未分组",
                            logo = a["tvg-logo"].orEmpty(),
                            tvgId = a["tvg-id"] ?: a["tvg-name"] ?: display,
                            url = line,
                            userAgent = p.userAgent,
                            referrer = p.referrer,
                        )
                        pending = null
                    }
                }
            }
        }
        return Playlist(declared, channels, warnings)
    }

    /** 返回节目单绝对地址；订阅地址非法时返回 null。 */
    fun resolveEpgUrl(subscriptionUrl: String, declared: String?): String? {
        val base = try {
            URI(subscriptionUrl)
        } catch (e: Exception) {
            return null
        }
        if (base.scheme?.lowercase() !in setOf("http", "https")) return null

        if (!declared.isNullOrBlank()) {
            try {
                val d = URI(declared)
                val resolved = if (d.isAbsolute) d else base.resolve(d)
                if (resolved.scheme?.lowercase() in setOf("http", "https")) return resolved.toString()
            } catch (e: Exception) {
                // 声明非法则回落到同源推导
            }
        }

        val path = base.path?.ifEmpty { "/" } ?: "/"
        val last = path.substringAfterLast('/')
        val isFile = FILE_LIKE.containsMatchIn(last) || last == "json" || last == "txt"
        val dir = if (isFile) path.removeSuffix(last) else if (path.endsWith("/")) path else "$path/"
        return "${base.scheme}://${base.authority}$dir" + "epg.xml"
    }

    fun normalizeSourceUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            val u = URI(s)
            if (u.scheme?.lowercase() in setOf("http", "https") && u.host != null) u.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    fun group(channels: List<Channel>): List<ChannelGroup> {
        val order = mutableListOf<String>()
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in channels) {
            val g = ch.group.ifEmpty { "未分组" }
            if (!map.containsKey(g)) {
                map[g] = mutableListOf()
                order += g
            }
            map[g]!! += ch
        }
        return order.map { ChannelGroup(it, map[it]!!.toList()) }
    }
}
