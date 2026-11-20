package com.tv.ball.feature.f038

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多播放列表的存取层。
 *
 * 列表本身很小（名称 + 若干条目 id），用 SharedPreferences 存一份 JSON 就够，
 * 也不必为一个功能加一张表。切换当前列表只改一个 id，读的时候再解析。
 */
class F038Store(private val prefs: SharedPreferences) {

    data class Playlist(val id: String, val name: String, val items: List<String>, val createdAtMs: Long)

    companion object {
        private const val KEY_LISTS = "lists_json"
        private const val KEY_CURRENT = "current_id"
        const val PREFS_NAME = "f038_playlists"

        fun create(context: Context): F038Store =
            F038Store(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    fun all(): List<Playlist> {
        val raw = prefs.getString(KEY_LISTS, null) ?: return seed()
        return runCatching { parse(raw) }.getOrElse { seed() }
    }

    fun currentId(): String = prefs.getString(KEY_CURRENT, null) ?: all().firstOrNull()?.id.orEmpty()

    fun current(): Playlist? = all().firstOrNull { it.id == currentId() } ?: all().firstOrNull()

    fun switchTo(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    fun create(name: String, nowMs: Long): Playlist {
        val trimmed = name.trim().ifEmpty { "新列表" }
        val list = Playlist(
            id = "pl-" + nowMs.toString(36) + "-" + (all().size + 1),
            name = trimmed,
            items = emptyList(),
            createdAtMs = nowMs
        )
        saveAll(all() + list)
        return list
    }

    fun rename(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val lists = all()
        val idx = lists.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val next = lists.toMutableList()
        next[idx] = next[idx].copy(name = trimmed)
        saveAll(next)
        return true
    }

    fun remove(id: String): Boolean {
        val lists = all()
        if (lists.none { it.id == id }) return false
        saveAll(lists.filterNot { it.id == id })
        if (currentId() == id) prefs.edit().remove(KEY_CURRENT).apply()
        return true
    }

    fun saveAll(lists: List<Playlist>) {
        val arr = JSONArray()
        lists.forEach { l ->
            arr.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("createdAtMs", l.createdAtMs)
                put("items", JSONArray(l.items))
            })
        }
        prefs.edit().putString(KEY_LISTS, arr.toString()).apply()
    }

    private fun parse(raw: String): List<Playlist> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).map { j -> a.optString(j) }
            } ?: emptyList()
            Playlist(
                id = o.optString("id"),
                name = o.optString("name"),
                items = items,
                createdAtMs = o.optLong("createdAtMs")
            )
        }.filter { it.id.isNotEmpty() }
    }

    /** 第一次进来给三个示例列表，避免空页面看不出功能。 */
    private fun seed(): List<Playlist> {
        val now = System.currentTimeMillis()
        return listOf(
            Playlist("pl-demo-1", "通勤电台", listOf("a1", "a2", "a3"), now - 86_400_000L),
            Playlist("pl-demo-2", "周末补番", listOf("b1", "b2"), now - 3_600_000L),
            Playlist("pl-demo-3", "本地试片", emptyList(), now)
        ).also { saveAll(it) }
    }
}
