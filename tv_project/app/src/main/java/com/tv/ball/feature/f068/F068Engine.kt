package com.tv.ball.feature.f068

import java.util.Locale

/**
 * 浏览器拿到这个扩展名之后会发生什么。
 *
 * 只看 MIME 是不够的：`application/octet-stream` 和 `video/x-matroska` 的区别不是"类型对不对"，
 * 而是**浏览器愿不愿意就地解封装**。把所有扩展名归成四类之后，页面上那句
 * 「能不能浏览器直播」才有确定的答案，而不是靠猜。
 *
 * 顺序即优先级：判定见 [F068Engine.playbackOf]。
 */
enum class BrowserPlayback {
    /** 浏览器带着这个 Content-Type 直接就地播放（`<video>` / `<audio>`），不弹下载。 */
    INLINE,

    /** 播放列表（m3u8）：能播，但要么 Safari 原生、要么页面自带 HLS 引擎。 */
    PLAYLIST,

    /** 字幕轨：文本资源，不是媒体流，靠 `<track>` 挂到播放器上。 */
    SUBTITLE,

    /** 浏览器只会下载，或者丢给外部播放器；网页里直播得先转封装。 */
    DOWNLOAD
}

/**
 * F068「MIME 映射与响应头」的能力层。
 *
 * 这一层只回答三个问题：**这个扩展名该发什么 Content-Type**、**整组响应头怎么拼**、
 * **浏览器到底能不能直接播**。
 *
 * 为什么值得单独一层：这套映射一旦写错，症状极其难查——
 * 视频文件明明是好的，浏览器却弹出下载框；或者能播但拖动进度条失效（少了 Accept-Ranges）；
 * 或者 web 端播放器取不到数据（少了 CORS 头，`<video crossOrigin>` 走的是 CORS 请求）。
 * 三种症状对应的其实是同一张表里的三列，散在各处拼 HEAD 响应时就很容易漏。
 *
 * 引擎**不引用 R**、不碰 Context：纯 Kotlin，能脱离 Android 单测；
 * 文案由页面自己翻（见 [F068Activity]），组件被别的模块复用时接一套自己的 strings 即可。
 */
object F068Engine {

    /**
     * 未知扩展名的兜底类型。
     *
     * 刻意**不回落** `application/octet-stream` 之外的任何类型，也刻意**不做内容嗅探**：
     * 服务端按扩展名猜 MIME 时，猜错的代价是"浏览器按错误类型解析"——把 .ass 当视频发出去，
     * 播放器会卡在解析阶段而不是给一条明确的"不支持"。octet-stream 的语义是
     * 「我不知道，你自己看着办」，浏览器一律转下载，至少行为是可预期的。
     */
    const val FALLBACK_MIME: String = "application/octet-stream"

    /**
     * 「大小未知」的哨兵值。
     *
     * 用 -1 而不是 0：0 是合法的空文件长度，跟"还没量出来"必须区分开——
     * 空文件可以老老实实写 `Content-Length: 0`，未知大小则一条都不能写（写了就是谎报长度，
     * 播放器会按这个长度把连接读断）。
     */
    const val SIZE_UNKNOWN: Long = -1L

    /**
     * 扩展名 → MIME 的唯一权威表。
     *
     * 用 [LinkedHashMap] 是为了保住声明顺序：页面上的"常用扩展名"按钮直接照这个顺序排，
     * 视频 → 音频 → 字幕 → 播放列表，改表就等于同时改按钮，不会出现两处顺序不一致。
     *
     * 几个值为什么不是"看起来更顺眼"的那个：
     * - `mkv` 用 `video/x-matroska` 而不是 `video/mkv`：后者不存在，只是名字像。
     * - `ts` 用 `video/mp2t`（MPEG-2 Transport Stream）而不是 `text/typescript` 或 `video/mp2t2`。
     * - `m4v` 用 `video/x-m4v` 而不是 `video/mp4`：两者容器一致，但 m4v 的约定俗成类型是前者，
     *   而 Safari 认后者、Chrome 认前者，发错了会掉到下载。宁可保持这个名字，交给页面按需覆盖。
     * - `vtt` 用 `text/vtt`（W3C WebVTT 的注册类型，`<track>` 只认它），
     *   `srt` 是 `application/x-subrip`（没有 IANA 注册，x- 前缀是行业惯例），
     *   `ass` 是 `text/x-ssa`（ASS 与 SSA 同源，播放器/ffmpeg 都按这个认）。
     * - `m3u8` 用 `application/vnd.apple.mpegurl`（HLS 的注册类型，Safari 只认它和 `audio/mpegurl`）。
     *
     * 这里**不带 charset 参数**：WebVTT 规范固定 UTF-8，播放器不看 charset；
     * 而给 `video/mp4` 之类二进制类型挂 charset 是无效参数，只会让某些 CDN 的缓存键多一种没意义的变体。
     */
    private val MIME_BY_EXT: Map<String, String> = linkedMapOf(
        // ===== 视频容器 =====
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "m4v" to "video/x-m4v",

        // ===== 音频 =====
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "flac" to "audio/flac",

        // ===== 字幕 =====
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",

        // ===== 播放列表 =====
        "m3u8" to "application/vnd.apple.mpegurl"
    )

    /** 全表扩展名，顺序同 [MIME_BY_EXT]，供页面遍历与文档对账。 */
    val SUPPORTED_EXTENSIONS: List<String> = MIME_BY_EXT.keys.toList()

    /**
     * 快捷按钮用的扩展名。
     *
     * 挑的是"最有对照价值"的十几个：既能播的（mp4/webm/mp3）、看着像能播其实下载的
     * （mkv/ts/flv/mov）、播放列表（m3u8）、字幕（vtt/ass/srt）。
     * 不用全表——按钮太多就没人在遥控器上按了。
     */
    val QUICK_EXTENSIONS: List<String> = listOf(
        "mp4", "mkv", "webm", "ts", "mov", "m3u8", "mp3", "aac", "vtt", "ass", "srt", "flv"
    )

    // ===== 扩展名归一化 =====

    /**
     * 把用户输入揉成裸扩展名。
     *
     * 页面上那个输入框名叫"文件名 / 扩展名"，用户什么都可能填进来：`电影.mp4`、
     * `http://nas:8080/media/EP01.mkv?token=abc`、`  MP4  `、`.webm`、甚至只有 `mp4`。
     * 归一化要处理的正是这五种形状，顺序也有讲究：
     *
     * 1. 去空白、转小写——路径和扩展名在 HTTP 里是大小写敏感的，但用户填的是文件名，
     *    我们只拿它查表，统一按小写处理，`MP4` 与 `.MP4` 必须查到同一行。
     * 2. 砍掉 `?` / `#` 之后的部分：带签名的地址里查询串比文件名还长，先砍才是对的；
     *    反过来先取扩展名会把 `mp4?token=x` 当成扩展名。
     * 3. 砍掉最后一个 `/` 之前的部分：URL 或 Windows 路径都适用，剩下的就是文件名。
     * 4. 取最后一个 `.` 之后的部分；没有点就整串当扩展名（用户可能只填了 `mkv`）。
     *
     * 输入是空串时返回空串，由调用方决定怎么展示；[mimeOf] 会把空串当未知处理。
     */
    fun normalizeExt(raw: String): String {
        var text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return ""

        val cut = text.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) text = text.substring(0, cut)

        val slash = text.lastIndexOf('/')
        if (slash >= 0) text = text.substring(slash + 1)

        val backslash = text.lastIndexOf('\\')
        if (backslash >= 0) text = text.substring(backslash + 1)

        val dot = text.lastIndexOf('.')
        if (dot >= 0) text = text.substring(dot + 1)

        return text.trim()
    }

    // ===== MIME 映射 =====

    /**
     * 扩展名 → Content-Type，未知一律 [FALLBACK_MIME]。
     *
     * 入参允许是文件名或 URL，内部先过 [normalizeExt]，所以 `Movie.MKV` 和
     * `http://host/a/b.webm?v=1` 都能直接喂进来。
     */
    fun mimeOf(ext: String): String = MIME_BY_EXT[normalizeExt(ext)] ?: FALLBACK_MIME

    /**
     * 这个扩展名在表里吗。
     *
     * 页面要区分「查到了」和「回落到兜底」——两者的 MIME 都"有值"，但后者意味着
     * 服务端其实什么都没判断出来，值得给用户一个提示色，而不是把 octet-stream 当成正常结果展示。
     */
    fun isKnown(ext: String): Boolean = MIME_BY_EXT.containsKey(normalizeExt(ext))

    /** 是不是字幕扩展名（vtt / srt / ass）。 */
    fun isSubtitle(ext: String): Boolean = playbackOf(ext) == BrowserPlayback.SUBTITLE

    /** 是不是播放列表（m3u8）。 */
    fun isPlaylist(ext: String): Boolean = playbackOf(ext) == BrowserPlayback.PLAYLIST

    // ===== 浏览器可播判定 =====

    /**
     * 这个扩展名在浏览器里的归宿。
     *
     * 判据是**浏览器原生解封装能力**，不是"编码是否常见"：mp4 里塞 H.264 才播，
     * 塞 H.265 一样不播——但那是编码问题，页面上说清"容器支持"已经够用，
     * 再往下钻就要读轨道头了，不属于 MIME 这一层的职责。
     *
     * 逐个说清几个容易误判的：
     * - `m4v` 与 `mp4` 容器相同、浏览器行为相同，故同为 [BrowserPlayback.INLINE]。
     * - `mov` 归 [BrowserPlayback.DOWNLOAD]：Safari 认 `video/quicktime`，Chrome / Firefox 不认，
     *   同一个地址在两种浏览器上行为不同比"都不能播"更难排查，所以按"不能播"处理并提示转封装。
     * - `ts` 归 [BrowserPlayback.DOWNLOAD]：`video/mp2t` 只在 HLS 的分片上下文里被消费，
     *   单独发一个 .ts 给 `<video>`，浏览器不接。
     * - `flac` 归 [BrowserPlayback.INLINE]：Chrome 56+ / Firefox / Safari 15+ 都内置解码，
     *   老浏览器会走到下载，属于可接受的降级。
     * - `m3u8` 是列表不是媒体：Safari / iOS 原生播，桌面 Chrome 要 hls.js，故单列 [BrowserPlayback.PLAYLIST]。
     */
    fun playbackOf(ext: String): BrowserPlayback = when (normalizeExt(ext)) {
        "mp4", "m4v", "webm", "mp3", "aac", "flac" -> BrowserPlayback.INLINE
        "m3u8" -> BrowserPlayback.PLAYLIST
        "vtt", "srt", "ass" -> BrowserPlayback.SUBTITLE
        // mkv / avi / ts / flv / mov / 以及所有未知扩展名
        else -> BrowserPlayback.DOWNLOAD
    }

    /**
     * 能不能在浏览器里直接播（含 m3u8 这种"能播但要 HLS 引擎"的）。
     *
     * 字幕不算"能播"：它不产生画面，只是挂在播放器上的一条轨。
     * 页面要区分"字幕"和"下载"时别只看这个布尔值，用 [playbackOf]。
     */
    fun isPlayableInBrowser(ext: String): Boolean = when (playbackOf(ext)) {
        BrowserPlayback.INLINE, BrowserPlayback.PLAYLIST -> true
        BrowserPlayback.SUBTITLE, BrowserPlayback.DOWNLOAD -> false
    }

    // ===== 响应头 =====

    /**
     * 拼出这个文件的整组响应头。
     *
     * 五条头各挡一类故障，缺一条就出一次现场：
     *
     * 1. `Content-Type` —— 缺了或错了，浏览器按 octet-stream 处理直接下载。这是本功能的正题。
     * 2. `Content-Length` —— 缺了浏览器不知道总长，进度条变成"一直加载"，直播流才该没有它。
     *    大小未知（[SIZE_UNKNOWN]）时**不写**：写 0 等于宣称空文件，播放器读一次就 EOF。
     * 3. `Accept-Ranges: bytes` —— 有了它浏览器才敢发 `Range: bytes=...` 跳转；
     *    少了就是"能播但拖不动进度条"。大小未知时降级成 `none`，明确告诉客户端别试。
     * 4. `Access-Control-Allow-Origin: *` —— web 端播放器一加 `crossOrigin`（要读字幕、
     *    要做频谱、要截帧）就变成 CORS 请求，缺这条连视频字节都取不到。
     *    媒体是公开只读资源，通配即可；真需要防盗链应该是签名 URL，不是靠 CORS 拦。
     * 5. `Cache-Control` —— 见 [cacheControlOf]。
     *
     * 用 [LinkedHashMap] 保序：页面上要把这组头原样打出来给人对照，
     * 固定成"类型 → 长度 → 范围 → 跨域 → 缓存"的顺序，比哈希顺序好读得多。
     *
     * @param sizeBytes 文件字节数；未知传 [SIZE_UNKNOWN]（或任何负数）。
     */
    fun headersFor(ext: String, sizeBytes: Long): Map<String, String> {
        val type = mimeOf(ext)
        val headers = LinkedHashMap<String, String>(8)

        headers["Content-Type"] = type
        if (sizeBytes >= 0L) {
            headers["Content-Length"] = sizeBytes.toString()
        }
        headers["Accept-Ranges"] = if (sizeBytes > 0L) "bytes" else "none"
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Cache-Control"] = cacheControlOf(ext, sizeBytes)

        return headers
    }

    /**
     * 缓存策略，三分支：
     *
     * - 未知扩展名 → `no-store`：连类型都没认出来的东西，没有任何理由让它躺在中间层缓存里；
     *   万一是被误传的坏文件，缓存一年之后没人查得出来源。
     * - m3u8 → `no-cache`：播放列表是**会变**的，直播时每几秒重新拉一次分片清单。
     *   这里要是给了 `max-age`，用户看到的就是"直播卡在开头那几秒"。
     * - 其余（点播媒体、字幕）→ `public, max-age=86400`：文件名通常带集数与清晰度、
     *   内容不变，一天足够压掉绝大部分回源。大小未知时降成 `no-cache`，
     *   因为长度都没量出来的响应本来就该由缓存去校验。
     */
    fun cacheControlOf(ext: String, sizeBytes: Long): String = when {
        !isKnown(ext) -> "no-store"
        sizeBytes < 0L -> "no-cache"
        isPlaylist(ext) -> "no-cache"
        else -> "public, max-age=86400"
    }

    /**
     * 把响应头渲染成可直接贴进 curl / 抓包对照的文本。
     *
     * 形如 `Content-Type: video/mp4` 一行一条，末尾不带换行——
     * 页面直接塞进 TextView，带尾换行会多出一个空行。
     */
    fun renderHeaders(ext: String, sizeBytes: Long): String =
        headersFor(ext, sizeBytes).entries.joinToString("\n") { "${it.key}: ${it.value}" }

    // ===== 展示辅助 =====

    /**
     * 字节数 → 人能读的大小。
     *
     * 1024 进制，保留一位小数。负数（[SIZE_UNKNOWN]）返回 `—`，
     * 页面据此显示"未知"，而不是显示 `-1 B` 这种一眼像 bug 的东西。
     */
    fun formatSize(sizeBytes: Long): String {
        if (sizeBytes < 0L) return "—"
        if (sizeBytes < 1024L) return "$sizeBytes B"

        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = sizeBytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
