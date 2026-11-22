package com.tv.ball.presentation.shortdrama

import android.util.Log
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 短剧数据源。
 *
 * 为什么不再用 api-v2.cenguigui.cn
 * ---------------------------------
 * 那套红果短剧接口已经整站下线：api-v2.cenguigui.cn 连 A 记录都没有（DNS 只有 SOA），
 * api.cenguigui.cn 直接回 Cloudflare 521（源站不可用）。所以首页一进就是「网络错误」，
 * 这不是 App 的问题，是接口没了。
 *
 * 现在的取数方式
 * --------------
 * 复用本项目「影视聚合」里已经在用的苹果 CMS 采集源（ac=videolist / ac=detail）：
 * - 列表：整库刷走采集源自己的短剧分类（360 源有 type 46「爽文短剧」，两万多条），
 *   分类 Tab 走关键词搜索（wd=），一次 20 条，翻页即「一直刷」。
 * - 详情：ac=detail&ids= 拿 vod_play_url，直接就是 m3u8 直链，不需要二次解析。
 * - 播放：直链直接交给播放器，不再经过任何第三方解析接口。
 */
object ShortDramaRepository {

    private const val TAG = "ShortDramaRepo"

    /** 采集源。base 形如 https://xxx/api.php/provide/vod/?ac=videolist */
    data class Provider(
        val key: String,
        val name: String,
        val base: String,
        /** 该源的「短剧」分类 id；有值就能整库刷，没有就只能靠关键词 */
        val dramaType: String? = null
    )

    private val providers = listOf(
        Provider("360zy", "360短剧库", "https://360zy5.com/api.php/provide/vod/?ac=videolist", "46"),
        Provider("iqiyi", "爱奇艺接口", "https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist"),
        Provider("hongniu", "红牛资源", "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist")
    )

    /**
     * 顶部 Tab。keyword 为空 + wholeLibrary 为 true = 整库刷（优先走短剧分类，没有就走「短剧」关键词）。
     * 关键词都实测有货（数量从几十到几百），别换成没内容的词。
     */
    data class Tab(val label: String, val keyword: String, val wholeLibrary: Boolean = false)

    val tabs = listOf(
        Tab("爽文短剧", "", true),
        Tab("霸总", "总裁"),
        Tab("逆袭", "逆袭"),
        Tab("穿越", "穿越"),
        Tab("闪婚", "闪婚"),
        Tab("复仇", "复仇"),
        Tab("千金", "千金"),
        Tab("归来", "归来"),
        Tab("战神", "战神"),
        Tab("萌宝", "萌宝")
    )

    private val client: NetworkDataSource by lazy {
        val ok = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        NetworkDataSource(ok, Dependencies.context)
    }

    /**
     * 竞速专用作用域。
     *
     * 为什么故意不挂在调用方的 Job 下面（不用 coroutineScope）：
     * cancel() 只能让协程在下一个挂起点退出，而 [NetworkDataSource.get] 内部是 OkHttp 的阻塞调用，
     * 取消并不会中断它 —— 那个子协程必须等阻塞调用自己跑到 timeout 才算真正结束。
     * 如果用 coroutineScope，它必须等**所有**子协程结束才返回，于是变成「第一个源 1s 就回来了，
     * 用户仍要等最慢那个源跑完 20s」，比原来顺序试还慢。
     * 用独立作用域 + SupervisorJob：拿到第一个可用结果就返回，输掉的那些自己跑完自己的超时，
     * 谁抛异常也只影响它自己。
     */
    private val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun headers(p: Provider): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to p.base.substringBefore("/api.php")
    )

    // ---------------- 源健康记忆 ----------------
    //
    // 为什么要在内存里记住「哪个源最近不靠谱」：
    // 三个采集源里经常有一个整体挂掉（换域名 / 443 被墙 / 源站 502）。原来的写法是每个请求
    // 都先撞一遍坏源，超时 10s 起步，用户点一次分类要等十几秒才出内容。连续失败 3 次的源
    // 降级 10 分钟排到末尾，等于让它在一次会话里只「试一次」；10 分钟后自动放回来重新探测，
    // 不写盘，避免把一次临时抽风永久记成不可用。

    /** 连续失败达到这个次数就临时降级 */
    private const val FAIL_THRESHOLD = 3

    /** 降级时长 */
    private const val DEMOTE_WINDOW_MS = 10 * 60 * 1000L

    /**
     * health 表的容量上限。
     * 现在只有 3 个源，正常永远撞不到；加这个护栏是因为它是 object 级 map，
     * 源列表一旦改成动态的（换域名 / 从配置读），长会话里就会无限长下去。
     */
    private const val HEALTH_LIMIT = 8

    private class Health(var failures: Int = 0, var demotedUntil: Long = 0L)

    private val healthLock = Any()
    private val health = HashMap<String, Health>()

    private fun markProviderSuccess(providerKey: String) {
        synchronized(healthLock) { health.remove(providerKey) }
    }

    private fun markProviderFailure(providerKey: String) {
        synchronized(healthLock) {
            // 超上限就整表清空：比逐个淘汰简单，代价只是丢掉历史失败计数，
            // 最坏结果是某个坏源被多试一次，不影响当前这次请求的结果
            if (!health.containsKey(providerKey) && health.size >= HEALTH_LIMIT) health.clear()
            val h = health.getOrPut(providerKey) { Health() }
            h.failures++
            if (h.failures >= FAIL_THRESHOLD) {
                h.demotedUntil = System.currentTimeMillis() + DEMOTE_WINDOW_MS
                // 计数清零：降级期内不再累加，到期后重新从 0 开始观察
                h.failures = 0
                Log.w(TAG, "$providerKey 连续失败 $FAIL_THRESHOLD 次，降级 ${DEMOTE_WINDOW_MS / 1000}s")
            }
        }
    }

    private fun isDemoted(providerKey: String, now: Long): Boolean = synchronized(healthLock) {
        val h = health[providerKey]
        h != null && h.demotedUntil > now
    }

    /**
     * 健康的源保持原顺序在前，降级源整体挪到末尾。
     * 全部降级时按原顺序试 —— 全挂说明可能是本地网络的问题，这时「跳过」只会让用户看到空列表。
     */
    private fun orderCandidates(candidates: List<Provider>): List<Provider> {
        val now = System.currentTimeMillis()
        val healthy = ArrayList<Provider>(candidates.size)
        val demoted = ArrayList<Provider>(candidates.size)
        for (p in candidates) {
            if (isDemoted(p.key, now)) demoted.add(p) else healthy.add(p)
        }
        return if (healthy.isEmpty()) candidates else healthy + demoted
    }

    // ---------------- 页缓存（最近 3 页 LRU） ----------------
    //
    // 为什么缓存：整库刷一页 20 条，用户「返回 → 再点同一个分类」会重新打一次采集源，
    // 而这类源对同一页的响应基本不变。只留最近 3 页，够覆盖「上一页 / 当前页 / 下一页」
    // 这种来回翻的动作，又不会把几十页数据长驻内存。空列表不入缓存：空页代表「到底了」，
    // 缓存住会让源恢复后也刷不出东西。

    private const val CACHE_PAGE_LIMIT = 3

    private val cacheLock = Any()

    /** accessOrder = true，keys 的第一个即最久未用，用来做 LRU 淘汰 */
    private val pageCache = LinkedHashMap<String, List<ShortDramaItem>>(8, 0.75f, true)

    /**
     * 缓存 key = `mode|请求指纹|page`。
     *
     * 第二个字段不再用「关键词」而是用指纹：整库刷时带 dramaType 的源发的是 `t=46`，
     * 不带的源发的是 `wd=短剧`，两种请求的响应内容完全不同，共用一个键会互相串味
     * （先命中 t=46 的缓存，再换成关键词的源就永远拿不到自己的数据）。指纹见 [requestSignature]。
     */
    private fun cacheKey(mode: String, signature: String, page: Int): String = "$mode|$signature|$page"

    /**
     * 请求指纹：整库刷带分类 id 的源按 t=<id> 取数，其余按 wd=<关键词>，
     * 二者响应不同，缓存 key 必须区分开，否则会互相串味。
     */
    private fun requestSignature(tab: Tab, isSearch: Boolean): String {
        if (isSearch) return "wd:${tab.keyword}"
        return if (tab.wholeLibrary) {
            val type = providers.firstNotNullOfOrNull { it.dramaType } ?: ""
            if (type.isNotEmpty()) "t:$type" else "wd:${effectiveKeyword(tab)}"
        } else {
            "wd:${effectiveKeyword(tab)}"
        }
    }

    private fun cachedPage(key: String): List<ShortDramaItem>? =
        synchronized(cacheLock) { pageCache[key] }

    private fun putPage(key: String, items: List<ShortDramaItem>) {
        if (items.isEmpty()) return
        synchronized(cacheLock) {
            pageCache[key] = items
            // size > 3 时 keys 一定非空，first() 就是最久未用的那一页
            while (pageCache.size > CACHE_PAGE_LIMIT) {
                pageCache.remove(pageCache.keys.first())
            }
        }
    }

    /**
     * 按前缀清缓存。传进来的前缀必须到第二个分隔符为止（`mode|指纹|`）：
     * mode 相同但指纹不同的页属于不同请求，只按 `mode|` 清会把别的分类的缓存一起误清。
     */
    private fun clearCachePrefix(prefix: String) {
        synchronized(cacheLock) {
            val snapshot = pageCache.keys.filter { it.startsWith(prefix) }
            snapshot.forEach { pageCache.remove(it) }
        }
    }

    /**
     * 刷新列表页时调用：第 1 页重取后，这个分类该指纹下的几页缓存和翻页状态一起作废。
     * seenIds 必须跟着清，否则「重新拉到了第 1 页」之后比对用的还是上一轮的已见集合。
     */
    fun invalidateTabCache(categoryLabel: String) {
        val tab = tabOf(categoryLabel)
        val mode = modeOf(tab)
        clearCachePrefix("$mode|${requestSignature(tab, isSearch = false)}|")
        forgetSeen(mode)
    }

    /** 刷新搜索结果时调用 */
    fun invalidateSearchCache(keyword: String) {
        val tab = Tab("搜索", keyword.trim())
        clearCachePrefix("search|${requestSignature(tab, isSearch = true)}|")
        forgetSeen("search")
    }

    // ---------------- 翻页有效性 ----------------
    //
    // 采集源经常忽略 pg 参数：第 2 页回的还是第 1 页那 20 条。继续追加就是满屏重复，
    // 所以按 mode 记住「已经返回过的 bookId」，整页都在里面就当成「翻页无效」返回空，
    // 由上层按「到底」处理。状态放 object，滚动加载跨回调也能对上。
    //
    // 为什么不再只看「本页首条和上一页首条是否相同」：源只要调一次排序，首条就会变，
    // 于是重复页被当成正常页塞给 UI、或者正常页被误判成「到底」直接丢掉。
    // 改成整页比对后，只要这一页还有没见过的条目就算有效页。

    private val pageLock = Any()

    /** mode → 这一轮已经返回过的 bookId。mode 见 [modeOf]，搜索统一是 "search" */
    private val seenIds = HashMap<String, MutableSet<String>>()

    /** seenIds 的 mode 数量上限：按关键词搜索时 mode 只有 "search"，但分类标签是可变的，兜个底 */
    private const val SEEN_MODE_LIMIT = 64

    private fun isPagingBroken(mode: String, page: Int, items: List<ShortDramaItem>): Boolean {
        if (page <= 1 || items.isEmpty()) return false
        return synchronized(pageLock) {
            val seen = seenIds[mode] ?: return@synchronized false
            items.all { it.bookId in seen }
        }
    }

    private fun rememberPage(mode: String, page: Int, items: List<ShortDramaItem>) {
        if (items.isEmpty()) return
        synchronized(pageLock) {
            if (!seenIds.containsKey(mode) && seenIds.size >= SEEN_MODE_LIMIT) seenIds.clear()
            // 第 1 页代表「这一轮从头开始」（下拉刷新 / 换关键词），旧的已见集合必须丢掉：
            // 否则新一轮的正常内容会被上一轮攒下的 bookId 判成「翻页无效」，直接把结果吞掉。
            val set = if (page <= 1) {
                HashSet<String>(items.size * 2).also { seenIds[mode] = it }
            } else {
                seenIds.getOrPut(mode) { HashSet() }
            }
            items.forEach { set.add(it.bookId) }
        }
    }

    /** 分类刷新时把该 mode 的翻页记录一起丢掉（缓存清了、状态还在就会误判到底） */
    private fun forgetSeen(mode: String) {
        synchronized(pageLock) { seenIds.remove(mode) }
    }

    private fun modeOf(tab: Tab): String = if (tab.wholeLibrary) "tab" else "tab:${tab.label}"

    private fun effectiveKeyword(tab: Tab): String = if (tab.wholeLibrary) "短剧" else tab.keyword

    private fun tabOf(categoryLabel: String): Tab =
        tabs.firstOrNull { it.label == categoryLabel } ?: tabs.first()

    // ---------------- 对外接口 ----------------

    /** 列表/搜索。同一页在多个源之间失败互备，全挂才抛错。 */
    suspend fun fetchList(categoryLabel: String, page: Int): List<ShortDramaItem> {
        val tab = tabOf(categoryLabel)
        return fetch(tab, page, isSearch = false)
    }

    /** 关键词搜索：站内搜索框走这里，仍然是多源互备。 */
    suspend fun search(keyword: String, page: Int): List<ShortDramaItem> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return fetch(Tab("搜索", kw), page, isSearch = true)
    }

    /** 单个源的尝试结果：并行竞速时每个 async 都返回这个，不抛异常 */
    private sealed interface Attempt {
        data class Ok(val items: List<ShortDramaItem>) : Attempt
        data class Fail(val message: String) : Attempt
        data class Empty(val message: String?) : Attempt
        /** 源没理 pg 参数（翻页无效）→ 直接按「到底」处理 */
        data object PagingBroken : Attempt
    }

    /**
     * 单个源的一次尝试。
     *
     * 所有失败都必须翻译成 [Attempt] 返回，绝不往外抛：竞速时任何一个源抛异常都会沿
     * `onAwait` 把整个 select 打断，等于让其它还活着的源白等一场。
     */
    private suspend fun attempt(
        p: Provider,
        tab: Tab,
        page: Int,
        mode: String
    ): Attempt {
        val url = buildListUrl(p, tab, page)
        return when (val result = client.get(url, headers(p))) {
            is NetworkResult.Success -> {
                val parsed = runCatching { parseList(result.data, p) }
                val items = parsed.getOrNull()
                if (items == null) {
                    val rawMessage = parsed.exceptionOrNull()?.message
                    Log.e(TAG, "解析列表失败 provider=${p.key} raw=$rawMessage")
                    markProviderFailure(p.key)
                    Attempt.Fail(translateError(p.key, rawMessage))
                } else if (items.isNotEmpty()) {
                    markProviderSuccess(p.key)
                    if (isPagingBroken(mode, page, items)) {
                        Log.w(TAG, "${p.key} 第${page}页与已返回内容重复，判定翻页无效（${tab.label}）")
                        Attempt.PagingBroken
                    } else {
                        // 这里只「认领」不记账：已见集合只记真正交给 UI 的那一页（见 fetch），
                        // 否则三个源并发时会把彼此的 bookId 混进同一个集合，下一页容易被误判成重复
                        Log.d(TAG, "${p.key} 第${page}页 ${items.size} 条（${tab.label}）")
                        Attempt.Ok(items)
                    }
                } else if (tab.wholeLibrary && page > 1) {
                    Attempt.PagingBroken   // 整库刷第 2 页起为空 = 到底了
                } else {
                    // 空页不算源失败：多半是这个关键词在这个源没内容
                    Attempt.Empty(readMessage(result.data))
                }
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "请求失败 provider=${p.key} raw=${result.message}")
                markProviderFailure(p.key)
                Attempt.Fail(translateError(p.key, result.message, result.exception))
            }
        }
    }

    /**
     * 列表/搜索的公共取数：多个源**并行竞速**，谁先给出可用结果就用谁，其余立刻取消等待。
     *
     * 为什么不再顺序试：原来 for 循环一个源一个源地撞，单源 connectTimeout 10s、callTimeout 20s，
     * 三个源全挂要 30s 以上才报错，用户点一次分类就是干等。
     * 并行之后：有源成功就是「最快那个源的响应时间」；三个都挂也只需等最慢那个自己的超时。
     *
     * 注意 cancel 只取消我们对它的等待：OkHttp 那条阻塞请求会自己跑到 timeout 才释放线程，
     * 那时我们已经拿着别的源的结果返回了，所以不影响用户等的时间，也不需要额外清理。
     * 竞速用的作用域见 [raceScope]：它故意不挂在调用方 Job 下，否则还是要等输掉的那些源跑完。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetch(tab: Tab, page: Int, isSearch: Boolean): List<ShortDramaItem> {
        // 搜索的 mode 和分类分开，否则「搜索 逆袭」会和「逆袭 Tab」共用缓存/翻页状态
        val mode = if (isSearch) "search" else modeOf(tab)
        val key = cacheKey(mode, requestSignature(tab, isSearch), page)
        cachedPage(key)?.let {
            Log.d(TAG, "命中页缓存 $key(${it.size} 条)")
            // 缓存命中也补记一次已见集合，否则「第 1 页来自缓存 → 翻第 2 页」时没有可比对的基准
            rememberPage(mode, page, it)
            return it
        }

        // 整库刷优先用带短剧分类的源，其余源作为关键词兜底
        val raw = if (tab.wholeLibrary) {
            providers.filter { it.dramaType != null } + providers.filter { it.dramaType == null }
        } else {
            providers
        }
        val candidates = orderCandidates(raw)

        // 三个源同时发车，谁先给出可用结果就定谁；输掉的下面的 finally 里 cancel
        val jobs = candidates.map { p ->
            p to raceScope.async(start = CoroutineStart.DEFAULT) { attempt(p, tab, page, mode) }
        }
        var lastError: String? = null
        var winner: List<ShortDramaItem>? = null
        try {
            while (true) {
                val pending = jobs.filter { it.second.isActive }
                if (pending.isEmpty()) break
                val result = select<Attempt> {
                    pending.forEach { (_, job) -> job.onAwait { it } }
                }
                when (result) {
                    // 第一个可用结果直接定胜负：剩下的源不再等，finally 里统一 cancel
                    is Attempt.Ok -> {
                        winner = result.items
                        break
                    }
                    // 某个源说「翻页无效」，按到底处理（其它源还没来得及回，就当它也到底）
                    is Attempt.PagingBroken -> {
                        winner = emptyList()
                        break
                    }
                    is Attempt.Fail -> lastError = result.message
                    is Attempt.Empty -> lastError = result.message ?: lastError
                }
            }
        } finally {
            // 取消的只是「我们对它的等待」：阻塞中的 OkHttp 请求会自己跑到 timeout
            jobs.forEach { (_, job) -> if (job.isActive) job.cancel() }
        }
        // 空列表由 putPage 内部挡掉（那里有 if (items.isEmpty()) return）：
        // 空页代表「到底」，缓存住会让源恢复之后也刷不出东西
        val items = winner ?: throw ShortDramaException(lastError ?: "短剧源返回格式异常")
        // 只把真正交给 UI 的这页记进已见集合：并发的几个源各记一份会互相污染
        rememberPage(mode, page, items)
        putPage(key, items)
        return items
    }

    /** 详情 + 分集。bookId 形如 "360zy:110377" */
    suspend fun fetchDetail(bookId: String): ShortDramaDetailData {
        val providerKey = bookId.substringBefore(":", "")
        val vodId = bookId.substringAfter(":", bookId)
        val provider = providers.firstOrNull { it.key == providerKey }
            ?: throw ShortDramaException("不认识的短剧来源: $providerKey")

        val url = provider.base.replace("ac=videolist", "ac=detail") + "&ids=" + vodId
        val result = client.get(url, headers(provider))
        val json: String = when (result) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> {
                markProviderFailure(providerKey)
                throw ShortDramaException(translateError(providerKey, result.message, result.exception))
            }
        }

        val obj = runCatching {
            JSONObject(json).optJSONArray("list")?.optJSONObject(0)
        }.onFailure {
            Log.e(TAG, "详情解析失败 provider=$providerKey raw=${it.message}")
        }.getOrNull() ?: throw ShortDramaException(readMessage(json) ?: "短剧源返回格式异常")

        // 一个 vod_play_url 里常常挂着多条线路（$$$ 分隔），全部解析出来交给 UI 备用
        val sources = parsePlaySources(obj)
        if (sources.isEmpty()) throw ShortDramaException("这部剧没有可用播放地址")

        // episodes 语义保持不变：永远是第一播放源的分集，老调用方不用改
        val episodes = sources.first().episodes

        val total = obj.optString("vod_total").trim().toIntOrNull() ?: 0
        val detail = toItem(obj, provider).copy(
            bookId = bookId,
            episodeCnt = (if (total > 0) total else episodes.size).toString()
        )
        markProviderSuccess(providerKey)
        return ShortDramaDetailData(detail = detail, episodes = episodes, sources = sources)
    }

    /**
     * 播放地址。采集源给的就是直链（m3u8），直接透传；老数据里的纯 id 没有解析价值，返回空串。
     */
    fun resolvePlayUrl(videoId: String): String {
        val id = videoId.trim()
        return if (id.startsWith("http://") || id.startsWith("https://")) id else ""
    }

    // ---------------- 内部实现 ----------------

    /**
     * 把 OkHttp / 采集源的原始报错翻译成人话。
     *
     * 日志保留 provider key + 原始 message：用户看到的是「连不上短剧源，检查网络」，
     * 排查时还能在 logcat 里对上到底是 DNS、TLS 还是 502。
     */
    private fun translateError(providerKey: String, raw: String?, cause: Throwable? = null): String {
        val rawText = raw ?: cause?.message ?: ""
        Log.e(TAG, "短剧源错误 provider=$providerKey raw=$rawText")
        val text = rawText.lowercase()
        val isTimeout = cause is SocketTimeoutException ||
            text.contains("timeout") || text.contains("timed out") || text.contains("time out")
        return when {
            isTimeout -> "短剧源响应超时，稍后再试"
            text.contains("http 401") || text.contains("http 403") || text.contains("http 404") ->
                "短剧源拒绝了请求"
            // 429 和 5xx 是「源被限流 / 源自己挂了」，跟用户没关系：直接原文抛
            // 「HTTP 500: Internal Server Error」只会让用户以为是自己手机的问题
            text.contains("http 429") -> "短剧源请求太频繁，稍后再试"
            text.contains("http 5") -> "短剧源暂时不可用，稍后再试"
            text.contains("unable to resolve host") || text.contains("unknownhost") ||
                text.contains("dns") || text.contains("connect") || text.contains("connection") ||
                text.contains("network error") || text.contains("socket") || text.contains("econn") ->
                "连不上短剧源，检查网络"
            text.contains("json") || text.contains("parse") ||
                text.contains("empty response body") || text.contains("格式") ->
                "短剧源返回格式异常"
            rawText.isBlank() -> "短剧源返回格式异常"
            // 采集源自己回的中文提示（例如「数据不存」）比兜底文案更有信息量，原样透出
            else -> rawText
        }
    }

    private fun buildListUrl(p: Provider, tab: Tab, page: Int): String {
        val base = p.base
        return if (tab.wholeLibrary && p.dramaType != null) {
            "$base&t=${p.dramaType}&pg=$page"
        } else {
            val keyword = effectiveKeyword(tab)
            "$base&wd=${URLEncoder.encode(keyword, "UTF-8")}&pg=$page"
        }
    }

    private fun parseList(json: String, p: Provider): List<ShortDramaItem> {
        val arr = JSONObject(json).optJSONArray("list") ?: return emptyList()
        val out = ArrayList<ShortDramaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val vodId = obj.opt("vod_id").toString().trim()
            if (vodId.isEmpty() || vodId == "null") continue
            out.add(toItem(obj, p))
        }
        return out
    }

    private fun toItem(o: JSONObject, p: Provider): ShortDramaItem {
        val vodId = o.opt("vod_id").toString().trim()
        val name = o.optString("vod_name").trim()
        val remarks = o.optString("vod_remarks").trim()
        val total = o.optString("vod_total").trim().toIntOrNull() ?: 0
        val episodeCount = when {
            total > 0 -> total.toString()
            else -> Regex("(\\d+)").find(remarks)?.groupValues?.get(1) ?: ""
        }
        val intro = stripHtml(o.optString("vod_content"))
            .ifBlank { stripHtml(o.optString("vod_blurb")) }
            .ifBlank { "暂无简介" }

        // 完结判断：优先看 vod_isend（CMS 的 0/1 字段），没这个字段的源退化看备注里的「完结」
        val isEnd = o.opt("vod_isend").toString().trim()
        val finished = isEnd == "1" || isEnd.equals("true", ignoreCase = true) || remarks.contains("完结")

        return ShortDramaItem(
            bookId = "${p.key}:$vodId",
            title = name.ifBlank { "未命名短剧" },
            author = o.optString("vod_actor").trim().ifBlank { "未知" },
            type = o.optString("vod_class").trim().ifBlank { remarks },
            playCnt = o.optString("vod_hits").trim().ifBlank { "0" },
            episodeCnt = episodeCount,
            cover = o.optString("vod_pic").trim(),
            intro = intro,
            year = o.optString("vod_year").trim(),
            score = o.optString("vod_score").trim(),
            finished = finished
        )
    }

    /**
     * 解析 vod_play_url 的全部线路。
     *
     * vod_play_url 形如：
     * `第1集$https://a/1.m3u8#第2集$https://a/2.m3u8$$$第1集$https://b/1.m3u8#第2集$https://b/2.m3u8`
     * —— `#` 分隔同一线路的分集，`$$$` 分隔不同播放源（线路）。
     *
     * 为什么要把全部源留下来（而不是像以前只取第一段）：
     * 单个线路的 m3u8 经常单条失效（换域名、防盗链、切片 404）。只保留第一源的话，
     * 用户碰到坏源只能退出重进详情页；把全部线路带到 UI，播放器起播失败可以原地切源继续看。
     */
    private fun parsePlaySources(o: JSONObject): List<ShortDramaPlaySource> {
        val segments = o.optString("vod_play_url").split("$$$")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()

        // vod_play_from 是采集源给的线路名，同样 $$$ 分隔；数量对得上就用真名，否则用「线路N」
        val fromNames = o.optString("vod_play_from").split("$$$").map { it.trim() }

        val out = ArrayList<ShortDramaPlaySource>(segments.size)
        segments.forEachIndexed { index, segment ->
            val episodes = parseEpisodes(segment).mapIndexed { i, (name, link) ->
                EpisodeItem(
                    videoId = link,
                    episodeNum = parseEpisodeNum(name) ?: (i + 1),
                    videoUrl = link
                )
            }.sortedBy { it.episodeNum }
            // 整条线路没有可用直链就跳过：保留一个空源只会让 UI 多一个切不动的按钮
            if (episodes.isEmpty()) return@forEachIndexed
            val name = fromNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "线路${index + 1}"
            // url 取该线路第一集的直链：采集源不单独给线路地址，这条直链既能当线路标识，也方便调试
            out.add(ShortDramaPlaySource(name = name, url = episodes.first().videoUrl, episodes = episodes))
        }
        return out
    }

    /** 单条线路内的分集："第1集$https://x/1.m3u8#第2集$https://x/2.m3u8"（$$$ 已在外层切掉） */
    private fun parseEpisodes(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split("#").mapNotNull { segment ->
            val seg = segment.trim()
            if (seg.isEmpty()) return@mapNotNull null
            val cut = seg.lastIndexOf('$')
            if (cut <= 0) {
                if (seg.startsWith("http")) "" to seg else null
            } else {
                val name = seg.substring(0, cut).trim()
                val link = seg.substring(cut + 1).trim()
                if (link.startsWith("http")) name to link else null
            }
        }
    }

    private fun parseEpisodeNum(name: String): Int? =
        Regex("第\\s*(\\d+)\\s*集").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("^(\\d+)$").find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun readMessage(json: String): String? = runCatching {
        val obj = JSONObject(json)
        val msg = obj.optString("msg")
        if (msg.isNotBlank()) msg else null
    }.getOrNull()

    private fun stripHtml(input: String): String =
        input.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** 一条播放源（线路）。episodes 为该线路自己的分集列表。 */
data class ShortDramaPlaySource(
    val name: String,
    val url: String,
    val episodes: List<EpisodeItem>
)

/** 详情 + 分集一起回来的产物 */
data class ShortDramaDetailData(
    val detail: ShortDramaItem,
    /** 第一播放源的分集，语义和以前一致（老调用方只认这个） */
    val episodes: List<EpisodeItem>,
    /** 全部播放源：第一源失效时 UI 可以切到别的线路 */
    val sources: List<ShortDramaPlaySource> = emptyList()
)

class ShortDramaException(message: String) : Exception(message)