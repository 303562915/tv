package com.tv.ball.presentation.shortdrama

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧列表页。
 *
 * 两种取数：分类 Tab（含整库刷）与站内搜索关键词，都走 [ShortDramaRepository]
 * （苹果 CMS 采集源）。原先那套红果短剧接口 api-v2.cenguigui.cn 已整站下线，
 * 这里不再有任何写死的第三方 URL。
 */
class ShortDramaViewModel : ViewModel() {

    companion object {
        private const val TAG = "ShortDramaViewModel"
    }

    private val _dramas = MutableStateFlow<List<ShortDramaItem>>(emptyList())
    val dramas: StateFlow<List<ShortDramaItem>> = _dramas.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 正在搜索的关键词；为空表示在按分类浏览 */
    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    private var currentCategory = ShortDramaRepository.tabs.first().label
    private var currentPage = 1
    private var isLoadingMore = false
    private var loadJob: Job? = null

    fun loadDramas(category: String, page: Int, refresh: Boolean = false) {
        currentCategory = category
        _searchKeyword.value = ""
        load(page, refresh)
    }

    /** 搜索框提交。空关键词等于退出搜索，回到当前分类。 */
    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            exitSearch()
            return
        }
        _searchKeyword.value = kw
        load(1, refresh = true)
    }

    fun exitSearch() {
        if (_searchKeyword.value.isEmpty()) return
        _searchKeyword.value = ""
        load(1, refresh = true)
    }

    fun loadMore() {
        if (!isLoadingMore && !_isLoading.value) {
            load(currentPage, refresh = false)
        }
    }

    private fun load(page: Int, refresh: Boolean) {
        if (refresh) {
            currentPage = 1
            _dramas.value = emptyList()
        }
        if (isLoadingMore && !refresh) {
            Log.d(TAG, "Already loading, skipping")
            return
        }

        isLoadingMore = true
        _isLoading.value = true
        _error.value = null

        val keyword = _searchKeyword.value
        val category = currentCategory
        // 搜索时把上一次的在途请求掐掉，避免旧结果盖掉新结果
        if (refresh) loadJob?.cancel()

        loadJob = viewModelScope.launch {
            try {
                val items = if (keyword.isNotEmpty()) {
                    ShortDramaRepository.search(keyword, page)
                } else {
                    ShortDramaRepository.fetchList(category, page)
                }

                if (items.isEmpty()) {
                    if (page <= 1) {
                        _error.value = if (keyword.isNotEmpty()) "没搜到「$keyword」相关的短剧" else "该分类暂时没有短剧"
                    }
                    // 第 2 页起为空 = 到底了，不再继续翻页
                } else {
                    _dramas.value = if (refresh || page <= 1) items else _dramas.value + items
                    currentPage = page + 1
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "load failed: ${e.message}", e)
                _error.value = e.message ?: "短剧源请求失败"
            } finally {
                _isLoading.value = false
                isLoadingMore = false
            }
        }
    }
}

data class ShortDramaItem(
    val bookId: String,
    val title: String,
    val author: String,
    val type: String,
    val playCnt: String,
    val episodeCnt: String,
    val cover: String,
    val intro: String
)