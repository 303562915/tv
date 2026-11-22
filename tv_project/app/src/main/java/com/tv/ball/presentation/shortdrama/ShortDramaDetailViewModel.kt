package com.tv.ball.presentation.shortdrama

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧详情页。
 *
 * 分集播放地址在详情接口里就有（采集源的 vod_play_url 就是 m3u8 直链），
 * 所以这里不再有「点一集再去问接口要地址」那一步，点开即播。
 *
 * 播放源切换也放在这里：详情接口一次就把所有源的分集都带回来了（sources），
 * 切源只做内存里的列表替换，不再走网络——否则点一下源又要转圈，体验比不切更差。
 */
class ShortDramaDetailViewModel : ViewModel() {

    companion object {
        private const val TAG = "ShortDramaDetailVM"

        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L // 5分钟缓存

        /**
         * 缓存最多留 8 部剧。
         *
         * 为什么要有上限：companion 里的 map 活得比 ViewModel 久（进程不退就不回收），
         * 而一条详情数据里挂着「所有线路 × 每线路全部分集」，一部剧轻松几百个 EpisodeItem。
         * 不设上限的话，用户连着刷几十部剧就是几十份这样的对象永远躺着——这就是纯粹的内存泄漏。
         */
        private const val CACHE_MAX_ENTRIES = 8

        /** 缓存锁：详情请求跑在 viewModelScope 上、切换/重试也可能来自别处，读写都可能并发 */
        private val cacheLock = Any()

        /** 缓存条目：数据和写入时间绑在一起，淘汰时不会出现「有数据没时间」的孤儿 */
        private class CacheEntry(val data: ShortDramaDetailData, val timestamp: Long)

        /**
         * 详情缓存（LRU × 时间戳）。
         *
         * accessOrder = true：读一次就把该条挪到队尾，[removeEldestEntry] 淘汰的是队头，
         * 也就是**最久没被用过**的那部剧，而不是「最早写进来的先死」——用户来回看同一部剧时
         * 不会因为别的剧插队就把它挤掉。
         *
         * 原来拆成 detailCache + cacheTimestamps 两个 map，两边要手工同步：过期只删一边、
         * 淘汰只顾一边，就会留下只有数据没有时间（或反过来）的条目，读的时候还得判空兜底。
         * 合成一条 entry 之后「过期」和「淘汰」都只有一处真相。
         */
        private val detailCache = object : LinkedHashMap<String, CacheEntry>(CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
                size > CACHE_MAX_ENTRIES
        }

        /** 取缓存：命中且未过期才返回；过期的读到就顺手剔掉，不留死数据占容量 */
        private fun cachedDetail(bookId: String): ShortDramaDetailData? = synchronized(cacheLock) {
            val entry = detailCache[bookId] ?: return@synchronized null
            if (System.currentTimeMillis() - entry.timestamp >= CACHE_EXPIRY_MS) {
                detailCache.remove(bookId)
                return@synchronized null
            }
            entry.data
        }

        /** 写缓存；超出上限时由 removeEldestEntry 自动淘汰最久未使用的一条 */
        private fun cacheDetail(bookId: String, data: ShortDramaDetailData) {
            synchronized(cacheLock) {
                detailCache[bookId] = CacheEntry(data, System.currentTimeMillis())
            }
        }

        /** 手动剔除某部剧的缓存（强制刷新 / 失败重试时用） */
        private fun dropCache(bookId: String) {
            synchronized(cacheLock) { detailCache.remove(bookId) }
        }
    }

    private val _detail = MutableStateFlow<ShortDramaItem?>(null)
    val detail: StateFlow<ShortDramaItem?> = _detail.asStateFlow()

    private val _episodes = MutableStateFlow<List<EpisodeItem>>(emptyList())
    val episodes: StateFlow<List<EpisodeItem>> = _episodes.asStateFlow()

    /** 全部播放源；只有一个（或没有）源时 UI 不显示切源那一排 */
    private val _sources = MutableStateFlow<List<ShortDramaPlaySource>>(emptyList())
    val sources: StateFlow<List<ShortDramaPlaySource>> = _sources.asStateFlow()

    /** 当前选中的源下标，和 _episodes 永远同步 */
    private val _selectedSourceIndex = MutableStateFlow(0)
    val selectedSourceIndex: StateFlow<Int> = _selectedSourceIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 加载详情。
     *
     * @param forceRefresh 强制走网络并清掉该剧的缓存；下拉刷新 / 手动重试用得到。
     *                     默认 false 时行为和以前完全一样（5 分钟内命中缓存直接下发）。
     */
    fun loadDetail(bookId: String, forceRefresh: Boolean = false) {
        Log.d(TAG, "loadDetail: bookId=$bookId, forceRefresh=$forceRefresh")

        // 能不能吃缓存必须在清 _error 之前定下来：上一次请求失败了（_error 非空）说明用户
        // 就是在点重试，这时候再拿 5 分钟前的同一份缓存喂回去，等于「点重试没有任何反应」。
        // 顺序反过来的话 _error 已经被清空，这里永远判断成 true，重试永远拿不到新数据。
        val useCache = !forceRefresh && _error.value == null

        // 先把 error 清掉再往下走：清 error 和置 loading 在同一个同步区间里完成，
        // UI 不会先闪一帧「有错误」再切到「加载中」。缓存命中时也只是补一句 loading=false，
        // 不会出现「转圈转了一半又回到旧数据」。
        _error.value = null
        if (forceRefresh) dropCache(bookId)

        if (useCache) {
            val cached = cachedDetail(bookId)
            if (cached != null) {
                Log.d(TAG, "⚡ 使用缓存数据")
                applyData(cached)
                _isLoading.value = false
                return
            }
        }

        _isLoading.value = true

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val data = ShortDramaRepository.fetchDetail(bookId)
                val cost = System.currentTimeMillis() - startTime
                Log.d(TAG, "⏱️ 详情加载完成，耗时 ${cost}ms，${data.episodes.size} 集，${data.sources.size} 个播放源")

                applyData(data)
                cacheDetail(bookId, data)
            } catch (e: Exception) {
                val cost = System.currentTimeMillis() - startTime
                Log.e(TAG, "⏱️ 详情加载失败，耗时 ${cost}ms: ${e.message}", e)
                _error.value = e.message ?: "短剧详情请求失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 统一下发一份详情数据：detail / episodes / sources / 选中源 一起对齐，
     * 免得 UI 层收到「详情换了但分集还是旧的」这种中间态。
     */
    private fun applyData(data: ShortDramaDetailData) {
        // 重新加载时不要无脑回到 0 号源：用户上一条线路可能是手动选的（比如 0 号源卡顿才切走），
        // 刷新一下又跳回去，等于把选择吞了。
        // 按**源名**找回，不能按下标：采集源每次返回的线路顺序并不稳定，按下标会恢复成另一条线路。
        val previousName = _sources.value.getOrNull(_selectedSourceIndex.value)?.name
        val restoredIndex = previousName
            ?.let { name -> data.sources.indexOfFirst { it.name == name && it.episodes.isNotEmpty() } }
            ?.takeIf { it >= 0 }
            ?: 0 // 首次加载 / 旧线路已经没了：回 0 号源（和以前一致）

        _detail.value = data.detail
        _sources.value = data.sources
        // 先定分集、再定下标（和 selectSource 同一个取舍，见那里的注释）
        _episodes.value = episodesOf(data, restoredIndex)
        _selectedSourceIndex.value = restoredIndex
    }

    /**
     * 第 [index] 条线路的分集（按集号排序）。
     *
     * 第一源没有分集时退回 data.episodes：那是老接口「只给第一源分集」的兼容路径，
     * 其它线路没分集就是真的没有，不能拿第一源的列表去冒充。
     */
    private fun episodesOf(data: ShortDramaDetailData, index: Int): List<EpisodeItem> {
        val own = data.sources.getOrNull(index)?.episodes.orEmpty()
        val fallback = if (index == 0) data.episodes else emptyList()
        return own.ifEmpty { fallback }.sortedBy { it.episodeNum }
    }

    /**
     * 切换播放源。直接取已加载的 sources，不发请求。
     */
    fun selectSource(index: Int) {
        val sourceList = _sources.value
        if (index !in sourceList.indices) return
        if (index == _selectedSourceIndex.value) return

        val target = sourceList[index]
        if (target.episodes.isEmpty()) {
            // 空源切过去只会得到一片空白，保持原样更安全
            Log.w(TAG, "播放源 ${target.name} 没有分集，忽略切换")
            return
        }

        Log.d(TAG, "切换播放源 -> ${target.name}（${target.episodes.size} 集）")
        // 两个 StateFlow 没法原子赋值，中间必然有一次 emit。取舍：先 episodes、后 index——
        // 最坏让 UI 看到一帧「新分集 + 旧高亮」，这比反过来（新高亮 + 旧分集）安全得多：
        // 后者正好是「用户点第 3 条线路，列表还是第 1 条的分集」，一旦此时起播就是拿错集去播。
        _episodes.value = target.episodes.sortedBy { it.episodeNum }
        _selectedSourceIndex.value = index
    }

    /** 分集地址详情接口已经给了，直接透传，不再发请求。 */
    @Deprecated(
        message = "详情接口已带直链，直接读 EpisodeItem.videoUrl",
        replaceWith = ReplaceWith("")
    )
    fun getVideoUrl(videoId: String, callback: (String) -> Unit) {
        callback(ShortDramaRepository.resolvePlayUrl(videoId))
    }
}

data class EpisodeItem(
    val videoId: String,
    val episodeNum: Int,
    val videoUrl: String
)
