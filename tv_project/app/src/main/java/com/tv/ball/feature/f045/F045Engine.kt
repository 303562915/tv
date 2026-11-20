package com.tv.ball.feature.f045

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条搜索历史。
 *
 * @param keyword 归一化之后的关键词（已去掉首尾空白、连续空白压成一个空格）
 * @param usedAtMs 最近一次搜索的时间戳，列表按它倒序排
 */
data class HistoryEntry(val keyword: String, val usedAtMs: Long)

/**
 * 搜索历史的存取层（纯数据逻辑，不碰任何 View）。
 *
 * 三条需求对应三个方法，且都不依赖调用顺序：
 *
 * 1. **去重**：[add] 先按关键词（忽略大小写）把旧记录摘掉，再把新记录插到最前面，
 *    所以「重复关键词只留最新一条」是插入时的性质，不靠读的时候补救。
 * 2. **限量**：[capacity] 默认 20 条，超过就从尾部砍——列表已经是时间倒序，
 *    尾部就是最旧的那条，不需要再比时间戳。
 * 3. **删除与清空**：[remove] 删一条，[clear] 清空并返回被清掉的条数（页面用来说 Toast）。
 *
 * 持久化：整份历史序列化成 JSON 数组，存在 SharedPreferences 的 [KEY_HISTORY] 键里，
 * 形如 `[{"keyword":"蓝色海洋","usedAtMs":1735660800000}]`。
 * 解析失败（手改 XML、旧版本残留）一律当空历史处理，不抛异常给页面。
 */
class HistoryStore(
    private val prefs: SharedPreferences,
    /** 历史上限。页面只读不改，改上限得新建一个 Store。 */
    val capacity: Int = DEFAULT_CAPACITY
) {

    init {
        require(capacity > 0) { "capacity 必须是正数，实际 $capacity" }
    }

    /** 全量历史，时间倒序（最近搜的在第一条）。 */
    fun all(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList()).take(capacity)
    }

    /** 当前条数，用来显示「x / 上限」。 */
    fun size(): Int = all().size

    /** 只剩关键词的视图，方便页面或测试直接断言顺序。 */
    fun keywords(): List<String> = all().map { it.keyword }

    /** 关键词是否已在历史里（忽略大小写）。 */
    fun contains(keyword: String): Boolean = find(keyword) != null

    /** 找第一条命中的记录，没有返回 null。 */
    fun find(keyword: String): HistoryEntry? {
        val key = normalize(keyword) ?: return null
        return all().firstOrNull { it.keyword.equals(key, ignoreCase = true) }
    }

    /**
     * 记一条搜索。
     *
     * - 空白关键词直接忽略并返回 null（页面据此提示「没记录」）；
     * - 已存在同关键词（忽略大小写）的先删掉再插到最前，保留用户这次输入的写法；
     * - 超出 [capacity] 时淘汰最旧的一条。
     *
     * @return 真正落盘的记录；关键词为空时返回 null
     */
    fun add(keyword: String, nowMs: Long = System.currentTimeMillis()): HistoryEntry? {
        val key = normalize(keyword) ?: return null
        val rest = all().filterNot { it.keyword.equals(key, ignoreCase = true) }
        val entry = HistoryEntry(key, nowMs)
        save((listOf(entry) + rest).take(capacity))
        return entry
    }

    /** 删掉一条。关键词不在历史里返回 false，页面据此决定要不要提示。 */
    fun remove(keyword: String): Boolean {
        val key = normalize(keyword) ?: return false
        val current = all()
        val next = current.filterNot { it.keyword.equals(key, ignoreCase = true) }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    /** 整体清空，返回被清掉的条数。 */
    fun clear(): Int {
        val removed = all().size
        prefs.edit().remove(KEY_HISTORY).apply()
        return removed
    }

    /** 当前历史的 JSON 文本，调试或导出用。 */
    fun exportJson(): String = encode(all())

    /** 直接覆盖整份历史：入口都走 add / remove / clear，这里只给测试与导入用。 */
    fun replaceAll(entries: List<HistoryEntry>) {
        save(entries.take(capacity))
    }

    private fun save(entries: List<HistoryEntry>) {
        prefs.edit().putString(KEY_HISTORY, encode(entries)).apply()
    }

    companion object {
        /** 需求约定的 SharedPreferences 文件名。 */
        const val PREFS_NAME: String = "f045_search_history"

        /** 需求约定的键名：整份历史一个 JSON 数组。 */
        const val KEY_HISTORY: String = "f045_history"

        /** 需求约定的上限：20 条。 */
        const val DEFAULT_CAPACITY: Int = 20

        fun create(context: Context, capacity: Int = DEFAULT_CAPACITY): HistoryStore =
            HistoryStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                capacity
            )

        /**
         * 关键词归一化：去掉首尾空白，连续空白压成一个空格。
         *
         * 归一化放在入口做一次，后面比较、存储、显示都用同一个字符串，
         * 免得「 蓝色海洋 」和「蓝色海洋」在历史里变成两条。
         */
        fun normalize(raw: String?): String? {
            val collapsed = raw?.trim()?.replace(WHITESPACE, " ") ?: return null
            return collapsed.ifEmpty { null }
        }

        private val WHITESPACE = Regex("\\s+")

        fun encode(entries: List<HistoryEntry>): String {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("keyword", e.keyword)
                        put("usedAtMs", e.usedAtMs)
                    }
                )
            }
            return arr.toString()
        }

        /**
         * 解析历史 JSON。
         *
         * 这一步做得比 [add] 宽容：文件可能被手改过，可能有重复条目、也可能顺序乱了。
         * 所以先按时间倒序排，再去重（留最新那条），保证任何输入都能得到合法列表。
         */
        fun decode(raw: String): List<HistoryEntry> {
            val arr = JSONArray(raw)
            val parsed = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val keyword = normalize(o.optString("keyword")) ?: return@mapNotNull null
                HistoryEntry(keyword, o.optLong("usedAtMs"))
            }
            return parsed
                .sortedByDescending { it.usedAtMs }
                .distinctBy { it.keyword.lowercase() }
        }
    }
}
