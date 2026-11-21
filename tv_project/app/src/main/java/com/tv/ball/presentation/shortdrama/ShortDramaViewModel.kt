package com.tv.ball.presentation.shortdrama

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧列表页。
 *
 * 取数全部交给 [ShortDramaRepository]（苹果 CMS 采集源）；原先那套红果短剧接口
 * api-v2.cenguigui.cn 已经整站下线，所以这里不再有任何写死的第三方 URL。
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

    private var currentCategory = ShortDramaRepository.tabs.first().label
    private var currentPage = 1
    private var isLoadingMore = false

    fun loadDramas(category: String, page: Int, refresh: Boolean = false) {
        Log.d(TAG, "loadDramas: category=$category, page=$page, refresh=$refresh")

        if (refresh) {
            currentPage = 1
            _dramas.value = emptyList()
        }
        if (isLoadingMore) {
            Log.d(TAG, "Already loading, skipping")
            return
        }

        currentCategory = category
        isLoadingMore = true
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val items = ShortDramaRepository.fetchList(category, page)
                if (items.isEmpty()) {
                    if (page <= 1) {
                        _error.value = "该分类暂时没有短剧"
                    }
                    // 第 2 页起为空 = 到底了，什么都不做，避免无限翻页
                } else {
                    _dramas.value = if (refresh || page <= 1) items else _dramas.value + items
                    currentPage = page + 1
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadDramas failed: ${e.message}", e)
                _error.value = e.message ?: "短剧源请求失败"
            } finally {
                _isLoading.value = false
                isLoadingMore = false
            }
        }
    }

    fun loadMore() {
        if (!isLoadingMore && !_isLoading.value) {
            loadDramas(currentCategory, currentPage, refresh = false)
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