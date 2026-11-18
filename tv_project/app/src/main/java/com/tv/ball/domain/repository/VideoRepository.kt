package com.tv.ball.domain.repository

import com.tv.ball.data.local.LocalDataSource
import com.tv.ball.data.local.VideoDao
import com.tv.ball.data.model.VideoEntity
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.parser.VideoDetailParserFactory
import com.tv.ball.domain.model.Episode
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.model.VideoDetail
import com.tv.ball.domain.parser.ParserChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

class VideoRepository(
    private val localDataSource: LocalDataSource,
    private val networkDataSource: NetworkDataSource,
    private val videoDao: VideoDao
) {
    
    fun getAllVideos(): Flow<List<VideoEntity>> {
        return localDataSource.getAllVideos()
    }
    
    suspend fun getVideoById(videoId: Long): VideoEntity? {
        return localDataSource.getVideoById(videoId)
    }
    
    suspend fun parseVideoUrl(url: String): ParseResult {
        android.util.Log.d("VideoRepository", "开始解析视频URL: $url")
        
        // 检查是否是在线之家的URL
        if (url.contains("zxzjhd.com", ignoreCase = true)) {
            android.util.Log.d("VideoRepository", "使用在线之家解析器")
            val parser = com.tv.ball.domain.parser.impl.ZxzjParser(networkDataSource)
            return parser.parse(url)
        }
        
        // 其他视频源暂时返回错误
        return ParseResult.Error("Parser not configured for this source")
    }
    
    suspend fun cacheVideo(video: VideoEntity): Long {
        return localDataSource.insertVideo(video)
    }
    
    suspend fun clearVideoCache() {
        localDataSource.deleteAllVideos()
    }
    
    suspend fun getVideoDetail(url: String): VideoDetail {
        android.util.Log.d("VideoRepository", "========== 开始加载视频详情 ==========")
        android.util.Log.d("VideoRepository", "请求URL: $url")
        
        // 根据URL智能识别视频源
        val sourceKey = identifySourceFromUrl(url)
        android.util.Log.d("VideoRepository", "识别的视频源: $sourceKey")
        
        try {
            // Fetch HTML/JSON content
            android.util.Log.d("VideoRepository", "开始网络请求...")
            val content = networkDataSource.fetchHtml(url)
            android.util.Log.d("VideoRepository", "网络请求成功，内容长度: ${content.length}")
            android.util.Log.d("VideoRepository", "内容预览: ${content.take(200)}")
            
            // 使用对应的解析器
            android.util.Log.d("VideoRepository", "开始解析内容...")
            val parser = VideoDetailParserFactory.getParser(sourceKey)
            val result = parser.parse(content, url) ?: VideoDetail(
                title = "解析失败",
                description = "无法解析视频详情",
                coverUrl = "",
                episodes = emptyList()
            )
            
            android.util.Log.d("VideoRepository", "解析完成，标题: ${result.title}, 集数: ${result.episodes.size}")
            android.util.Log.d("VideoRepository", "========== 视频详情加载完成 ==========")
            return result
            
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "========== 加载视频详情失败 ==========")
            android.util.Log.e("VideoRepository", "错误类型: ${e.javaClass.simpleName}")
            android.util.Log.e("VideoRepository", "错误信息: ${e.message}")
            android.util.Log.e("VideoRepository", "堆栈跟踪:", e)
            android.util.Log.e("VideoRepository", "========================================")
            throw e
        }
    }
    
    /**
     * 根据URL智能识别视频源
     */
    private fun identifySourceFromUrl(url: String): String {
        return when {
            // API源识别
            url.contains("iqiyizyapi.com") -> "iqiyi"
            url.contains("360zy") -> "360zy"
            url.contains("ffzy5.tv") -> "ffzy5"
            url.contains("dbzy") -> "dbzy"
            url.contains("niuniuzy") -> "niuniuzy3"
            url.contains("hongniuzy") -> "hongniu"
            url.contains("jisuziyuan") -> "jisuzy"
            
            // Web源识别
            url.contains("103.194.185.51") || url.contains("keke1.app") -> "keke"
            url.contains("zxzjhd.com") -> "zxzj"
            url.contains("libvio.cc") -> "libvod"
            
            // 默认使用当前选择的源
            else -> com.tv.ball.data.VideoSourceManager.getCurrentSource()
        }
    }
    
    private fun parseVideoDetailFromHtml(html: String, baseUrl: String): VideoDetail {
        // Extract title
        val title = extractTitle(html)
        
        // Extract description
        val description = extractDescription(html)
        
        // Extract cover URL
        val coverUrl = extractCoverUrl(html, baseUrl)
        
        // Extract episodes
        val episodes = extractEpisodes(html, baseUrl)
        
        return VideoDetail(
            title = title,
            description = description,
            coverUrl = coverUrl,
            episodes = episodes
        )
    }
    
    private fun extractTitle(html: String): String {
        // Try multiple patterns for title extraction
        val patterns = listOf(
            Regex("""<h1[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h1>"""),
            Regex("""<div[^>]*class="[^"]*module-info-heading[^"]*"[^>]*>.*?<h1[^>]*>([^<]+)</h1>""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<title>([^<]+)</title>""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        
        return "未知标题"
    }
    
    private fun extractDescription(html: String): String {
        val patterns = listOf(
            Regex("""<div[^>]*class="[^"]*module-info-introduction[^"]*"[^>]*>([^<]+)</div>"""),
            Regex("""<p[^>]*class="[^"]*desc[^"]*"[^>]*>([^<]+)</p>"""),
            Regex("""<meta\s+name="description"\s+content="([^"]+)"""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        
        return "暂无简介"
    }
    
    private fun extractCoverUrl(html: String, baseUrl: String): String {
        val patterns = listOf(
            Regex("""<img[^>]*class="[^"]*lazyload[^"]*"[^>]*data-original="([^"]+)""""),
            Regex("""<img[^>]*class="[^"]*cover[^"]*"[^>]*src="([^"]+)"""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                if (url.startsWith("//")) {
                    url = "https:$url"
                } else if (!url.startsWith("http")) {
                    url = "$baseUrl/$url"
                }
                return url
            }
        }
        
        return ""
    }
    
    private fun extractEpisodes(html: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Pattern for episode links
        val pattern = Regex("""<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""")
        val matches = pattern.findAll(html)
        
        for (match in matches) {
            val url = match.groupValues[1]
            val name = match.groupValues[2].trim()
            
            // Filter out non-episode links
            if (name.matches(Regex("""�?\d+集|EP?\d+|\d+"""))) {
                var fullUrl = url
                if (url.startsWith("/")) {
                    fullUrl = "$baseUrl$url"
                } else if (!url.startsWith("http")) {
                    fullUrl = "$baseUrl/$url"
                }
                
                episodes.add(Episode(name = name, url = fullUrl))
            }
        }
        
        return episodes
    }
    
    suspend fun searchVideos(query: String): List<com.tv.ball.presentation.home.VideoItem> {
        // 使用当前选择的视频源进行搜索
        val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
        val isApiSource = com.tv.ball.data.VideoSourceManager.isApiSource(currentSource)

        android.util.Log.d("VideoRepository", "单源搜索 - 源:$currentSource, 关键词:$query")

        if (isApiSource) {
            // API 源搜索
            val searchUrl = com.tv.ball.data.VideoSourceManager.getSearchApiUrl(currentSource, query)
            val html = networkDataSource.fetchHtml(searchUrl)
            // 检查是否是XML格式
            return if (html.trim().startsWith("<?xml")) {
                parseApiXmlSearchResults(html, currentSource)
            } else {
                parseApiSearchResults(html, currentSource)
            }
        } else {
            // Web 源搜索
            val searchUrl = com.tv.ball.data.VideoSourceManager.getSearchWebUrl(currentSource, query)
            val html = networkDataSource.fetchHtml(searchUrl)
            return parseWebSearchResults(html, currentSource)
        }
    }

    /**
     * 多站点并行搜索
     */
    suspend fun searchMultiSites(
        query: String,
        sources: List<String>
    ): List<com.tv.ball.presentation.home.VideoItem> = coroutineScope {
        sources.map { sourceKey ->
            async {
                try {
                    withTimeout(10000) {
                        searchInSource(query, sourceKey)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "搜索失败 [$sourceKey]: ${e.message}")
                    emptyList()
                }
            }
        }.flatMap { it.await() }
    }

    /**
     * 在指定视频源中搜索
     */
    private suspend fun searchInSource(
        query: String,
        sourceKey: String
    ): List<com.tv.ball.presentation.home.VideoItem> {
        val isApiSource = com.tv.ball.data.VideoSourceManager.isApiSource(sourceKey)
        val sourceName = com.tv.ball.data.VideoSourceManager.getSourceName(sourceKey)

        android.util.Log.d("VideoRepository", "在源 [$sourceKey] 中搜索: $query")

        return if (isApiSource) {
            val searchUrl = com.tv.ball.data.VideoSourceManager.getSearchApiUrl(sourceKey, query)
            val html = networkDataSource.fetchHtml(searchUrl)
            val results = if (html.trim().startsWith("<?xml")) {
                parseApiXmlSearchResults(html, sourceKey)
            } else {
                parseApiSearchResults(html, sourceKey)
            }
            results.map { it.copy(quality = "${it.quality} [$sourceName]", sourceKey = sourceKey) }
        } else {
            val searchUrl = com.tv.ball.data.VideoSourceManager.getSearchWebUrl(sourceKey, query)
            val html = networkDataSource.fetchHtml(searchUrl)
            parseWebSearchResults(html, sourceKey).map { it.copy(quality = "${it.quality} [$sourceName]", sourceKey = sourceKey) }
        }
    }
    
    private fun parseApiSearchResults(json: String, sourceKey: String = ""): List<com.tv.ball.presentation.home.VideoItem> {
        val results = mutableListOf<com.tv.ball.presentation.home.VideoItem>()
        val currentSource = if (sourceKey.isEmpty()) com.tv.ball.data.VideoSourceManager.getCurrentSource() else sourceKey
        
        try {
            val listPattern = """"list":\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val listMatch = listPattern.find(json)
            
            if (listMatch != null) {
                val listContent = listMatch.groupValues[1]
                // 匹配每个完整的JSON对象
                val itemPattern = """\{[^}]*?"vod_id":\s*(\d+)[^}]*?\}""".toRegex()
                
                for (match in itemPattern.findAll(listContent)) {
                    val itemJson = match.value
                    
                    // 分别提取各个字段
                    val vodId = """"vod_id":\s*(\d+)""".toRegex().find(itemJson)?.groupValues?.get(1) ?: continue
                    val vodName = """"vod_name":\s*"([^"]+)"""".toRegex().find(itemJson)?.groupValues?.get(1) ?: continue
                    val vodPic = """"vod_pic":\s*"([^"]*)"""".toRegex().find(itemJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                    val vodRemarks = """"vod_remarks":\s*"([^"]*)"""".toRegex().find(itemJson)?.groupValues?.get(1) ?: ""
                    
                    android.util.Log.d("VideoRepository", "搜索结果 - 源:$currentSource, ID:$vodId, 标题:$vodName")
                    
                    results.add(
                        com.tv.ball.presentation.home.VideoItem(
                            title = vodName,
                            coverUrl = vodPic,
                            detailUrl = vodId,
                            quality = vodRemarks,
                            sourceKey = currentSource
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "API搜索结果解析失败: ${e.message}")
        }
        
        return results
    }
    
    private fun parseApiXmlSearchResults(xml: String, sourceKey: String): List<com.tv.ball.presentation.home.VideoItem> {
        val results = mutableListOf<com.tv.ball.presentation.home.VideoItem>()
        
        try {
            // 匹配每个<video>标签
            val videoPattern = """<video>(.*?)</video>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            for (match in videoPattern.findAll(xml)) {
                val videoXml = match.groupValues[1]
                
                // 提取各个字段
                val vodId = """<id>(\d+)</id>""".toRegex().find(videoXml)?.groupValues?.get(1) ?: continue
                val vodName = """<name>&lt;!\[CDATA\[(.*?)\]\]&gt;</name>""".toRegex().find(videoXml)?.groupValues?.get(1) ?: continue
                val vodPic = """<pic>(.*?)</pic>""".toRegex().find(videoXml)?.groupValues?.get(1) ?: ""
                val vodRemarks = """<note>&lt;!\[CDATA\[(.*?)\]\]&gt;</note>""".toRegex().find(videoXml)?.groupValues?.get(1) ?: ""
                
                // 补全图片URL
                val fullPicUrl = if (vodPic.isNotEmpty() && !vodPic.startsWith("http")) {
                    "https://uuzy.me$vodPic"
                } else {
                    vodPic
                }
                
                android.util.Log.d("VideoRepository", "XML搜索结果 - 源:$sourceKey, ID:$vodId, 标题:$vodName")
                
                results.add(
                    com.tv.ball.presentation.home.VideoItem(
                        title = vodName,
                        coverUrl = fullPicUrl,
                        detailUrl = vodId,
                        quality = vodRemarks,
                        sourceKey = sourceKey
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "XML搜索结果解析失败: ${e.message}")
        }
        
        return results
    }
    
    private fun parseWebSearchResults(html: String, sourceKey: String): List<com.tv.ball.presentation.home.VideoItem> {
        val results = mutableListOf<com.tv.ball.presentation.home.VideoItem>()
        val cleanHtml = html.replace("\r", "").replace("\n", "")
        
        android.util.Log.d("VideoRepository", "========== 解析Web搜索结果 ==========")
        android.util.Log.d("VideoRepository", "视频源: $sourceKey")
        
        when (sourceKey) {
            "keke" -> {
                // 可可影视搜索结果解析 - IP地址版本
                val baseUrl = "https://103.194.185.51:51120"
                
                // 搜索结果的HTML结构
                val pattern = """<a[^>]*class="[^"]*stui-vodlist__thumb[^"]*"[^>]*href="([^"]+)"[^>]*>.*?<img[^>]*data-original="([^"]+)"[^>]*>.*?<span[^>]*class="[^"]*pic-text[^"]*"[^>]*>([^<]*)</span>.*?<h4[^>]*class="[^"]*title[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                
                for (match in pattern.findAll(cleanHtml)) {
                    var link = match.groupValues[1]
                    var img = match.groupValues[2]
                    val quality = match.groupValues[3].trim()
                    val title = match.groupValues[4].trim()
                    
                    // 处理相对路径
                    if (!link.startsWith("http")) {
                        link = baseUrl + link
                    }
                    if (img.startsWith("//")) {
                        img = "https:$img"
                    } else if (img.startsWith("/")) {
                        img = baseUrl + img
                    }
                    
                    if (title.isNotEmpty() && !title.contains("可可影视")) {
                        android.util.Log.d("VideoRepository", "可可影视搜索结果 - 标题:$title, URL:$link")
                        results.add(
                            com.tv.ball.presentation.home.VideoItem(
                                title = title,
                                coverUrl = img,
                                detailUrl = link,
                                quality = quality.ifEmpty { "HD" },
                                sourceKey = sourceKey
                            )
                        )
                    }
                }
            }
            "zxzj" -> {
                // 在线之家搜索结果解析
                val baseUrl = "https://www.zxzjhd.com"
                
                // 搜索结果的HTML结构：包含图片和标题
                // <a href="/detail/4561.html">
                //   <img data-original="//img9.doubanio.com/view/photo/s_ratio_poster/public/p2885787484.jpg">
                //   <h4>标题</h4>
                // </a>
                val fullPattern = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<img[^>]*data-original="([^"]+)".*?<h4>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                
                for (match in fullPattern.findAll(cleanHtml)) {
                    val link = match.groupValues[1]
                    var imageUrl = match.groupValues[2].trim()
                    val title = match.groupValues[3].trim()
                    
                    if (title.isNotEmpty()) {
                        // 修复图片URL - 补全协议
                        if (imageUrl.startsWith("//")) {
                            imageUrl = "https:$imageUrl"
                        }
                        
                        val fullUrl = baseUrl + link
                        android.util.Log.d("VideoRepository", "在线之家搜索结果 - 标题:$title, URL:$fullUrl")
                        
                        results.add(
                            com.tv.ball.presentation.home.VideoItem(
                                title = title,
                                coverUrl = imageUrl,
                                detailUrl = fullUrl,
                                quality = "HD",
                                sourceKey = sourceKey
                            )
                        )
                    }
                }
                
                // 如果没有匹配到，尝试仅标题模式（无图片）
                if (results.isEmpty()) {
                    val titleOnlyPattern = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<h4>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    for (match in titleOnlyPattern.findAll(cleanHtml)) {
                        val link = match.groupValues[1]
                        val title = match.groupValues[2].trim()
                        
                        if (title.isNotEmpty()) {
                            val fullUrl = baseUrl + link
                            android.util.Log.d("VideoRepository", "在线之家搜索结果(无图) - 标题:$title, URL:$fullUrl")
                            
                            results.add(
                                com.tv.ball.presentation.home.VideoItem(
                                    title = title,
                                    coverUrl = "",
                                    detailUrl = fullUrl,
                                    quality = "HD",
                                    sourceKey = sourceKey
                                )
                            )
                        }
                    }
                }
            }
            "dadaqu" -> {
                // 达达趣搜索结果解析
                val baseUrl = "https://www.dadaqu.me"
                
                android.util.Log.d("VideoRepository", "开始解析达达趣搜索结果")
                
                // 达达趣搜索结果可能的HTML结构：
                // 模式1: module-item 结构
                // <a class="module-item" href="/detail/121753.html">
                //   <div class="module-item-pic">
                //     <img data-original="图片URL">
                //   </div>
                //   <div class="module-item-text">标题</div>
                // </a>
                
                // 模式2: stui-vodlist__thumb 结构
                // <a href="/detail/121753.html" class="stui-vodlist__thumb">
                //   <img data-original="图片URL">
                //   <span class="pic-text">更新信息</span>
                // </a>
                // <h4 class="title">标题</h4>
                
                var matchCount = 0
                
                // 尝试模式1: module-item
                val pattern1 = """<a[^>]*class="[^"]*module-item[^"]*"[^>]*href="([^"]+)"[^>]*>.*?<img[^>]*data-original="([^"]+)".*?<div[^>]*class="[^"]*module-item-text[^"]*"[^>]*>([^<]+)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                
                for (match in pattern1.findAll(cleanHtml)) {
                    val link = match.groupValues[1]
                    var imageUrl = match.groupValues[2].trim()
                    val title = match.groupValues[3].trim()
                    
                    if (title.isEmpty()) continue
                    
                    // 修复图片URL
                    if (imageUrl.startsWith("//")) {
                        imageUrl = "https:$imageUrl"
                    } else if (!imageUrl.startsWith("http")) {
                        imageUrl = baseUrl + imageUrl
                    }
                    
                    val fullUrl = if (link.startsWith("http")) link else baseUrl + link
                    
                    matchCount++
                    android.util.Log.d("VideoRepository", "达达趣搜索结果(模式1) #$matchCount - 标题:$title, URL:$fullUrl")
                    
                    results.add(
                        com.tv.ball.presentation.home.VideoItem(
                            title = title,
                            coverUrl = imageUrl,
                            detailUrl = fullUrl,
                            quality = "HD",
                            sourceKey = sourceKey
                        )
                    )
                }
                
                // 如果模式1没匹配到，尝试模式2: stui-vodlist__thumb
                if (results.isEmpty()) {
                    android.util.Log.d("VideoRepository", "模式1未匹配，尝试模式2")
                    val pattern2 = """<a[^>]*href="([^"]+)"[^>]*class="[^"]*stui-vodlist__thumb[^"]*"[^>]*>.*?<img[^>]*data-original="([^"]+)".*?<span[^>]*class="[^"]*pic-text[^"]*"[^>]*>([^<]*)</span>.*?<h4[^>]*class="[^"]*title[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    for (match in pattern2.findAll(cleanHtml)) {
                        val link = match.groupValues[1]
                        var imageUrl = match.groupValues[2].trim()
                        val quality = match.groupValues[3].trim()
                        val title = match.groupValues[4].trim()
                        
                        if (title.isEmpty()) continue
                        
                        // 修复图片URL
                        if (imageUrl.startsWith("//")) {
                            imageUrl = "https:$imageUrl"
                        } else if (!imageUrl.startsWith("http")) {
                            imageUrl = baseUrl + imageUrl
                        }
                        
                        val fullUrl = if (link.startsWith("http")) link else baseUrl + link
                        
                        matchCount++
                        android.util.Log.d("VideoRepository", "达达趣搜索结果(模式2) #$matchCount - 标题:$title, URL:$fullUrl")
                        
                        results.add(
                            com.tv.ball.presentation.home.VideoItem(
                                title = title,
                                coverUrl = imageUrl,
                                detailUrl = fullUrl,
                                quality = quality.ifEmpty { "HD" },
                                sourceKey = sourceKey
                            )
                        )
                    }
                }
                
                // 如果还是没匹配到，尝试模式3: 简化版（只匹配链接和标题）
                if (results.isEmpty()) {
                    android.util.Log.d("VideoRepository", "模式2未匹配，尝试模式3")
                    val pattern3 = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<h4[^>]*>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    for (match in pattern3.findAll(cleanHtml)) {
                        val link = match.groupValues[1]
                        val title = match.groupValues[2].trim()
                        
                        if (title.isEmpty() || title.length < 2) continue
                        
                        val fullUrl = baseUrl + link
                        
                        matchCount++
                        android.util.Log.d("VideoRepository", "达达趣搜索结果(模式3) #$matchCount - 标题:$title, URL:$fullUrl")
                        
                        results.add(
                            com.tv.ball.presentation.home.VideoItem(
                                title = title,
                                coverUrl = "",
                                detailUrl = fullUrl,
                                quality = "HD",
                                sourceKey = sourceKey
                            )
                        )
                    }
                }
                
                android.util.Log.d("VideoRepository", "达达趣搜索结果解析完成，共 $matchCount 条")
                
                // 如果没有解析到结果，打印调试信息
                if (results.isEmpty()) {
                    val sample = cleanHtml.take(2000)
                    android.util.Log.e("VideoRepository", "========== 达达趣搜索解析失败！HTML示例 ==========")
                    android.util.Log.e("VideoRepository", sample)
                    android.util.Log.e("VideoRepository", "包含 module-item: ${cleanHtml.contains("module-item")}")
                    android.util.Log.e("VideoRepository", "包含 stui-vodlist__thumb: ${cleanHtml.contains("stui-vodlist__thumb")}")
                    android.util.Log.e("VideoRepository", "包含 /detail/: ${cleanHtml.contains("/detail/")}")
                    android.util.Log.e("VideoRepository", "========================================")
                }
            }
            else -> {
                // 默认搜索结果解析（freeok等）
                val pattern = Regex(
                    """<a\s+href="([^"]+)"\s+class="module-card-item-poster".*?data-original="([^"]+)".*?<strong>([^<]+)</strong>""",
                    RegexOption.DOT_MATCHES_ALL
                )
                
                for (match in pattern.findAll(cleanHtml)) {
                    val url = match.groupValues[1]
                    var imageUrl = match.groupValues[2]
                    val title = match.groupValues[3].trim()
                    
                    // Fix image URL
                    if (imageUrl.startsWith("//")) {
                        imageUrl = "https:$imageUrl"
                    } else if (!imageUrl.startsWith("http")) {
                        imageUrl = "https://www.freeok.mobi$imageUrl"
                    }
                    
                    // Fix video URL
                    val fullUrl = if (url.startsWith("/")) {
                        "https://www.freeok.mobi$url"
                    } else {
                        url
                    }
                    
                    android.util.Log.d("VideoRepository", "默认源搜索结果 - 标题:$title, URL:$fullUrl")
                    
                    results.add(
                        com.tv.ball.presentation.home.VideoItem(
                            title = title,
                            coverUrl = imageUrl,
                            detailUrl = fullUrl,
                            quality = "HD",
                            sourceKey = sourceKey
                        )
                    )
                }
            }
        }
        
        android.util.Log.d("VideoRepository", "Web搜索结果解析完成，共 ${results.size} 条")
        android.util.Log.d("VideoRepository", "========================================")
        
        return results
    }
}
