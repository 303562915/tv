package com.tv.ball.presentation.shortdrama

import android.util.Log
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import com.tv.ball.di.Dependencies
import okhttp3.OkHttpClient
import org.json.JSONObject
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

    private fun headers(p: Provider): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to p.base.substringBefore("/api.php")
    )

    /** 列表/搜索。同一页在多个源之间失败互备，全挂才抛错。 */
    suspend fun fetchList(categoryLabel: String, page: Int): List<ShortDramaItem> {
        val tab = tabs.firstOrNull { it.label == categoryLabel } ?: tabs.first()
        return fetch(tab, page)
    }

    /** 关键词搜索：站内搜索框走这里，仍然是多源互备。 */
    suspend fun search(keyword: String, page: Int): List<ShortDramaItem> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return fetch(Tab("搜索", kw), page)
    }

    private suspend fun fetch(tab: Tab, page: Int): List<ShortDramaItem> {
        // 整库刷优先用带短剧分类的源，其余源作为关键词兜底
        val candidates = if (tab.wholeLibrary) {
            providers.filter { it.dramaType != null } + providers.filter { it.dramaType == null }
        } else {
            providers
        }

        var lastError: String? = null
        for (p in candidates) {
            val url = buildListUrl(p, tab, page)
            when (val result = client.get(url, headers(p))) {
                is NetworkResult.Success -> {
                    val items = runCatching { parseList(result.data, p) }
                        .onFailure { Log.e(TAG, "解析列表失败(${p.key}): ${it.message}") }
                        .getOrDefault(emptyList())
                    if (items.isNotEmpty()) {
                        Log.d(TAG, "${p.key} 第${page}页 ${items.size} 条（${tab.label}）")
                        return items
                    }
                    // 翻译成人话：整库刷第 2 页起为空 = 到底了；第 1 页为空说明这个源没货，换下一个
                    if (tab.wholeLibrary && page > 1) return emptyList()
                    lastError = readMessage(result.data) ?: lastError
                }
                is NetworkResult.Error -> {
                    Log.w(TAG, "${p.key} 请求失败: ${result.message}")
                    lastError = result.message
                }
            }
        }
        throw ShortDramaException(lastError ?: "短剧源暂时没有返回内容")
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
            is NetworkResult.Error -> throw ShortDramaException(result.message)
        }

        val root = JSONObject(json)
        val list = root.optJSONArray("list")
        val obj = list?.optJSONObject(0) ?: throw ShortDramaException(readMessage(json) ?: "短剧详情为空")

        val episodes = parseEpisodes(obj.optString("vod_play_url")).mapIndexed { index, (name, link) ->
            EpisodeItem(
                videoId = link,
                episodeNum = parseEpisodeNum(name) ?: (index + 1),
                videoUrl = link
            )
        }.sortedBy { it.episodeNum }

        if (episodes.isEmpty()) throw ShortDramaException("这部剧没有可用播放地址")

        val total = obj.optString("vod_total").trim().toIntOrNull() ?: 0
        val detail = toItem(obj, provider).copy(
            bookId = bookId,
            episodeCnt = (if (total > 0) total else episodes.size).toString()
        )
        return ShortDramaDetailData(detail, episodes)
    }

    /**
     * 播放地址。采集源给的就是直链（m3u8），直接透传；老数据里的纯 id 没有解析价值，返回空串。
     */
    fun resolvePlayUrl(videoId: String): String {
        val id = videoId.trim()
        return if (id.startsWith("http://") || id.startsWith("https://")) id else ""
    }

    // ---------------- 内部实现 ----------------

    private fun buildListUrl(p: Provider, tab: Tab, page: Int): String {
        val base = p.base
        return if (tab.wholeLibrary && p.dramaType != null) {
            "$base&t=${p.dramaType}&pg=$page"
        } else {
            val keyword = if (tab.wholeLibrary) "短剧" else tab.keyword
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

        return ShortDramaItem(
            bookId = "${p.key}:$vodId",
            title = name.ifBlank { "未命名短剧" },
            author = o.optString("vod_actor").trim().ifBlank { "未知" },
            type = o.optString("vod_class").trim().ifBlank { remarks },
            playCnt = o.optString("vod_hits").trim().ifBlank { "0" },
            episodeCnt = episodeCount,
            cover = o.optString("vod_pic").trim(),
            intro = intro
        )
    }

    /** vod_play_url: "第1集$https://x/1.m3u8#第2集$https://x/2.m3u8"，多播放源之间用 $$$ 分隔 */
    private fun parseEpisodes(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        val firstSource = raw.split("$$$").firstOrNull().orEmpty()
        return firstSource.split("#").mapNotNull { segment ->
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

/** 详情 + 分集一起回来的产物 */
data class ShortDramaDetailData(
    val detail: ShortDramaItem,
    val episodes: List<EpisodeItem>
)

class ShortDramaException(message: String) : Exception(message)