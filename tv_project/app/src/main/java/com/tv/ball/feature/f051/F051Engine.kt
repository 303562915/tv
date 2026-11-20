package com.tv.ball.feature.f051

/**
 * F051 的命中区间计算（纯 Kotlin，不碰 Android API）。
 *
 * 一件事：给定一段文本和一组关键词，算出「哪几段字符要被高亮」。
 * - [parseKeywords]：把「空格分隔」的输入拆成去重关键词；
 * - [findRanges]：多词全部扫描 -> 排序 -> 合并重叠 / 相邻区间，返回互不重叠的区间；
 * - [highlight]：用 [MARK_START] / [MARK_END] 把命中段包起来，方便日志和测试里直接看结果。
 *
 * 匹配只做字面匹配（不是正则），因此 `list.regex` 里的点号不会被当成通配符；
 * 大小写不敏感走 [String.regionMatches]，命中下标始终与原文对齐。
 */
object F051Engine {

    /** [highlight] 输出的命中区间左标记。 */
    const val MARK_START: String = "[["

    /** [highlight] 输出的命中区间右标记。 */
    const val MARK_END: String = "]]"

    /** 一次输入最多认多少个关键词，防止极端输入把界面拖死。 */
    const val MAX_KEYWORDS: Int = 16

    /** 单个关键词最多保留多少字符。 */
    const val MAX_KEYWORD_LENGTH: Int = 32

    /**
     * 拆分关键词：按空白切分，去掉空串，忽略大小写去重，保序，最多 [MAX_KEYWORDS] 个。
     *
     * 单个词超过 [MAX_KEYWORD_LENGTH] 时截断，避免整段文本被当成一个词。
     */
    fun parseKeywords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (token in raw.trim().split(WHITESPACE)) {
            var word = token.trim()
            if (word.isEmpty()) continue
            if (word.length > MAX_KEYWORD_LENGTH) word = word.substring(0, MAX_KEYWORD_LENGTH)
            val key = word.lowercase()
            if (!seen.add(key)) continue
            out.add(word)
            if (out.size >= MAX_KEYWORDS) break
        }
        return out
    }

    /**
     * 找出文本里所有命中区间，按起点升序、互不重叠。
     *
     * 多个关键词各自扫描一遍，命中就收一条 `start until end`；
     * 之后统一排序合并：`nextStart <= currentLast + 1` 视为重叠或紧邻，直接并成一个区间
     * （紧邻也合并，避免「热点」「热门」两个词挨着时画出两段相邻高亮）。
     *
     * @param text 待扫描文本
     * @param keywords 关键词表，空串会被跳过
     * @param ignoreCase true 时大小写不敏感（默认）
     */
    fun findRanges(
        text: String,
        keywords: List<String>,
        ignoreCase: Boolean = true
    ): List<IntRange> {
        if (text.isEmpty() || keywords.isEmpty()) return emptyList()

        val raw = ArrayList<IntRange>()

        // 直接用 regionMatches 逐位比对，不做 lowercase 折叠：
        // 折叠后个别字符（如 'İ'）会变长，区间就和原文对不上了。
        for (keyword in keywords) {
            val length = keyword.length
            if (length == 0 || length > text.length) continue
            var index = 0
            while (index <= text.length - length) {
                if (text.regionMatches(index, keyword, 0, length, ignoreCase)) {
                    raw.add(index until index + length)
                    index += length
                } else {
                    index += 1
                }
            }
        }

        if (raw.isEmpty()) return emptyList()
        raw.sortWith(compareBy({ it.first }, { it.last }))

        val merged = ArrayList<IntRange>()
        var start = raw[0].first
        var end = raw[0].last
        for (index in 1 until raw.size) {
            val range = raw[index]
            if (range.first <= end + 1) {
                if (range.last > end) end = range.last
            } else {
                merged.add(start..end)
                start = range.first
                end = range.last
            }
        }
        merged.add(start..end)
        return merged
    }

    /**
     * 用 [MARK_START] / [MARK_END] 标出命中区间。
     *
     * 例：`highlight("流浪地球2 与 流浪地球1", listOf("流浪地球"))`
     * -> `[[流浪地球]]2 与 [[流浪地球]]1`
     *
     * 没命中时原样返回（连空白都不动），方便调用方直接比对。
     */
    fun highlight(text: String, keywords: List<String>): String {
        val ranges = findRanges(text, keywords)
        if (ranges.isEmpty()) return text

        val sb = StringBuilder(text.length + ranges.size * (MARK_START.length + MARK_END.length))
        var cursor = 0
        for (range in ranges) {
            if (range.first > cursor) sb.append(text, cursor, range.first)
            sb.append(MARK_START)
            sb.append(text, range.first, range.last + 1)
            sb.append(MARK_END)
            cursor = range.last + 1
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /**
     * 统计命中：返回每个关键词各自的命中次数（用区间去重后的口径统计），
     * 与 [findRanges] 合并后的区间一起用于界面上的「命中词数」。
     */
    fun countHits(text: String, keywords: List<String>, ignoreCase: Boolean = true): Int =
        findRanges(text, keywords, ignoreCase).size

    private val WHITESPACE = Regex("\\s+")
}
