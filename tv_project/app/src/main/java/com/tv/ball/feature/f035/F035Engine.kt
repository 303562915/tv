package com.tv.ball.feature.f035

/**
 * F035「M3U 播放列表导入导出」的能力层。
 *
 * 只处理 M3U/M3U8 这一种文本格式：把队列按标准写法拼出去，把外来的文本解析回条目。
 * 刻意写成纯 Kotlin：不碰 Android API、不读写文件，剪贴板与页面交互都留给 [F035Activity]，
 * 这样格式细节可以脱离界面单测。
 *
 * 对外的两条主线就是 [export] 与 [parse]：
 * - 导出永远产出 `#EXTM3U` 头 + 每条两行（`#EXTINF:秒,标题` 与地址），行尾统一 `\n`，
 *   因为 M3U8 规定用 UTF-8 + LF，Windows 播放器也认。
 * - 解析不抛异常：认不出的行只计数（[ParseOutcome.skipped]），时长缺失/为负一律归一成 -1
 *   （M3U 里 -1 表示"未知时长"，直播源没有确定长度）。
 */
class F035Engine {

    /**
     * 一条播放条目。
     *
     * @param title 显示名；外来文件没写 EXTINF 标题时这里是空串，由界面兜底
     * @param durationSec 时长（秒）；-1 表示未知/直播，导出时原样写回 -1
     * @param location 地址：http(s)/rtmp/file 等带 scheme 的 URL，或 `/`、`./` 开头的本地路径
     */
    data class Entry(
        val title: String,
        val durationSec: Int,
        val location: String
    )

    /**
     * 解析结果。
     *
     * @param entries 成功识别的条目，顺序与文件里出现顺序一致
     * @param skipped 非法行数量：结构坏掉的 EXTINF、悬空的 EXTINF、不像地址的行、最终没配上地址的 EXTINF
     * @param error 整体性失败的原因（内容为空 / 一条地址都没识别出来）；有内容可展示时为 null
     */
    data class ParseOutcome(
        val entries: List<Entry>,
        val skipped: Int,
        val error: String?
    )

    /**
     * 导出成 M3U8 文本。
     *
     * 地址为空的条目直接丢掉：写出去会变成一行空行，播放器读到那里就断链。
     * 标题里的换行换成空格（EXTINF 一条只能占一行），逗号保留 —— 逗号之后整段都算标题，不会歧义。
     */
    fun export(entries: List<Entry>): String {
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        entries.forEach { entry ->
            val location = singleLine(entry.location)
            if (location.isEmpty()) return@forEach
            val duration = if (entry.durationSec < 0) UNKNOWN_DURATION else entry.durationSec
            out.append(EXTINF_PREFIX).append(duration).append(',')
                .append(singleLine(entry.title)).append('\n')
            out.append(location).append('\n')
        }
        return out.toString()
    }

    /**
     * 解析 M3U/M3U8 文本。
     *
     * 宽松策略，尽量多救回条目：
     * - `#EXTINF:<时长>,<标题>` 记住元信息，紧跟着的第一条地址行与它配对；
     * - 没有 EXTINF 的裸地址行也算一条（标题空、时长 -1），很多播放器导出的就是纯地址列表；
     * - 其余 `#` 开头的行（`#EXTM3U`、`#EXTVLCOPT`、注释）静默忽略，不算错误行；
     * - 认不出的正文行、悬空的 EXTINF 都只累加 [ParseOutcome.skipped]，绝不抛异常。
     */
    fun parse(text: String?): ParseOutcome {
        if (text.isNullOrBlank()) {
            return ParseOutcome(emptyList(), 0, ERROR_EMPTY)
        }
        val entries = ArrayList<Entry>()
        var skipped = 0
        var pendingTitle: String? = null
        var pendingDuration = UNKNOWN_DURATION

        text.removePrefix("\uFEFF").split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#")) {
                if (line.startsWith(EXTINF_PREFIX, ignoreCase = true)) {
                    val meta = readExtinf(line)
                    if (meta == null) {
                        skipped++
                        pendingTitle = null
                        pendingDuration = UNKNOWN_DURATION
                    } else {
                        pendingTitle = meta.first
                        pendingDuration = meta.second
                    }
                }
                return@forEach
            }

            if (!looksLikeLocation(line)) {
                skipped++
                // 元信息已经扣不上地址，顺手清掉，免得串到后面那条上
                pendingTitle = null
                pendingDuration = UNKNOWN_DURATION
                return@forEach
            }

            val title = pendingTitle
            if (title == null) {
                entries.add(Entry("", UNKNOWN_DURATION, line))
            } else {
                entries.add(Entry(title, pendingDuration, line))
                pendingTitle = null
                pendingDuration = UNKNOWN_DURATION
            }
        }

        // 文件末尾只剩一条 EXTINF，没有地址跟上来
        if (pendingTitle != null) skipped++

        val error = if (entries.isEmpty()) ERROR_NO_ADDRESS else null
        return ParseOutcome(entries, skipped, error)
    }

    /**
     * 读 `#EXTINF:` 后面那段。
     *
     * 真实文件常带属性（`#EXTINF:-1 tvg-id="x" group-title="y",标题`），所以时长只取第一个空格前的 token；
     * 没有逗号说明标题分隔符都丢了，这条算非法行。
     * 时长不是整数时按 -1 处理 —— 部分导出器写 `#EXTINF:None,标题`，标题仍然是有用的。
     */
    private fun readExtinf(line: String): Pair<String, Int>? {
        val body = line.substring(EXTINF_PREFIX.length)
        val comma = body.indexOf(',')
        if (comma < 0) return null
        val durationToken = body.substring(0, comma).trim().substringBefore(' ')
        val duration = durationToken.toIntOrNull() ?: UNKNOWN_DURATION
        val title = body.substring(comma + 1).trim()
        return title to duration
    }

    /**
     * 判断一行正文像不像地址。
     *
     * 带 scheme 的 URL 是主路；本地路径（`/`、`./`、`../`）和常见媒体后缀的裸文件名也认，
     * 因为 M3U 允许相对路径。其余（乱码、说明文字）归入跳过。
     */
    private fun looksLikeLocation(line: String): Boolean {
        if (line.startsWith("/") || line.startsWith("./") || line.startsWith("../")) return true
        val colon = line.indexOf(':')
        if (colon > 0) {
            // scheme 只允许字母开头、字母数字与 +-. —— 顺手排除 "标题：内容" 这类中文冒号行
            val scheme = line.substring(0, colon)
            if (scheme.length <= 12 && scheme.first().isLetter() &&
                scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
            ) {
                return true
            }
        }
        val lower = line.lowercase()
        return MEDIA_SUFFIXES.any { lower.endsWith(it) }
    }

    /** 去掉会破坏"一条条目一行"的换行，其余字符原样保留。 */
    private fun singleLine(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').trim()

    companion object {
        /** 文件头，M3U8 的标识。 */
        const val HEADER = "#EXTM3U"

        /** 未知/直播时长，M3U 约定值。 */
        const val UNKNOWN_DURATION = -1

        private const val EXTINF_PREFIX = "#EXTINF:"

        private const val ERROR_EMPTY = "内容为空，没有可解析的行"
        private const val ERROR_NO_ADDRESS = "没有识别到任何播放地址"

        /** 裸文件名兜底认的媒体后缀。 */
        private val MEDIA_SUFFIXES = listOf(
            ".mp4", ".mkv", ".ts", ".m3u8", ".flv", ".avi", ".mov", ".webm",
            ".mp3", ".aac", ".m4a", ".flac", ".wav"
        )
    }
}