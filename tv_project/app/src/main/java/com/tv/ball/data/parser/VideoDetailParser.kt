package com.tv.ball.data.parser

import android.util.Log
import com.tv.ball.domain.model.Episode
import com.tv.ball.domain.model.VideoDetail

interface VideoDetailParser {
    fun parse(html: String, sourceUrl: String): VideoDetail?
}

object VideoDetailParserFactory {
    private const val TAG = "VideoDetailParser"
    
    fun getParser(sourceKey: String): VideoDetailParser {
        return when (sourceKey) {
            "libvod" -> WebDetailParser()
            "keke" -> WebDetailParser()
            "zxzj" -> WebDetailParser()
            "dadaqu" -> WebDetailParser()
            "iqiyi", "360zy", "ffzy5", "dbzy", "niuniuzy3", "hongniu" -> ApiDetailParser()
            else -> ApiDetailParser() // 默认使用API解析器
        }
    }
}

// API类型详情解析器（JSON）
class ApiDetailParser : VideoDetailParser {
    override fun parse(html: String, sourceUrl: String): VideoDetail? {
        try {
            Log.d("ApiDetailParser", "开始解析API详情")
            Log.d("ApiDetailParser", "内容长度: ${html.length}")
            Log.d("ApiDetailParser", "内容预览: ${html.take(500)}")
            
            // 解析JSON格式的详情
            val vodNamePattern = """"vod_name":"([^"]+)"""".toRegex()
            val vodPicPattern = """"vod_pic":"([^"]+)"""".toRegex()
            val vodContentPattern = """"vod_content":"([^"]+)"""".toRegex()
            val vodPlayUrlPattern = """"vod_play_url":"([^"]+)"""".toRegex()
            val vodYearPattern = """"vod_year":"([^"]+)"""".toRegex()
            val vodAreaPattern = """"vod_area":"([^"]+)"""".toRegex()
            val vodTypePattern = """"type_name":"([^"]+)"""".toRegex()
            val vodDirectorPattern = """"vod_director":"([^"]+)"""".toRegex()
            val vodActorPattern = """"vod_actor":"([^"]+)"""".toRegex()
            val vodPlayFromPattern = """"vod_play_from":"([^"]+)"""".toRegex()
            
            val title = vodNamePattern.find(html)?.groupValues?.get(1) ?: ""
            val cover = vodPicPattern.find(html)?.groupValues?.get(1) ?: ""
            val description = vodContentPattern.find(html)?.groupValues?.get(1) ?: ""
            val playUrl = vodPlayUrlPattern.find(html)?.groupValues?.get(1) ?: ""
            val playFrom = vodPlayFromPattern.find(html)?.groupValues?.get(1) ?: ""
            val year = vodYearPattern.find(html)?.groupValues?.get(1) ?: ""
            val area = vodAreaPattern.find(html)?.groupValues?.get(1) ?: ""
            val type = vodTypePattern.find(html)?.groupValues?.get(1) ?: ""
            val director = vodDirectorPattern.find(html)?.groupValues?.get(1) ?: ""
            val actor = vodActorPattern.find(html)?.groupValues?.get(1) ?: ""
            
            Log.d("ApiDetailParser", "标题: $title")
            Log.d("ApiDetailParser", "播放源: $playFrom")
            Log.d("ApiDetailParser", "播放URL长度: ${playUrl.length}")
            
            // 解析播放列表
            val episodes = parseApiEpisodes(playUrl, playFrom)
            
            Log.d("ApiDetailParser", "解析到 ${episodes.size} 个剧集")
            if (episodes.isNotEmpty()) {
                Log.d("ApiDetailParser", "第一个剧集: ${episodes[0].name} -> ${episodes[0].url.take(100)}")
            }
            
            return VideoDetail(
                title = title,
                coverUrl = cover,
                description = description.replace("\\n", "\n"),
                episodes = episodes,
                year = year,
                area = area,
                type = type,
                director = director,
                actor = actor
            )
        } catch (e: Exception) {
            Log.e("ApiDetailParser", "Parse error: ${e.message}", e)
            return null
        }
    }
    
    private fun parseApiEpisodes(playUrl: String, playFrom: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            Log.d("ApiDetailParser", "开始解析剧集")
            
            // 处理多个播放源的情况
            // vod_play_from: "lzm3u8$$$lzm3u8"
            // vod_play_url: "第01集$url1#第02集$url2$$$第01集$url3#第02集$url4"
            
            val playFromList = playFrom.split("$$$")
            val playUrlList = playUrl.split("$$$")
            
            Log.d("ApiDetailParser", "播放源数量: ${playFromList.size}")
            Log.d("ApiDetailParser", "播放URL组数量: ${playUrlList.size}")
            
            // 遍历每个播放源
            playFromList.forEachIndexed { index, sourceName ->
                if (index < playUrlList.size) {
                    val sourceUrl = playUrlList[index]
                    val sourceDisplayName = if (playFromList.size > 1) "[$sourceName]" else ""
                    
                    Log.d("ApiDetailParser", "解析播放源 $index: $sourceName")
                    
                    // 格式: 第01集$url#第02集$url#...
                    val parts = sourceUrl.split("#")
                    Log.d("ApiDetailParser", "  剧集数量: ${parts.size}")
                    
                    parts.forEachIndexed { episodeIndex, part ->
                        if (part.isNotEmpty()) {
                            val episodeParts = part.split("$")
                            if (episodeParts.size >= 2) {
                                val episodeName = episodeParts[0].trim()
                                val episodeUrl = episodeParts[1].trim()
                                
                                if (episodeName.isNotEmpty() && episodeUrl.isNotEmpty()) {
                                    val displayName = if (sourceDisplayName.isNotEmpty()) {
                                        "$sourceDisplayName $episodeName"
                                    } else {
                                        episodeName
                                    }
                                    
                                    episodes.add(
                                        Episode(
                                            name = displayName,
                                            url = episodeUrl
                                        )
                                    )
                                    
                                    // 只打印前3个剧集的详细信息
                                    if (episodeIndex < 3) {
                                        Log.d("ApiDetailParser", "    剧集 $episodeIndex: $displayName -> ${episodeUrl.take(80)}")
                                    }
                                }
                            } else {
                                Log.w("ApiDetailParser", "    无效的剧集格式: $part")
                            }
                        }
                    }
                }
            }
            
            Log.d("ApiDetailParser", "剧集解析完成，总数: ${episodes.size}")
        } catch (e: Exception) {
            Log.e("ApiDetailParser", "Parse episodes error: ${e.message}", e)
        }
        
        return episodes
    }
}

// 网页类型详情解析器（HTML）
class WebDetailParser : VideoDetailParser {
    override fun parse(html: String, sourceUrl: String): VideoDetail? {
        try {
            Log.d("WebDetailParser", "开始解析详情页，URL: $sourceUrl")
            Log.d("WebDetailParser", "HTML 长度: ${html.length}")

            val cleanHtml = html.replace("\r", "").replace("\n", "")

            // 提取基础URL（用于拼接相对路径）
            val baseUrl = extractBaseUrl(sourceUrl)
            Log.d("WebDetailParser", "基础 URL: $baseUrl")
            
            // 判断是否是可可影视或在线之家或达达趣
            val isKeke = sourceUrl.contains("keke1.app") || sourceUrl.contains("103.194.185.51") || sourceUrl.contains("51120")
            val isZxzj = sourceUrl.contains("zxzjhd.com")
            val isDadaqu = sourceUrl.contains("dadaqu.me")

            Log.d("WebDetailParser", "是否可可影视: $isKeke")
            Log.d("WebDetailParser", "是否在线之家: $isZxzj")
            Log.d("WebDetailParser", "是否达达趣: $isDadaqu")
            
            // 解析标题 - 可可影视使用多种可能的结构
            var title = ""
            if (isKeke) {
                // 尝试多种标题模式
                val titlePatterns = listOf(
                    """<h1[^>]*class="[^"]*detail-title[^"]*"[^>]*>([^<]+)</h1>""".toRegex(),
                    """<div[^>]*class="[^"]*detail-title[^"]*"[^>]*>([^<]+)</div>""".toRegex(),
                    """<h2[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h2>""".toRegex(),
                    """<h1[^>]*>([^<]+)</h1>""".toRegex(),
                    """<title>([^<-]+)""".toRegex() // 从页面标题提取
                )
                
                for (pattern in titlePatterns) {
                    val match = pattern.find(cleanHtml)
                    if (match != null) {
                        title = match.groupValues[1].trim()
                        Log.d("WebDetailParser", "使用模式找到标题: $title")
                        break
                    }
                }
            } else if (isDadaqu) {
                // 达达趣标题解析
                val titlePatterns = listOf(
                    """<h1[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h1>""".toRegex(),
                    """<div[^>]*class="[^"]*video-info-header[^"]*"[^>]*>.*?<h1[^>]*>([^<]+)</h1>""".toRegex(RegexOption.DOT_MATCHES_ALL),
                    """<h1[^>]*>([^<]+)</h1>""".toRegex(),
                    """<title>([^<-_]+)""".toRegex()
                )
                
                for (pattern in titlePatterns) {
                    val match = pattern.find(cleanHtml)
                    if (match != null) {
                        title = match.groupValues[1].trim()
                        Log.d("WebDetailParser", "达达趣找到标题: $title")
                        break
                    }
                }
            } else {
                val titlePattern = """<h1[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h1>""".toRegex()
                title = titlePattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
            }
            Log.d("WebDetailParser", "标题: $title")

            // 解析封面 - 可可影视和在线之家可能使用不同的图片属性
            var cover = ""
            if (isKeke || isZxzj || isDadaqu) {
                val coverPatterns = listOf(
                    """<img[^>]*class="[^"]*lazy[^"]*"[^>]*data-original="([^"]+)"""".toRegex(),
                    """<img[^>]*data-original="([^"]+)"[^>]*class="[^"]*lazy[^"]*"""".toRegex(),
                    """<img[^>]*class="[^"]*lazyload[^"]*"[^>]*data-original="([^"]+)"""".toRegex(),
                    """<div[^>]*class="[^"]*detail-pic[^"]*"[^>]*>.*?<img[^>]*src="([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL),
                    """<img[^>]*data-original="([^"]+)"""".toRegex(), // 通用data-original
                    """<img[^>]*src="([^"]+)"[^>]*class="[^"]*cover[^"]*"""".toRegex()
                )
                
                for (pattern in coverPatterns) {
                    val match = pattern.find(cleanHtml)
                    if (match != null) {
                        cover = match.groupValues[1]
                        // 修复图片URL - 补全协议
                        if (cover.startsWith("//")) {
                            cover = "https:$cover"
                        }
                        Log.d("WebDetailParser", "找到封面: $cover")
                        break
                    }
                }
            } else {
                val coverPattern = """<img[^>]*class="[^"]*lazyload[^"]*"[^>]*data-original="([^"]+)"""".toRegex()
                cover = coverPattern.find(cleanHtml)?.groupValues?.get(1) ?: ""
            }
            
            cover = resolveUrl(cover, baseUrl)
            Log.d("WebDetailParser", "封面: $cover")

            // 解析简介 - 可可影视可能使用不同的结构
            var description = ""
            if (isKeke) {
                val descPatterns = listOf(
                    """<div[^>]*class="[^"]*detail-desc[^"]*"[^>]*>([^<]+)</div>""".toRegex(),
                    """<div[^>]*class="[^"]*desc[^"]*"[^>]*>([^<]+)</div>""".toRegex(),
                    """<p[^>]*class="[^"]*detail-content[^"]*"[^>]*>([^<]+)</p>""".toRegex(),
                    """<span[^>]*class="[^"]*detail-content[^"]*"[^>]*>([^<]+)</span>""".toRegex(),
                    """<meta[^>]*name="description"[^>]*content="([^"]+)"""".toRegex() // 从meta标签提取
                )
                
                for (pattern in descPatterns) {
                    val match = pattern.find(cleanHtml)
                    if (match != null) {
                        description = match.groupValues[1].trim()
                        // 清理可能的前缀（如"柔美的细胞小将第三季："）
                        if (description.contains("：")) {
                            val parts = description.split("：", limit = 2)
                            if (parts.size == 2) {
                                description = parts[1].trim()
                            }
                        }
                        Log.d("WebDetailParser", "找到简介: ${description.take(50)}...")
                        break
                    }
                }
            } else if (isDadaqu) {
                // 达达趣简介解析
                val descPatterns = listOf(
                    """<div[^>]*class="[^"]*video-info-aux[^"]*"[^>]*>.*?<span[^>]*class="[^"]*detail[^"]*"[^>]*>([^<]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL),
                    """<span[^>]*class="[^"]*detail[^"]*"[^>]*>([^<]+)</span>""".toRegex(),
                    """<div[^>]*class="[^"]*desc[^"]*"[^>]*>([^<]+)</div>""".toRegex(),
                    """<p[^>]*class="[^"]*desc[^"]*"[^>]*>([^<]+)</p>""".toRegex(),
                    """<meta[^>]*name="description"[^>]*content="([^"]+)"""".toRegex()
                )
                
                for (pattern in descPatterns) {
                    val match = pattern.find(cleanHtml)
                    if (match != null) {
                        description = match.groupValues[1].trim()
                        Log.d("WebDetailParser", "达达趣找到简介: ${description.take(50)}...")
                        break
                    }
                }
            } else {
                val descPattern = """<span class="detail-content"[^>]*>([^<]+)</span>""".toRegex()
                description = descPattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
            }
            Log.d("WebDetailParser", "简介: ${description.take(100)}...")

            // 解析年份、地区、类型等信息 - 可可影视可能使用不同的结构
            var year = ""
            var area = ""
            var type = ""
            var director = ""
            var actor = ""
            
            if (isKeke) {
                // 可可影视可能使用 detail-info 或 detail-list 结构
                // 尝试多种模式
                val infoPatterns = mapOf(
                    "year" to listOf(
                        """年份[：:]\s*<[^>]*>([^<]+)<""".toRegex(),
                        """年份[：:]\s*([^<\n]+)""".toRegex(),
                        """<span[^>]*>年份[：:]</span>\s*<[^>]*>([^<]+)<""".toRegex()
                    ),
                    "area" to listOf(
                        """地区[：:]\s*<[^>]*>([^<]+)<""".toRegex(),
                        """地区[：:]\s*([^<\n]+)""".toRegex(),
                        """<span[^>]*>地区[：:]</span>\s*<[^>]*>([^<]+)<""".toRegex()
                    ),
                    "type" to listOf(
                        """类型[：:]\s*<[^>]*>([^<]+)<""".toRegex(),
                        """类型[：:]\s*([^<\n]+)""".toRegex(),
                        """<span[^>]*>类型[：:]</span>\s*<[^>]*>([^<]+)<""".toRegex()
                    ),
                    "director" to listOf(
                        """导演[：:]\s*<[^>]*>([^<]+)<""".toRegex(),
                        """导演[：:]\s*([^<\n]+)""".toRegex(),
                        """<span[^>]*>导演[：:]</span>\s*<[^>]*>([^<]+)<""".toRegex()
                    ),
                    "actor" to listOf(
                        """主演[：:]\s*<[^>]*>([^<]+)<""".toRegex(),
                        """主演[：:]\s*([^<\n]+)""".toRegex(),
                        """<span[^>]*>主演[：:]</span>\s*<[^>]*>([^<]+)<""".toRegex()
                    )
                )
                
                for ((field, patterns) in infoPatterns) {
                    for (pattern in patterns) {
                        val match = pattern.find(cleanHtml)
                        if (match != null) {
                            val value = match.groupValues[1].trim()
                            when (field) {
                                "year" -> year = value
                                "area" -> area = value
                                "type" -> type = value
                                "director" -> director = value
                                "actor" -> actor = value
                            }
                            Log.d("WebDetailParser", "$field: $value")
                            break
                        }
                    }
                }
            } else {
                // 原有的解析逻辑
                val yearPattern = """年份[：:]\s*([^<]+)<""".toRegex()
                year = yearPattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
                
                val areaPattern = """地区[：:]\s*([^<]+)<""".toRegex()
                area = areaPattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
                
                val typePattern = """类型[：:]\s*([^<]+)<""".toRegex()
                type = typePattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
                
                val directorPattern = """导演[：:]\s*([^<]+)<""".toRegex()
                director = directorPattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
                
                val actorPattern = """主演[：:]\s*([^<]+)<""".toRegex()
                actor = actorPattern.find(cleanHtml)?.groupValues?.get(1)?.trim() ?: ""
            }

            // 解析播放列表
            val episodes = if (isKeke) {
                parseKekeEpisodes(cleanHtml, baseUrl)
            } else if (isZxzj) {
                parseZxzjEpisodes(cleanHtml, baseUrl)
            } else if (isDadaqu) {
                parseDadaquEpisodes(cleanHtml, baseUrl)
            } else {
                parseWebEpisodes(cleanHtml, baseUrl)
            }
            Log.d("WebDetailParser", "解析到 ${episodes.size} 个剧集")

            return VideoDetail(
                title = title,
                coverUrl = cover,
                description = description,
                episodes = episodes,
                year = year,
                area = area,
                type = type,
                director = director,
                actor = actor
            )
        } catch (e: Exception) {
            Log.e("WebDetailParser", "Parse error: ${e.message}", e)
            return null
        }
    }

    private fun extractBaseUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}${port}"
        } catch (e: Exception) {
            ""
        }
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> url
        }
    }

    /**
     * 检测URL是否是网盘链接
     */
    private fun detectCloudStorage(url: String): Pair<Boolean, String> {
        return when {
            url.contains("drive.uc.cn", ignoreCase = true) -> Pair(true, "[UC]")
            url.contains("pan.quark.cn", ignoreCase = true) -> Pair(true, "[夸克]")
            url.contains("pan.baidu.com", ignoreCase = true) -> Pair(true, "[百度]")
            url.contains("aliyundrive.com", ignoreCase = true) || 
            url.contains("alipan.com", ignoreCase = true) -> Pair(true, "[阿里]")
            url.contains("115.com", ignoreCase = true) -> Pair(true, "[115]")
            url.contains("xunlei.com", ignoreCase = true) -> Pair(true, "[迅雷]")
            else -> Pair(false, "")
        }
    }

    private fun parseWebEpisodes(html: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        try {
            Log.d("WebDetailParser", "开始解析剧集，baseUrl: $baseUrl")

            // 1. 解析播放源 (BD5播放、HD5播放等)
            // 匹配: <div class="playlist-panel"> (不包含netdisk-panel)
            val playSourcePattern = """<div class="playlist-panel"[^>]*>\s*<div class="panel-head">\s*<h3>([^<]+)</h3>.*?<ul class="stui-content__playlist[^"]*"[^>]*>(.*?)</ul>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            for (match in playSourcePattern.findAll(html)) {
                val sourceName = match.groupValues[1].trim()
                val sourceContent = match.groupValues[2]
                
                Log.d("WebDetailParser", "找到播放源: $sourceName")
                
                // 提取该源下的所有剧集链接
                val episodePattern = """<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                var episodeCount = 0
                
                for (episodeMatch in episodePattern.findAll(sourceContent)) {
                    var url = episodeMatch.groupValues[1]
                    val name = episodeMatch.groupValues[2].trim()
                    
                    // 转换相对路径为完整URL
                    url = resolveUrl(url, baseUrl)
                    
                    // 检测是否是网盘链接
                    val (isCloud, cloudType) = detectCloudStorage(url)
                    
                    episodeCount++
                    if (episodeCount <= 3) {
                        Log.d("WebDetailParser", "  剧集: $name -> $url ${if (isCloud) "(网盘:$cloudType)" else ""}")
                    }
                    
                    episodes.add(
                        Episode(
                            name = if (isCloud) "$cloudType$name" else "[$sourceName] $name",
                            url = url,
                            isCloudStorage = isCloud,
                            cloudType = cloudType
                        )
                    )
                }
                
                Log.d("WebDetailParser", "  共 $episodeCount 个剧集")
            }
            
            // 如果没有找到播放源，尝试其他可能的结构
            if (episodes.isEmpty()) {
                Log.d("WebDetailParser", "尝试备用解析方式")
                
                // 备用方式1: 直接查找所有播放链接
                val altPattern1 = """<div class="module-play-list"[^>]*>.*?<div class="module-play-list-content"[^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                for (match in altPattern1.findAll(html)) {
                    val content = match.groupValues[1]
                    val episodePattern = """<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                    
                    for (episodeMatch in episodePattern.findAll(content)) {
                        var url = episodeMatch.groupValues[1]
                        val name = episodeMatch.groupValues[2].trim()
                        url = resolveUrl(url, baseUrl)
                        
                        // 检测是否是网盘链接
                        val (isCloud, cloudType) = detectCloudStorage(url)
                        
                        episodes.add(
                            Episode(
                                name = if (isCloud) "$cloudType$name" else name,
                                url = url,
                                isCloudStorage = isCloud,
                                cloudType = cloudType
                            )
                        )
                    }
                }
                
                // 备用方式2: 查找tab-content结构
                if (episodes.isEmpty()) {
                    val altPattern2 = """<div[^>]*class="[^"]*tab-content[^"]*"[^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    for (match in altPattern2.findAll(html)) {
                        val content = match.groupValues[1]
                        val episodePattern = """<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                        
                        for (episodeMatch in episodePattern.findAll(content)) {
                            var url = episodeMatch.groupValues[1]
                            val name = episodeMatch.groupValues[2].trim()
                            url = resolveUrl(url, baseUrl)
                            
                            // 检测是否是网盘链接
                            val (isCloud, cloudType) = detectCloudStorage(url)
                            
                            episodes.add(
                                Episode(
                                    name = if (isCloud) "$cloudType$name" else name,
                                    url = url,
                                    isCloudStorage = isCloud,
                                    cloudType = cloudType
                                )
                            )
                        }
                    }
                }
            }
            
            // 2. 解析网盘下载链接
            // 匹配: <div class="playlist-panel netdisk-panel">
            val downloadSourcePattern = """<div class="playlist-panel netdisk-panel">.*?<h3>([^<]+)</h3>.*?<div class="netdisk-list">(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            for (match in downloadSourcePattern.findAll(html)) {
                val sourceName = match.groupValues[1].trim() // 例如: "视频下载(UC)"
                val sourceContent = match.groupValues[2]
                
                Log.d("WebDetailParser", "找到下载源: $sourceName")
                
                // 提取网盘类型
                val cloudType = when {
                    sourceName.contains("UC", ignoreCase = true) -> "[UC]"
                    sourceName.contains("夸克", ignoreCase = true) -> "[夸克]"
                    sourceName.contains("百度", ignoreCase = true) -> "[百度]"
                    sourceName.contains("阿里", ignoreCase = true) -> "[阿里]"
                    sourceName.contains("115") -> "[115]"
                    sourceName.contains("迅雷", ignoreCase = true) -> "[迅雷]"
                    else -> "[网盘]"
                }
                
                // 提取该源下的所有下载链接
                val episodePattern = """<a[^>]*class="netdisk-item"[^>]*href="([^"]+)"[^>]*>.*?<span class="netdisk-name">([^<]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                var episodeCount = 0
                
                for (episodeMatch in episodePattern.findAll(sourceContent)) {
                    val url = episodeMatch.groupValues[1]
                    val name = episodeMatch.groupValues[2].trim()
                    
                    episodeCount++
                    if (episodeCount <= 3) {
                        Log.d("WebDetailParser", "  下载链接: $name -> $url")
                    }
                    
                    episodes.add(
                        Episode(
                            name = "$cloudType$name",
                            url = url,
                            isCloudStorage = true,
                            cloudType = cloudType
                        )
                    )
                }
                
                Log.d("WebDetailParser", "  共 $episodeCount 个下载链接")
            }

            Log.d("WebDetailParser", "总共解析到 ${episodes.size} 个剧集/下载链接")
        } catch (e: Exception) {
            Log.e("WebDetailParser", "Parse episodes error: ${e.message}", e)
        }

        return episodes
    }
    
    /**
     * 解析可可影视的剧集列表
     * 可可影视的特点：多个播放源，每个源有多个剧集
     * 注意：第一个线路需要排到最后（因为需要app才能看）
     */
    private fun parseKekeEpisodes(html: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val allSources = mutableListOf<Pair<String, List<Episode>>>() // 存储所有线路及其剧集
        
        try {
            Log.d("WebDetailParser", "开始解析可可影视剧集")
            
            // 可可影视实际结构：
            // <div class="episode-list-box">
            //   <div class="episode-list" style="">  <!-- 第一个线路，默认显示 -->
            //     <a href="/play/333078-32-3516190.html" class="episode-item"><span>1080P</span></a>
            //   </div>
            //   <div class="episode-list" style="display: none;">  <!-- 其他线路，默认隐藏 -->
            //     <a href="/play/333078-35-3516683.html" class="episode-item"><span>1080P</span></a>
            //   </div>
            // </div>
            
            // 解析所有播放源（episode-list）
            val playListPattern = """<div[^>]*class="episode-list"[^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            var sourceIndex = 0
            
            for (match in playListPattern.findAll(html)) {
                sourceIndex++
                val sourceName = "线路$sourceIndex"
                val listContent = match.groupValues[1]
                val sourceEpisodes = mutableListOf<Episode>()
                
                Log.d("WebDetailParser", "解析线路: $sourceName")
                
                // 提取该线路下的所有剧集
                val episodePattern = """<a[^>]*href="([^"]+)"[^>]*class="episode-item"[^>]*>.*?<span>([^<]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                var episodeCount = 0
                
                for (episodeMatch in episodePattern.findAll(listContent)) {
                    var url = episodeMatch.groupValues[1]
                    val name = episodeMatch.groupValues[2].trim()
                    
                    // 转换相对路径为完整URL
                    url = resolveUrl(url, baseUrl)
                    
                    // 检测是否是网盘链接
                    val (isCloud, cloudType) = detectCloudStorage(url)
                    
                    episodeCount++
                    if (episodeCount <= 3) {
                        Log.d("WebDetailParser", "  剧集: $name -> $url ${if (isCloud) "(网盘:$cloudType)" else ""}")
                    }
                    
                    sourceEpisodes.add(
                        Episode(
                            name = if (isCloud) "$cloudType$name" else "[$sourceName] $name",
                            url = url,
                            isCloudStorage = isCloud,
                            cloudType = cloudType
                        )
                    )
                }
                
                Log.d("WebDetailParser", "  共 $episodeCount 个剧集")
                
                // 保存这个线路的剧集
                if (sourceEpisodes.isNotEmpty()) {
                    allSources.add(Pair(sourceName, sourceEpisodes))
                }
            }
            
            // 重新排序：将第一个线路移到最后
            if (allSources.size > 1) {
                val firstSource = allSources.removeAt(0)
                allSources.add(firstSource)
                Log.d("WebDetailParser", "已将第一个线路移到最后位置")
            }
            
            // 按新顺序添加所有剧集
            for ((_, sourceEpisodes) in allSources) {
                episodes.addAll(sourceEpisodes)
            }
            
            // 如果上面的方式没有解析到，尝试备用方式
            if (episodes.isEmpty()) {
                Log.d("WebDetailParser", "尝试备用解析方式")
                
                // 备用方式：直接查找所有播放链接
                val altPattern = """<a[^>]*href="(/play/[^"]+)"[^>]*class="episode-item"[^>]*>.*?<span>([^<]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                for (match in altPattern.findAll(html)) {
                    var url = match.groupValues[1]
                    val name = match.groupValues[2].trim()
                    url = resolveUrl(url, baseUrl)
                    
                    episodes.add(
                        Episode(
                            name = name,
                            url = url,
                            isCloudStorage = false,
                            cloudType = ""
                        )
                    )
                }
            }
            
            Log.d("WebDetailParser", "可可影视共解析到 ${episodes.size} 个剧集")
        } catch (e: Exception) {
            Log.e("WebDetailParser", "Parse keke episodes error: ${e.message}", e)
        }
        
        return episodes
    }
    
    /**
     * 解析在线之家的剧集列表
     * 在线之家的特点：类似libvod的结构
     */
    private fun parseZxzjEpisodes(html: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            Log.d("WebDetailParser", "开始解析在线之家剧集")
            
            // 在线之家可能的HTML结构：
            // <a href="/video/4446-1-1.html">第1集</a>
            // 或者类似libvod的结构
            
            // 模式1: 直接匹配播放链接
            val pattern1 = """<a[^>]*href="(/video/\d+-\d+-\d+\.html)"[^>]*>([^<]+)</a>""".toRegex()
            
            var episodeCount = 0
            for (match in pattern1.findAll(html)) {
                var url = match.groupValues[1]
                val name = match.groupValues[2].trim()
                
                // 转换相对路径为完整URL
                url = resolveUrl(url, baseUrl)
                
                // 检测是否是网盘链接
                val (isCloud, cloudType) = detectCloudStorage(url)
                
                episodeCount++
                if (episodeCount <= 3) {
                    Log.d("WebDetailParser", "  剧集: $name -> $url ${if (isCloud) "(网盘:$cloudType)" else ""}")
                }
                
                episodes.add(
                    Episode(
                        name = if (isCloud) "$cloudType$name" else name,
                        url = url,
                        isCloudStorage = isCloud,
                        cloudType = cloudType
                    )
                )
            }
            
            // 如果没有找到，尝试备用模式
            if (episodes.isEmpty()) {
                Log.d("WebDetailParser", "尝试备用解析方式")
                
                // 备用模式：查找所有播放链接
                val pattern2 = """<a[^>]*href="(/play/[^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                for (match in pattern2.findAll(html)) {
                    var url = match.groupValues[1]
                    val name = match.groupValues[2].trim()
                    url = resolveUrl(url, baseUrl)
                    
                    // 检测是否是网盘链接
                    val (isCloud, cloudType) = detectCloudStorage(url)
                    
                    episodes.add(
                        Episode(
                            name = if (isCloud) "$cloudType$name" else name,
                            url = url,
                            isCloudStorage = isCloud,
                            cloudType = cloudType
                        )
                    )
                }
            }
            
            Log.d("WebDetailParser", "在线之家共解析到 ${episodes.size} 个剧集")
        } catch (e: Exception) {
            Log.e("WebDetailParser", "Parse zxzj episodes error: ${e.message}", e)
        }
        
        return episodes
    }
    
    /**
     * 解析达达趣的剧集列表
     * 达达趣的特点：类似其他网页源，可能有多个播放源
     * 播放详情页格式：/play/121753-1-1.html
     */
    private fun parseDadaquEpisodes(html: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            Log.d("WebDetailParser", "开始解析达达趣剧集")
            
            // 达达趣可能的HTML结构：
            // 1. 尝试解析播放源标签
            val playSourcePattern = """<div[^>]*class="[^"]*stui-vodlist__head[^"]*"[^>]*>.*?<h3[^>]*>([^<]+)</h3>.*?<ul[^>]*class="[^"]*stui-content__playlist[^"]*"[^>]*>(.*?)</ul>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            var foundSources = false
            for (match in playSourcePattern.findAll(html)) {
                foundSources = true
                val sourceName = match.groupValues[1].trim()
                val sourceContent = match.groupValues[2]
                
                Log.d("WebDetailParser", "找到播放源: $sourceName")
                
                // 提取该源下的所有剧集链接
                // 格式: <a href="/play/121753-1-1.html">第01集</a>
                val episodePattern = """<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                var episodeCount = 0
                
                for (episodeMatch in episodePattern.findAll(sourceContent)) {
                    var url = episodeMatch.groupValues[1]
                    val name = episodeMatch.groupValues[2].trim()
                    
                    // 转换相对路径为完整URL
                    url = resolveUrl(url, baseUrl)
                    
                    // 检测是否是网盘链接
                    val (isCloud, cloudType) = detectCloudStorage(url)
                    
                    episodeCount++
                    if (episodeCount <= 3) {
                        Log.d("WebDetailParser", "  剧集: $name -> $url ${if (isCloud) "(网盘:$cloudType)" else ""}")
                    }
                    
                    episodes.add(
                        Episode(
                            name = if (isCloud) "$cloudType$name" else "[$sourceName] $name",
                            url = url,
                            isCloudStorage = isCloud,
                            cloudType = cloudType
                        )
                    )
                }
                
                Log.d("WebDetailParser", "  共 $episodeCount 个剧集")
            }
            
            // 如果没有找到播放源，尝试备用方式
            if (!foundSources) {
                Log.d("WebDetailParser", "尝试备用解析方式")
                
                // 备用方式1: 直接查找所有播放链接 /play/xxx.html
                val altPattern1 = """<a[^>]*href="(/play/[^"]+\.html)"[^>]*>([^<]+)</a>""".toRegex()
                for (match in altPattern1.findAll(html)) {
                    var url = match.groupValues[1]
                    val name = match.groupValues[2].trim()
                    url = resolveUrl(url, baseUrl)
                    
                    // 检测是否是网盘链接
                    val (isCloud, cloudType) = detectCloudStorage(url)
                    
                    episodes.add(
                        Episode(
                            name = if (isCloud) "$cloudType$name" else name,
                            url = url,
                            isCloudStorage = isCloud,
                            cloudType = cloudType
                        )
                    )
                }
                
                // 备用方式2: 查找 module-play-list 结构
                if (episodes.isEmpty()) {
                    val altPattern2 = """<div[^>]*class="[^"]*module-play-list[^"]*"[^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    for (match in altPattern2.findAll(html)) {
                        val content = match.groupValues[1]
                        val episodePattern = """<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                        
                        for (episodeMatch in episodePattern.findAll(content)) {
                            var url = episodeMatch.groupValues[1]
                            val name = episodeMatch.groupValues[2].trim()
                            url = resolveUrl(url, baseUrl)
                            
                            // 检测是否是网盘链接
                            val (isCloud, cloudType) = detectCloudStorage(url)
                            
                            episodes.add(
                                Episode(
                                    name = if (isCloud) "$cloudType$name" else name,
                                    url = url,
                                    isCloudStorage = isCloud,
                                    cloudType = cloudType
                                )
                            )
                        }
                    }
                }
                
                // 备用方式3: 查找 playlist-panel 结构
                if (episodes.isEmpty()) {
                    val altPattern3 = """<div[^>]*class="[^"]*playlist-panel[^"]*"[^>]*>.*?<h3>([^<]+)</h3>.*?<ul[^>]*>(.*?)</ul>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    for (match in altPattern3.findAll(html)) {
                        val sourceName = match.groupValues[1].trim()
                        val content = match.groupValues[2]
                        val episodePattern = """<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
                        
                        for (episodeMatch in episodePattern.findAll(content)) {
                            var url = episodeMatch.groupValues[1]
                            val name = episodeMatch.groupValues[2].trim()
                            url = resolveUrl(url, baseUrl)
                            
                            // 检测是否是网盘链接
                            val (isCloud, cloudType) = detectCloudStorage(url)
                            
                            episodes.add(
                                Episode(
                                    name = if (isCloud) "$cloudType$name" else "[$sourceName] $name",
                                    url = url,
                                    isCloudStorage = isCloud,
                                    cloudType = cloudType
                                )
                            )
                        }
                    }
                }
            }
            
            Log.d("WebDetailParser", "达达趣共解析到 ${episodes.size} 个剧集")
        } catch (e: Exception) {
            Log.e("WebDetailParser", "Parse dadaqu episodes error: ${e.message}", e)
        }
        
        return episodes
    }
}
