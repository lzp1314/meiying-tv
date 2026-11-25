package com.lik.iptvplayer.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 订阅源持久化。应用不预置任何源，首次启动为空列表。 */
class SourceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_sources", Context.MODE_PRIVATE)

    fun list(): List<Source> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    fun put(source: Source) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == source.id }
        if (idx >= 0) current[idx] = source else current.add(source)
        write(current)
    }

    fun remove(id: String) = write(list().filter { it.id != id })

    private fun write(items: List<Source>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(s: Source) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("url", s.url); put("epgUrl", s.epgUrl)
        put("demo", s.demo); put("addedAt", s.addedAt); put("lastOk", s.lastOk)
        put("channelCount", s.channelCount)
    }

    private fun fromJson(o: JSONObject) = Source(
        id = o.optString("id"), name = o.optString("name"), url = o.optString("url"),
        epgUrl = o.optString("epgUrl"), demo = o.optBoolean("demo", false),
        addedAt = o.optLong("addedAt", 0L), lastOk = o.optLong("lastOk", 0L),
        channelCount = o.optInt("channelCount", 0),
    )

    private companion object { const val KEY = "sources_json" }
}
