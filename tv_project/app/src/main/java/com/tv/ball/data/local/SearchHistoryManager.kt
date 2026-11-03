package com.tv.ball.data.local

import android.content.Context
import android.content.SharedPreferences

object SearchHistoryManager {
    private const val PREFS_NAME = "search_history"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_HISTORY_SIZE = 10
    
    private lateinit var prefs: SharedPreferences
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun addSearchHistory(query: String) {
        if (query.isBlank()) return
        
        val history = getSearchHistory().toMutableList()
        
        // 移除重复项
        history.remove(query)
        
        // 添加到开头
        history.add(0, query)
        
        // 限制数量
        if (history.size > MAX_HISTORY_SIZE) {
            history.subList(MAX_HISTORY_SIZE, history.size).clear()
        }
        
        // 保存
        saveSearchHistory(history)
    }
    
    fun getSearchHistory(): List<String> {
        val historyString = prefs.getString(KEY_HISTORY, "") ?: ""
        return if (historyString.isEmpty()) {
            emptyList()
        } else {
            historyString.split("|").filter { it.isNotBlank() }
        }
    }
    
    fun clearSearchHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
    
    private fun saveSearchHistory(history: List<String>) {
        val historyString = history.joinToString("|")
        prefs.edit().putString(KEY_HISTORY, historyString).apply()
    }
}
