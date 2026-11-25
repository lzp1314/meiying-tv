package com.lik.iptvplayer.core

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV 解析：与 prototype/lib/parse.js 的 parseXmltv / nowNext 逐条对应。
 *
 * 解析分两条路径：
 * - [parse]：给单元测试和已拿到字符串的场景用，内部转流式。
 * - [parseStream]：正式路径。边下载边解析，正文不驻留内存；
 *   并且只保留「本订阅源里真实存在的频道」的节目，几十 MB 的全量节目单也能吃下。
 *
 * 为什么必须这样：1GB 内存的老机顶盒上，原来「整串正则 + 全量保留」会
 * OutOfMemoryError；而把上限压到 1.5MB 又会让国内十几 MB 的节目单整份丢弃，
 * 表现就是「电视端看不到节目单」。
 */
object XmltvParser {
    private val DISPLAY =
        Regex("""<display-name[^>]*>([\s\S]*?)</display-name\s*>""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title[^>]*>([\s\S]*?)</title\s*>""", RegexOption.IGNORE_CASE)
    private val DESC = Regex("""<desc[^>]*>([\s\S]*?)</desc\s*>""", RegexOption.IGNORE_CASE)
    private val CDATA = Regex("""<!\[CDATA\[([\s\S]*?)\]\]>""")
    private val TIME = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-]\d{4}))?""")
    // SimpleDateFormat 非线程安全，每次解析新建实例
    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    /** 单个频道的节目上限：正常节目单一天 30~50 条，再多基本是脏数据。 */
    private const val MAX_PER_CHANNEL = 400

    /** 全局上限，防止极脏的节目单吃满内存。 */
    private const val MAX_TOTAL = 80_000

    /**
     * 数据块切断时在缓冲区尾部保留的字符数。
     * 只需覆盖「半截标签头」，64 个字符足够（`<channel id=` 这类前缀都不到 20 个）。
     */
    private const val TAIL_KEEP = 64

    /** 频道名里常见的画质/后缀词，匹配前先削掉，避免「CCTV1高清」对不上「CCTV1」。 */
    private val DECOR = listOf(
        "高清", "超清", "蓝光", "标清", "4K", "1080P", "720P", "50帧", "频道", "综合", "一套", "HD",
    )

    private fun decode(s: String): String {
        var v = CDATA.replace(s, "$1")
        v = Regex("&#x([0-9a-fA-F]+);").replace(v) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        v = Regex("&#(\\d+);").replace(v) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
        }
        return v.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&").trim()
    }

    /** 支持 20261124200000 +0800 与 ISO 8601；无时区时按设备本地时区解释。 */
    fun parseTime(s: String): Long? {
        val v = s.trim()
        val m = TIME.find(v) ?: return try {
            isoFormat().parse(v)?.time
        } catch (e: Exception) {
            null
        }
        val n = { i: Int -> m.groupValues[i].toInt() }
        val Y = n(1); val M = n(2); val D = n(3)
        val h = n(4); val mi = n(5); val se = n(6)
        val tz = m.groupValues[7]
        val cal = if (tz.isEmpty()) Calendar.getInstance()
        else Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(Y, M - 1, D, h, mi, se)
        var ms = cal.timeInMillis
        if (tz.isNotEmpty()) {
            val sign = if (tz[0] == '-') -1 else 1
            val offMinutes = tz.substring(1, 3).toInt() * 60 + tz.substring(3).toInt()
            ms -= sign * offMinutes * 60_000L
        }
        return ms
    }

    /** 频道名/标识归一化：削画质后缀、去符号、转小写。 */
    private fun norm(raw: String): String {
        var v = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (d in DECOR) {
                if (v.length > d.length && v.endsWith(d, ignoreCase = true)) {
                    v = v.dropLast(d.length).trim()
                    changed = true
                }
            }
        }
        val sb = StringBuilder(v.length)
        for (ch in v) if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        return sb.toString()
    }

    /** 兼容旧签名：已拿到完整字符串时用。 */
    fun parse(xml: String): Epg =
        parseStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    /**
     * 流式解析。
     *
     * @param wantedIds 订阅源里频道的 tvg-id
     * @param wantedNames 订阅源里频道的显示名
     * 两个集合都为空时不做过滤，保留全部节目。
     */
    fun parseStream(
        input: InputStream,
        wantedIds: Set<String> = emptySet(),
        wantedNames: Set<String> = emptySet(),
    ): Epg {
        val channels = mutableMapOf<String, String>()
        val byName = mutableMapOf<String, String>()
        val programmes = mutableMapOf<String, MutableList<Programme>>()

        val wantId = HashSet<String>()
        val wantName = HashSet<String>()
        for (id in wantedIds) {
            val t = id.trim()
            if (t.isEmpty()) continue
            wantId += t
            val n = norm(t)
            if (n.isNotEmpty()) wantId += n
        }
        for (nm in wantedNames) {
            val n = norm(nm)
            if (n.isNotEmpty()) wantName += n
        }
        val filtering = wantId.isNotEmpty() || wantName.isNotEmpty()

        val acc = StringBuilder(1 shl 16)
        var cursor = 0
        var kept = 0

        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 15)
        val buf = CharArray(1 shl 14)

        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            acc.append(buf, 0, n)

            while (kept < MAX_TOTAL) {
                val pAt = acc.indexOf("<programme", cursor)
                val cAt = acc.indexOf("<channel", cursor)
                val p = if (pAt >= 0 && isBoundary(acc, pAt + 10)) pAt else -1
                val c = if (cAt >= 0 && isBoundary(acc, cAt + 8)) cAt else -1

                if (p < 0 && c < 0) {
                    // 没有合法起点。注意这里**不能**把游标推到 acc 末尾：
                    // 数据块很可能正好切在 "<chan" / "</chan" 中间，
                    // 推过去之后补全的 "<channel" 就永远扫不到了（节目单里的频道整段丢失）。
                    // 只保留尾部一小段重新扫，多余的前缀丢掉。
                    val keepFrom = maxOf(0, acc.length - TAIL_KEEP)
                    if (keepFrom > 0) acc.delete(0, keepFrom)
                    cursor = 0
                    break
                }

                val pos = if (p >= 0 && c >= 0) minOf(p, c) else maxOf(p, c)

                if (pos == c) {
                    val end = acc.indexOf("</channel>", pos)
                    if (end < 0) break // 段还没收全，等下一块数据
                    val idAt = acc.indexOf('>', pos)
                    if (idAt in 0 until end) {
                        val attrs = M3uParser.parseAttrs(acc.substring(pos + 8, idAt))
                        val id = attrs["id"]
                        if (!id.isNullOrBlank()) {
                            val rawDisplay = DISPLAY.find(acc.substring(idAt + 1, end))?.groupValues?.get(1)
                            val name = if (rawDisplay != null) decode(rawDisplay) else id
                            channels[id] = name
                            if (name.isNotEmpty()) byName[name] = id
                        }
                    }
                    cursor = end + 10
                    continue
                }

                val end = acc.indexOf("</programme>", pos)
                if (end < 0) break
                val headEnd = acc.indexOf('>', pos)
                if (headEnd < 0 || headEnd > end) {
                    cursor = end + 12
                    continue
                }
                val attrs = M3uParser.parseAttrs(acc.substring(pos + 10, headEnd))
                cursor = end + 12

                val channel = attrs["channel"] ?: continue
                if (filtering && !isWanted(channel, channels, wantId, wantName)) continue

                val start = parseTime(attrs["start"].orEmpty()) ?: continue
                val stop = parseTime(attrs["stop"].orEmpty()) ?: (start + 3_600_000L)

                val body = acc.substring(headEnd + 1, end)
                val title = TITLE.find(body)?.let { decode(it.groupValues[1]) } ?: "（无标题）"
                // desc 在列表里根本不显示，只在详情用；老盒子上省掉这一份字符串
                val desc = if (Diag.isLowRam) "" else DESC.find(body)?.let { decode(it.groupValues[1]) }.orEmpty()

                val list = programmes.getOrPut(channel) { mutableListOf() }
                if (list.size < MAX_PER_CHANNEL) {
                    list += Programme(start, stop, title, desc)
                    kept++
                }
            }

            // 已消费部分及时丢弃，acc 只保留未闭合的尾巴
            if (cursor > 8192) {
                acc.delete(0, cursor)
                cursor = 0
            }
            if (kept >= MAX_TOTAL) break
        }

        return Epg(
            channels.toMap(), byName.toMap(),
            programmes.mapValues { (_, list) -> list.sortedBy { it.start } }.toMap(),
        )
    }

    /** 标签名后面必须紧跟空白或 '>'，否则是 `<programmes>` 这类同前缀标签。 */
    private fun isBoundary(sb: CharSequence, idx: Int): Boolean {
        if (idx >= sb.length) return false
        val c = sb[idx]
        return c == ' ' || c == '>' || c == '\t' || c == '\n' || c == '\r' || c == '/'
    }

    /**
     * 这个节目属于本源要显示的频道吗。
     * 依次比：EPG 的 channel id、id 归一化后、以及该 channel 的 display-name。
     */
    private fun isWanted(
        channelId: String,
        channels: Map<String, String>,
        wantId: Set<String>,
        wantName: Set<String>,
    ): Boolean {
        if (channelId in wantId) return true
        val nid = norm(channelId)
        if (nid.isNotEmpty() && (nid in wantId || nid in wantName)) return true
        val display = channels[channelId] ?: return false
        val dn = norm(display)
        return dn.isNotEmpty() && (dn in wantName || dn in wantId)
    }

    /** 对表顺序：tvg-id -> display-name 反查 -> 频道名，与 hd-iptv/epg.py 的 mapping 一致。 */
    fun nowNext(epg: Epg, tvgId: String, channelName: String, now: Long = System.currentTimeMillis()): NowNext {
        val ids = mutableListOf<String>()
        if (tvgId.isNotEmpty()) ids += tvgId
        val mapped = epg.byName[tvgId] ?: epg.byName[channelName]
        if (mapped != null && mapped !in ids) ids += mapped
        if (channelName.isNotEmpty() && channelName !in ids) ids += channelName
        // 归一化后的名字也试一次，覆盖「CCTV-1 高清」对「cctv1」的情况
        val normed = norm(channelName)
        if (normed.isNotEmpty() && normed !in ids) ids += normed

        val list = ids.firstNotNullOfOrNull { epg.programmes[it]?.takeIf { p -> p.isNotEmpty() } }
            ?: return NowNext(null, null, false)

        val current = list.firstOrNull { it.start <= now && now < it.stop }
        val after = current?.stop ?: now
        val next = list.firstOrNull { it.start >= after }
        return NowNext(current, next, true)
    }
}
