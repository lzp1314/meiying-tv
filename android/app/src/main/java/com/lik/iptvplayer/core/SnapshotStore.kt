package com.lik.iptvplayer.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 频道 + 节目单的离线快照。
 *
 * 为什么需要：M3U 与 XMLTV 都可能有几百 KB，冷启动重新下载要等十几秒。
 * 快照落盘后启动可以先照缓存起播，再在后台决定要不要刷新，
 * 用户不必每次进应用都盯着「正在拉取」。
 *
 * 只存「结构性数据」（频道列表 + 节目时间轴），**不存 now/next**：
 * 那两个字段随时间变化，读取时用当前时间重算，避免放一夜就显示昨天的节目。
 */
data class Snapshot(
    val sourceId: String,
    val subscriptionUrl: String,
    val declaredEpgUrl: String?,
    val epgUrl: String?,
    val epgError: String?,
    val warnings: List<String>,
    val epgByTvg: Map<String, String>,
    val epgByName: Map<String, String>,
    val programmes: Map<String, List<Programme>>,
    val channels: List<Channel>,
    val fetchedAt: Long,
) {
    /** 按当前时间重算每个频道的「正在播 / 接下来」。 */
    fun toResult(now: Long = System.currentTimeMillis()): RefreshResult {
        val epg = Epg(epgByTvg, epgByName, programmes)
        val list = channels.map { ch ->
            val nn = XmltvParser.nowNext(epg, ch.tvgId, ch.name, now)
            ch.copy(
                current = nn?.current?.title.orEmpty(),
                currentStart = nn?.current?.start ?: 0L,
                currentStop = nn?.current?.stop ?: 0L,
                next = nn?.next?.title.orEmpty(),
            )
        }
        return RefreshResult(
            sourceId = sourceId,
            subscriptionUrl = subscriptionUrl,
            declaredEpgUrl = declaredEpgUrl,
            epgUrl = epgUrl,
            epgError = epgError,
            epgChannelCount = programmes.values.sumOf { it.size },
            epgMatchedCount = list.count { it.current.isNotEmpty() || it.next.isNotEmpty() },
            channels = list,
            groups = M3uParser.group(list),
            warnings = warnings,
            fetchedAt = fetchedAt,
        )
    }
}

class SnapshotStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "snapshots")

    fun load(sourceId: String): Snapshot? {
        return try {
            val f = File(dir, "$sourceId.json")
            if (!f.isFile) return null
            val o = JSONObject(f.readText(Charsets.UTF_8))
            if (o.optInt("v", 0) != FORMAT) return null

            val channels = mutableListOf<Channel>()
            val chArr = o.optJSONArray("channels") ?: JSONArray()
            for (i in 0 until chArr.length()) {
                val c = chArr.optJSONObject(i) ?: continue
                channels += Channel(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    group = c.optString("group", "未分组"),
                    logo = c.optString("logo"),
                    tvgId = c.optString("tvgId"),
                    url = c.optString("url"),
                    userAgent = c.optString("ua"),
                    referrer = c.optString("ref"),
                )
            }
            if (channels.isEmpty()) return null

            val programmes = mutableMapOf<String, List<Programme>>()
            val pgObj = o.optJSONObject("programmes") ?: JSONObject()
            for (key in pgObj.keys()) {
                val arr = pgObj.optJSONArray(key) ?: continue
                val list = ArrayList<Programme>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONArray(i) ?: continue
                    val start = p.optLong(0)
                    val stop = p.optLong(1)
                    if (start <= 0L || stop <= start) continue
                    list += Programme(start, stop, p.optString(2), p.optString(3))
                }
                if (list.isNotEmpty()) programmes[key] = list
            }

            Snapshot(
                sourceId = sourceId,
                subscriptionUrl = o.optString("subscriptionUrl"),
                declaredEpgUrl = o.optString("declaredEpgUrl").ifBlank { null },
                epgUrl = o.optString("epgUrl").ifBlank { null },
                epgError = o.optString("epgError").ifBlank { null },
                warnings = o.optJSONArray("warnings").toStringList(),
                epgByTvg = o.optJSONObject("epgByTvg").toStringMap(),
                epgByName = o.optJSONObject("epgByName").toStringMap(),
                programmes = programmes,
                channels = channels,
                fetchedAt = o.optLong("fetchedAt"),
            )
        } catch (e: Exception) {
            // 文件损坏不该影响启动：删掉重来
            runCatching { File(dir, "$sourceId.json").delete() }
            null
        }
    }

    fun save(snap: Snapshot) {
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val o = JSONObject()
            o.put("v", FORMAT)
            o.put("subscriptionUrl", snap.subscriptionUrl)
            o.put("declaredEpgUrl", snap.declaredEpgUrl ?: "")
            o.put("epgUrl", snap.epgUrl ?: "")
            o.put("epgError", snap.epgError ?: "")
            o.put("fetchedAt", snap.fetchedAt)

            val chArr = JSONArray()
            snap.channels.forEach { ch ->
                chArr.put(
                    JSONObject().apply {
                        put("id", ch.id); put("name", ch.name); put("group", ch.group)
                        put("logo", ch.logo); put("tvgId", ch.tvgId); put("url", ch.url)
                        put("ua", ch.userAgent); put("ref", ch.referrer)
                    },
                )
            }
            o.put("channels", chArr)

            // 节目单用紧凑数组：["开始","结束","标题","简述"]，比对象字面量省一半体积
            val pgObj = JSONObject()
            snap.programmes.forEach { (key, list) ->
                val arr = JSONArray()
                list.forEach { p ->
                    arr.put(
                        JSONArray().put(p.start).put(p.stop).put(p.title)
                            .put(p.desc.take(80)),
                    )
                }
                pgObj.put(key, arr)
            }
            o.put("programmes", pgObj)
            o.put("epgByTvg", snap.epgByTvg.toJsonObject())
            o.put("epgByName", snap.epgByName.toJsonObject())
            o.put("warnings", JSONArray(snap.warnings))

            // 先写临时文件再改名，避免写一半被杀进程留下坏文件
            val tmp = File(dir, "${snap.sourceId}.json.tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            val dst = File(dir, "${snap.sourceId}.json")
            if (!tmp.renameTo(dst)) {
                dst.delete()
                tmp.renameTo(dst)
            }
        }
    }

    fun remove(sourceId: String) {
        runCatching { File(dir, "$sourceId.json").delete() }
    }

    /** 清理已删除源的快照，免得应用目录越用越大。 */
    fun prune(keep: Set<String>) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                val id = f.name.substringBefore('.')
                if (id !in keep) f.delete()
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).ifBlank { null } }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (key in keys()) {
            val v = optString(key)
            if (v.isNotBlank()) out[key] = v
        }
        return out
    }

    private fun Map<String, String>.toJsonObject(): JSONObject {
        val o = JSONObject()
        forEach { (k, v) -> o.put(k, v) }
        return o
    }

    private companion object { const val FORMAT = 1 }
}
