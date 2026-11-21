package com.tv.ball.presentation.shortdrama

import android.content.Context
import com.tv.ball.di.Dependencies

/**
 * 短剧搜索历史。
 *
 * 为什么单独存一份、不复用 data.local.SearchHistoryManager：
 * 那个是「影视搜索」的历史（SharedPreferences 名 search_history），共用会把短剧名混进影视搜索历史里，
 * 两边互相污染。这里用独立文件名 short_drama_search_history，互不影响。
 *
 * 为什么用 Dependencies.context 而不是在 Activity 里初始化：
 * 收藏/进度一类的全局存取都是这个路子，Activity 反复进出不需要 init，静态访问即可。
 */
object ShortDramaSearchHistory {

    private const val PREFS_NAME = "short_drama_search_history"
    private const val KEY_HISTORY = "keywords"
    private const val MAX_SIZE = 10

    /** 用控制字符分隔，避免关键词本身含逗号/竖线时把词切坏（影视搜索那边用 "|" 就有这个隐患） */
    private const val SEPARATOR = "\u0001"

    private fun prefs() = Dependencies.context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近的在前，最多 MAX_SIZE 条 */
    fun all(): List<String> {
        val raw = prefs().getString(KEY_HISTORY, null) ?: return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.take(MAX_SIZE)
    }

    /** 记一次搜索：去重（旧位置删掉）、插到第一位、超长截断 */
    fun add(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val list = all().toMutableList()
        list.removeAll { it.equals(kw, ignoreCase = true) }
        list.add(0, kw)
        val trimmed = if (list.size > MAX_SIZE) list.subList(0, MAX_SIZE).toList() else list
        prefs().edit().putString(KEY_HISTORY, trimmed.joinToString(SEPARATOR)).apply()
    }

    fun clear() {
        prefs().edit().remove(KEY_HISTORY).apply()
    }
}