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

    /**
     * 后面还有没有数据。
     *
     * 为什么要有这个状态：仓库里采集源一页固定 20 条，如果只按「这次拿回几条 < 20」推到底，
     * 最后一页恰好是满 20 条时列表页推不出来，用户必须先滚到底打一次空请求才看到「没有更多了」。
     * 这里在请求侧就知道答案（某页返回空 = 到底），所以直接暴露出去给界面用。
     */
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var currentCategory = ShortDramaRepository.tabs.first().label
    private var currentPage = 1
    private var isLoadingMore = false
    private var loadJob: Job? = null

    /** 已经翻到底（某页返回空）就不再发请求，避免滚动到底后反复重打同一页 */
    private var endReached = false

    /**
     * 在途请求去重表：同 mode+keyword+page 只发一次。
     * 列表页滚动监听和下拉刷新经常在同一帧里连着触发，没有这张表就会对同一页发两三个请求，
     * 结果重复追加到列表里。refresh 走的是「取消旧的 + 重新发」，不受这里限制。
     */
    private val inFlight = mutableSetOf<String>()

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

    /**
     * 消费掉当前错误。
     *
     * 为什么需要它：error 是 StateFlow<String?>，StateFlow 不会因为「被读过」就自己清空，
     * 所以旋转屏幕重新 collect、或从详情页返回列表页重新订阅，同一条旧错误会再弹一次 Toast。
     * 弹过的调用方负责把它清掉。
     *
     * 注意：这里只清值，不改 error 的类型（ShortDramaFragment 也在收集 viewModel.error，
     * 改成别的类型那边编译不过）。
     */
    fun consumeError() {
        _error.value = null
    }

    fun loadMore() {
        if (!isLoadingMore && !_isLoading.value) {
            load(currentPage, refresh = false)
        }
    }

    private fun load(page: Int, refresh: Boolean) {
        val keyword = _searchKeyword.value
        val category = currentCategory
        val mode = if (keyword.isNotEmpty()) "search" else "tab:$category"
        val requestKey = "$mode|$keyword|$page"

        if (refresh) {
            // 刷新：掐掉在途请求，再清掉这个 mode+keyword 的页缓存，
            // 否则刷新后拿到的还是 Repository 里缓存的旧页
            loadJob?.cancel()
            currentPage = 1
            _dramas.value = emptyList()
            endReached = false
            // 新的一轮取数开始：上一轮「到底」的结论只对上一轮有效，先恢复成「还有更多」，
            // 否则换了分类/关键词之后分页会被旧的到底状态挡住
            _hasMore.value = true
            if (keyword.isEmpty()) {
                ShortDramaRepository.invalidateTabCache(category)
            } else {
                ShortDramaRepository.invalidateSearchCache(keyword)
            }
        } else {
            if (isLoadingMore || _isLoading.value) {
                Log.d(TAG, "已有请求在途，跳过 $requestKey")
                return
            }
            if (requestKey in inFlight) {
                Log.d(TAG, "同一页已在途，跳过 $requestKey")
                return
            }
            if (endReached) {
                Log.d(TAG, "已翻到底，跳过 $requestKey")
                return
            }
        }

        isLoadingMore = true
        _isLoading.value = true
        _error.value = null
        inFlight += requestKey

        val job = viewModelScope.launch {
            // 记住自己是谁：被 cancel 掉的旧 job 的 finally 不能把新请求的 loading 状态清掉
            val self = coroutineContext[Job]
            try {
                val items = if (keyword.isNotEmpty()) {
                    ShortDramaRepository.search(keyword, page)
                } else {
                    ShortDramaRepository.fetchList(category, page)
                }

                if (items.isEmpty()) {
                    if (page <= 1) {
                        _error.value = if (keyword.isNotEmpty()) "没搜到「$keyword」相关的短剧" else "该分类暂时没有短剧"
                        // 首页就没数据：后面也不会有，直接标记没有更多
                        _hasMore.value = false
                    } else {
                        // 第 2 页起为空 = 到底了（也可能是采集源翻页无效，Repository 那边已按空返回）
                        endReached = true
                        // 只有「这一页真的没数据」才置 false；不用「条数 < 20」去猜，
                        // 最后一页恰好满 20 条的那种情况猜不出来
                        _hasMore.value = false
                    }
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
                inFlight -= requestKey
                if (loadJob === self) {
                    _isLoading.value = false
                    isLoadingMore = false
                }
            }
        }
        loadJob = job
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
    val intro: String,
    /** 年份，采集源没有这个字段时为空串 */
    val year: String = "",
    /** 评分，采集源没有这个字段时为空串 */
    val score: String = "",
    /** 是否完结（vod_isend=1 或备注里带「完结」） */
    val finished: Boolean = false
)