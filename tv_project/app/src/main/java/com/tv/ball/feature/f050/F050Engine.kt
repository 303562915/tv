package com.tv.ball.feature.f050

import kotlin.math.abs

/**
 * F050「模糊匹配容错」的纯逻辑层：只做字符串运算，不碰任何 Android API。
 *
 * 三层处理：
 * 1. [normalize] 归一化——全角转半角、去掉空格与标点、常用繁转简、英文小写；
 * 2. [editDistance] 带上限的编辑距离，超过上限直接返回 -1，不再往下算整张 DP 表；
 * 3. [fuzzyMatch] 逐级放宽的判定：完全一致 → 前缀 → 包含 → 拼音一致 → 拼音包含 →
 *    错字近似 → 拼音近似；[rank] 把一批标题按分数排好序。
 *
 * 命中原因只回字符串键（[REASON_EXACT] 等），翻译交给界面层，引擎里不出现资源 ID。
 */
object F050Engine {

    /** 容错级别下限：只认完全一致。 */
    const val TOLERANCE_MIN = 0

    /** 容错级别上限：容 2 个字符差（错一个字、漏一个字各算一次）。 */
    const val TOLERANCE_MAX = 2

    /** 命中原因：归一化后完全一致。 */
    const val REASON_EXACT = "exact"

    /** 命中原因：标题以查询开头。 */
    const val REASON_PREFIX = "prefix"

    /** 命中原因：标题包含查询。 */
    const val REASON_CONTAINS = "contains"

    /** 命中原因：拼音串完全一致。 */
    const val REASON_PINYIN = "pinyin"

    /** 命中原因：拼音串里包含查询。 */
    const val REASON_PINYIN_PART = "pinyin_part"

    /** 命中原因：错字近似（汉字层的编辑距离在上限内）。 */
    const val REASON_TYPO = "typo"

    /** 命中原因：拼音近似（拼音层的编辑距离在上限内，例如 luse → lanse）。 */
    const val REASON_PINYIN_TYPO = "pinyin_typo"

    private const val SCORE_EXACT = 100
    private const val SCORE_PREFIX = 96
    private const val SCORE_CONTAINS = 92
    private const val SCORE_PINYIN = 88
    private const val SCORE_PINYIN_PART = 84
    private const val SCORE_TYPO = 80
    private const val SCORE_PINYIN_TYPO = 74
    private const val SCORE_STEP = 6

    /**
     * 一条命中。
     *
     * [score] 是排序用的相对分（越大越靠前）；[reasonKey] 说明为什么算命中；
     * [distance] 是实际编辑距离（完全一致、前缀、包含、拼音一致这几类都是 0）；
     * 归一化后的两条串一起带出来，界面上可以直接展示「拿什么去比的」，排查也方便。
     */
    data class Match(
        val title: String,
        val score: Int,
        val reasonKey: String,
        val distance: Int,
        val normalizedTitle: String,
        val normalizedQuery: String
    )

    /**
     * 归一化：整条匹配链路只认这一种写法。
     *
     * - 全角 ASCII（！到 ～ 以及全角空格）转半角；
     * - 只留字母、数字、汉字，空格和标点一律丢掉；
     * - 常用繁体字折叠成简体；
     * - 英文统一小写，输入法的大小写不该影响结果。
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text) {
            var ch = raw
            val code = ch.code
            when {
                ch == '\u3000' -> continue                       // 全角空格
                code in 0xFF01..0xFF5E -> ch = (code - 0xFEE0).toChar()   // 全角 → 半角
            }
            if (!ch.isLetterOrDigit()) continue                  // 空格 / 标点 / 其它符号
            val folded = TRADITIONAL[ch] ?: ch
            sb.append(folded.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * 取拼音串：汉字转成无分隔音节，字母数字原样保留并小写。
     *
     * 表里只覆盖常用字和演示标题里出现的字；表外汉字原样保留，
     * 这样它只会让拼音链路失配，不会造成假命中。
     */
    fun pinyin(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (raw in text) {
            val ch = TRADITIONAL[raw] ?: raw
            val syllable = PINYIN[ch]
            if (syllable != null) {
                sb.append(syllable)
            } else if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 带上限的编辑距离（插入 / 删除 / 替换各算 1）。
     *
     * 返回实际距离；一旦超过 [max] 就返回 -1——调用方只关心「在不在容错范围内」，
     * 不在就直接放弃，不用算到底差多少。逐行算的时候整行最小值已经超过上限就提前收工。
     */
    fun editDistance(a: String, b: String, max: Int): Int {
        if (max < 0) return -1
        if (a == b) return 0
        if (a.isEmpty()) return if (b.length <= max) b.length else -1
        if (b.isEmpty()) return if (a.length <= max) a.length else -1
        if (abs(a.length - b.length) > max) return -1

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val value = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                cur[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > max) return -1
            val swap = prev
            prev = cur
            cur = swap
        }
        val distance = prev[b.length]
        return if (distance <= max) distance else -1
    }

    /**
     * 单条标题的匹配判定，命中返回 [Match]，不命中返回 null（界面据此走空态）。
     *
     * 判定顺序就是「从最硬到最软」：完全一致 → 前缀 → 包含 → 拼音一致 → 拼音包含 →
     * 汉字错字（编辑距离 ≤ [tolerance]）→ 拼音错字（拼音编辑距离 ≤ [tolerance]）。
     * 越靠前的越确定，分也越高；[tolerance] 会被夹到 0..2。
     */
    fun fuzzyMatch(title: String, query: String, tolerance: Int): Match? {
        val tol = tolerance.coerceIn(TOLERANCE_MIN, TOLERANCE_MAX)
        val normalizedQuery = normalize(query)
        val normalizedTitle = normalize(title)
        if (normalizedQuery.isEmpty() || normalizedTitle.isEmpty()) return null

        if (normalizedTitle == normalizedQuery) {
            return Match(title, SCORE_EXACT, REASON_EXACT, 0, normalizedTitle, normalizedQuery)
        }
        if (normalizedTitle.startsWith(normalizedQuery)) {
            return Match(title, SCORE_PREFIX, REASON_PREFIX, 0, normalizedTitle, normalizedQuery)
        }
        if (normalizedTitle.contains(normalizedQuery)) {
            return Match(title, SCORE_CONTAINS, REASON_CONTAINS, 0, normalizedTitle, normalizedQuery)
        }

        val pinyinTitle = pinyin(title)
        val pinyinQuery = pinyin(query)
        if (pinyinQuery.isNotEmpty() && pinyinTitle == pinyinQuery) {
            return Match(title, SCORE_PINYIN, REASON_PINYIN, 0, normalizedTitle, normalizedQuery)
        }
        if (pinyinQuery.length >= 2 && pinyinTitle.contains(pinyinQuery)) {
            return Match(title, SCORE_PINYIN_PART, REASON_PINYIN_PART, 0, normalizedTitle, normalizedQuery)
        }

        val hanDistance = editDistance(normalizedTitle, normalizedQuery, tol)
        if (hanDistance >= 0) {
            return Match(
                title,
                SCORE_TYPO - hanDistance * SCORE_STEP,
                REASON_TYPO,
                hanDistance,
                normalizedTitle,
                normalizedQuery
            )
        }

        val pinyinDistance = if (pinyinQuery.isEmpty() || pinyinTitle.isEmpty()) {
            -1
        } else {
            editDistance(pinyinTitle, pinyinQuery, tol)
        }
        if (pinyinDistance >= 0) {
            return Match(
                title,
                SCORE_PINYIN_TYPO - pinyinDistance * SCORE_STEP,
                REASON_PINYIN_TYPO,
                pinyinDistance,
                normalizedTitle,
                normalizedQuery
            )
        }
        return null
    }

    /**
     * 排序：分高的在前；同分则编辑距离小的在前；再同则短标题在前，最后按字面稳定排序。
     *
     * 输入顺序不影响结果，同一批标题 + 同一句查询永远得到同一个顺序。
     */
    fun rank(titles: List<String>, query: String, tolerance: Int): List<Match> =
        titles.mapNotNull { fuzzyMatch(it, query, tolerance) }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenBy { it.distance }
                    .thenBy { it.normalizedTitle.length }
                    .thenBy { it.title }
            )

    /**
     * 常用繁体 → 简体折叠表。
     *
     * 只收高频字：够了就能让「藍色 海洋」和「蓝色海洋」落到同一条归一化结果上，
     * 剩下的生僻繁体按原字参与比较，最差退化成一次错字匹配。
     */
    private val TRADITIONAL: Map<Char, Char> = mapOf(
        '藍' to '蓝', '視' to '视', '體' to '体', '設' to '设', '劇' to '剧',
        '國' to '国', '學' to '学', '愛' to '爱', '這' to '这', '個' to '个',
        '們' to '们', '時' to '时', '間' to '间', '後' to '后', '來' to '来',
        '東' to '东', '風' to '风', '雲' to '云', '夢' to '梦', '聲' to '声',
        '樂' to '乐', '語' to '语', '書' to '书', '車' to '车', '門' to '门',
        '開' to '开', '關' to '关', '長' to '长', '頭' to '头', '買' to '买',
        '賣' to '卖', '錢' to '钱', '邊' to '边', '裡' to '里', '麵' to '面',
        '麼' to '么', '樣' to '样', '點' to '点', '對' to '对', '錯' to '错',
        '說' to '说', '話' to '话', '認' to '认', '識' to '识', '記' to '记',
        '憶' to '忆', '節' to '节', '慶' to '庆', '餘' to '余', '傳' to '传',
        '億' to '亿', '萬' to '万', '與' to '与', '為' to '为', '無' to '无',
        '覺' to '觉', '見' to '见', '現' to '现', '實' to '实', '際' to '际',
        '總' to '总', '統' to '统', '線' to '线', '網' to '网', '絡' to '络',
        '頁' to '页', '術' to '术', '歸' to '归', '處' to '处', '號' to '号',
        '動' to '动', '畫' to '画', '類' to '类', '題' to '题', '經' to '经',
        '歷' to '历', '歡' to '欢', '響' to '响', '燈' to '灯', '將' to '将'
    )

    /**
     * 汉字 → 拼音音节表。
     *
     * 刻意只覆盖演示标题与查询里出现的字（蓝色海洋、深海迷航、漫长的季节、三体、
     * 第二季、流浪地球、繁花、隐秘的角落、狂飙……），保持引擎无依赖、体积可控。
     */
    private val PINYIN: Map<Char, String> = mapOf(
        '蓝' to "lan", '色' to "se", '海' to "hai", '洋' to "yang",
        '深' to "shen", '迷' to "mi", '航' to "hang", '漫' to "man",
        '长' to "chang", '的' to "de", '季' to "ji", '节' to "jie",
        '三' to "san", '体' to "ti", '第' to "di", '二' to "er",
        '流' to "liu", '浪' to "lang", '地' to "di", '球' to "qiu",
        '繁' to "fan", '花' to "hua", '隐' to "yin", '秘' to "mi",
        '角' to "jiao", '落' to "luo", '狂' to "kuang", '飙' to "biao",
        '星' to "xing", '际' to "ji", '穿' to "chuan", '越' to "yue",
        '时' to "shi", '光' to "guang", '影' to "ying", '城' to "cheng",
        '旧' to "jiu", '事' to "shi", '无' to "wu", '间' to "jian",
        '道' to "dao", '夜' to "ye", '白' to "bai", '天' to "tian",
        '新' to "xin", '世' to "shi", '界' to "jie", '风' to "feng",
        '云' to "yun", '雨' to "yu", '雷' to "lei", '电' to "dian",
        '火' to "huo", '山' to "shan", '河' to "he", '湖' to "hu",
        '江' to "jiang", '南' to "nan", '北' to "bei", '东' to "dong",
        '西' to "xi", '中' to "zhong", '国' to "guo", '人' to "ren",
        '民' to "min", '市' to "shi", '县' to "xian"
    )
}