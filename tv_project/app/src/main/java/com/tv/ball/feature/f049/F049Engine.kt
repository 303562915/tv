package com.tv.ball.feature.f049

/**
 * F049「热词与最近查询排序」· 纯 Kotlin 引擎。
 *
 * 不引用任何 Android API，也不碰文件：热词怎么排、最近查询怎么去重、持久化长什么样，
 * 全在这里决定；页面只负责把结果画出来、把点击和输入喂进来。
 *
 * 两条排序规则写死，避免「排行榜」和「输入顺序」互相打架：
 * - 热词：点击数降序；点击数相同按最近一次点击时间降序；再相同按文本升序兜底，
 *   保证同一份数据每次打开的顺序完全一样（排序是稳定的，不会随机抖动）；
 * - 最近查询：时间降序，同一句只留最近一次——重复搜索不该占两条位置。
 */
object F049Engine {

    /** 持久化格式版本，将来字段变了靠它决定怎么读旧文件。 */
    const val VERSION: Int = 1

    /** 首页热词区一次最多展示几个。 */
    const val DEFAULT_HOT_LIMIT: Int = 8

    /** 首页最近查询一次最多展示几条。 */
    const val DEFAULT_RECENT_LIMIT: Int = 10

    /** 最近查询的落盘上限：只留最新的这么多条，防止文件无限长大。 */
    const val MAX_RECENT: Int = 30

    /** 一个热词：文本、被点了多少次、最后一次点它是什么时候。 */
    data class HotWord(
        val text: String,
        val clicks: Int,
        val lastAtMs: Long
    )

    /** 一条最近查询：搜过什么、什么时候搜的。 */
    data class Query(
        val text: String,
        val atMs: Long
    )

    /** 排序键：点击数降序 → 最近点击降序 → 文本升序（稳定兜底）。 */
    private val HOT_ORDER: Comparator<HotWord> =
        compareByDescending<HotWord> { it.clicks }
            .thenByDescending { it.lastAtMs }
            .thenBy { it.text }

    /** 排序键：时间降序；`sortedWith` 是稳定排序，同一毫秒的两条保持原有先后。 */
    private val RECENT_ORDER: Comparator<Query> = compareByDescending<Query> { it.atMs }

    /** 连续空白（含全角空格以外的空白），统一折成一个半角空格。 */
    private val WHITESPACE: Regex = Regex("\\s+")

    /**
     * 按热词规则排序并截取前 [limit] 个。
     *
     * 返回新列表，入参不被修改；空白词直接丢掉（空串点不出有意义的热词）。
     * [limit] <= 0 时返回空列表，语义是「这次不展示」。
     */
    fun hotWords(list: List<HotWord>, limit: Int = DEFAULT_HOT_LIMIT): List<HotWord> {
        val ranked = list.asSequence()
            .filter { it.text.isNotBlank() }
            .sortedWith(HOT_ORDER)
            .toList()
        return takeLimit(ranked, limit)
    }

    /** 按时间倒序去重后截取前 [limit] 条最近查询。 */
    fun recentQueries(list: List<Query>, limit: Int = DEFAULT_RECENT_LIMIT): List<Query> =
        takeLimit(dedupRecent(list), limit)

    /**
     * 去重：同一句只留最近的一次。
     *
     * 先按时间降序排，再顺着扫一遍，见过的文本就跳过——先出现的那条一定是更新的那条。
     * 文本做 [normalize] 后比较：「 三体 」和「三体」算同一句，落地时也统一成规范形式，
     * 否则列表里会出现两条看起来一样的记录。
     */
    fun dedupRecent(list: List<Query>): List<Query> {
        val seen = HashSet<String>(list.size * 2)
        val result = ArrayList<Query>(list.size)
        for (query in list.sortedWith(RECENT_ORDER)) {
            val text = normalize(query.text)
            if (text.isEmpty()) continue
            if (!seen.add(text)) continue
            result.add(query.copy(text = text))
        }
        return result
    }

    /** 规范化：去首尾空白，中间连续空白折成一个空格。空串表示「这次输入不算数」。 */
    fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private fun <T> takeLimit(list: List<T>, limit: Int): List<T> {
        if (limit <= 0) return emptyList()
        return if (list.size <= limit) list else ArrayList(list.subList(0, limit))
    }

    /**
     * 热词表 + 最近查询表。
     *
     * [clock] 注入时间源，测试里给固定值就能断言点击数的时间戳，不用等真实时间。
     * 内部用 LinkedHashMap 保持写入顺序（写文件时顺序稳定），读出来一律走排序函数，
     * 所以「哪条在前」只由规则决定，与插入顺序无关。
     */
    class Store(private val clock: () -> Long = { System.currentTimeMillis() }) {

        private val hot = LinkedHashMap<String, HotWord>()
        private val recent = ArrayList<Query>()

        /** 当前热词榜单，已排序并截断。 */
        fun hotWords(limit: Int = DEFAULT_HOT_LIMIT): List<HotWord> =
            F049Engine.hotWords(hot.values.toList(), limit)

        /** 当前最近查询，已去重、按时间倒序并截断。 */
        fun recentQueries(limit: Int = DEFAULT_RECENT_LIMIT): List<Query> =
            F049Engine.recentQueries(recent, limit)

        /** 热词种数（不截断）。 */
        fun hotCount(): Int = hot.size

        /** 最近查询条数（去重后，不截断）。 */
        fun recentCount(): Int = dedupRecent(recent).size

        /** 两份表都是空的——页面据此决定要不要灌演示数据。 */
        fun isEmpty(): Boolean = hot.isEmpty() && recent.isEmpty()

        /**
         * 点一次热词：点击数加一，时间戳换成现在。
         *
         * 没见过的词当成第一次点击（点击数从 1 起），空文本视为无效调用返回 null。
         */
        fun recordClick(text: String, nowMs: Long = clock()): HotWord? {
            val key = normalize(text)
            if (key.isEmpty()) return null
            val next = HotWord(key, (hot[key]?.clicks ?: 0) + 1, nowMs)
            hot[key] = next
            return next
        }

        /**
         * 记一次查询：同一句先摘掉旧记录再压到末尾，保证「一个词只占一条」且时间是新的。
         *
         * 写进来之后顺手按上限裁剪，最近的 [MAX_RECENT] 条之外的老记录直接丢。
         */
        fun recordQuery(text: String, nowMs: Long = clock()): Query? {
            val key = normalize(text)
            if (key.isEmpty()) return null
            recent.removeAll { normalize(it.text) == key }
            val query = Query(key, nowMs)
            recent.add(query)
            trim()
            return query
        }

        /**
         * 原样放回一条热词（读文件 / 灌演示数据用）。
         *
         * 不走 [recordClick]：文件里的点击数和时间都必须保留，否则每次启动都被抹成
         * 「点过 1 次、刚才点的」，榜单就永远是平的。
         */
        fun restoreHot(word: HotWord) {
            val key = normalize(word.text)
            if (key.isEmpty()) return
            hot[key] = HotWord(key, word.clicks.coerceAtLeast(0), word.lastAtMs)
        }

        /** 原样放回一条最近查询（保留时间戳，重复文本由 [recentQueries] 去重）。 */
        fun restoreQuery(query: Query) {
            val key = normalize(query.text)
            if (key.isEmpty()) return
            recent.add(Query(key, query.atMs))
        }

        /** 清空最近查询，返回清掉的条数（热词榜不受影响：热词是「热」，不是「最近」）。 */
        fun clearRecent(): Int {
            val removed = recent.size
            recent.clear()
            return removed
        }

        /** 按上限裁剪最近查询：留时间最新的 [MAX_RECENT] 条，顺手去重。 */
        fun trim() {
            if (recent.size <= MAX_RECENT) return
            val kept = dedupRecent(recent).take(MAX_RECENT)
            recent.clear()
            recent.addAll(kept)
        }

        /** 序列化成 JSON。手写而不是用 org.json：这一层要能在 JVM 单测里跑，不依赖 Android。 */
        fun toJson(): String {
            val builder = StringBuilder(256)
            builder.append("{\"version\":").append(VERSION).append(",\"hotWords\":[")
            var first = true
            for (word in F049Engine.hotWords(hot.values.toList(), Int.MAX_VALUE)) {
                if (!first) builder.append(',')
                first = false
                builder.append("{\"text\":").append(quote(word.text))
                    .append(",\"clicks\":").append(word.clicks)
                    .append(",\"lastAtMs\":").append(word.lastAtMs)
                    .append('}')
            }
            builder.append("],\"queries\":[")
            first = true
            for (query in dedupRecent(recent)) {
                if (!first) builder.append(',')
                first = false
                builder.append("{\"text\":").append(quote(query.text))
                    .append(",\"atMs\":").append(query.atMs)
                    .append('}')
            }
            builder.append("]}")
            return builder.toString()
        }
    }

    /**
     * 解析持久化 JSON。
     *
     * 容忍三种坏输入：null / 空白 / 结构不对——一律返回空表；单条记录字段缺失或类型不对
     * 只丢这一条，其余继续读。热词和最近查询都是「锦上添花」的数据，读坏了顶多没有榜单，
     * 不该让首页打不开。
     */
    fun parse(json: String?, clock: () -> Long = { System.currentTimeMillis() }): Store {
        val store = Store(clock)
        if (json.isNullOrBlank()) return store
        val root = runCatching { MiniJson.parseObject(json) }.getOrNull() ?: return store

        val hotItems = root["hotWords"] as? List<*> ?: emptyList<Any?>()
        for (element in hotItems) {
            val item = element as? Map<*, *> ?: continue
            val text = item["text"] as? String ?: continue
            val clicks = (item["clicks"] as? Double)?.toInt() ?: 0
            val lastAtMs = (item["lastAtMs"] as? Double)?.toLong() ?: 0L
            store.restoreHot(HotWord(text, clicks, lastAtMs))
        }

        val queryItems = root["queries"] as? List<*> ?: emptyList<Any?>()
        for (element in queryItems) {
            val item = element as? Map<*, *> ?: continue
            val text = item["text"] as? String ?: continue
            val atMs = (item["atMs"] as? Double)?.toLong() ?: 0L
            store.restoreQuery(Query(text, atMs))
        }

        store.trim()
        return store
    }

    /** JSON 字符串转义：只处理必须转义的字符，够覆盖搜索词的实际取值。 */
    private fun quote(raw: String): String {
        val builder = StringBuilder(raw.length + 2)
        builder.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') {
                    builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    /**
     * 极简 JSON 读取器。
     *
     * 只实现到「能把这份持久化文件读回来」为止：对象 / 数组 / 字符串 / 数字 / true / false / null。
     * 遇到不认识的字符直接抛，外层 runCatching 兜成空表。
     */
    private object MiniJson {

        fun parseObject(text: String): Map<String, Any?> {
            val reader = Reader(text)
            val value = reader.readValue()
            return value as? Map<String, Any?> ?: emptyMap()
        }

        private class Reader(private val src: String) {

            private var pos = 0

            private fun skipSpace() {
                while (pos < src.length && src[pos].isWhitespace()) pos++
            }

            fun readValue(): Any? {
                skipSpace()
                if (pos >= src.length) throw IllegalArgumentException("JSON 提前结束")
                return when (val ch = src[pos]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    't' -> readLiteral("true", true)
                    'f' -> readLiteral("false", false)
                    'n' -> readLiteral("null", null)
                    else -> if (ch == '-' || ch.isDigit()) {
                        readNumber()
                    } else {
                        throw IllegalArgumentException("JSON 里出现意外字符 '$ch'")
                    }
                }
            }

            private fun readObject(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                pos++ // 跳过 '{'
                skipSpace()
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                while (true) {
                    skipSpace()
                    val key = readString()
                    skipSpace()
                    if (pos >= src.length || src[pos] != ':') throw IllegalArgumentException("缺少 ':'")
                    pos++
                    map[key] = readValue()
                    skipSpace()
                    if (pos >= src.length) throw IllegalArgumentException("对象没有闭合")
                    when (src[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return map
                        }
                        else -> throw IllegalArgumentException("对象里出现意外字符 '${src[pos]}'")
                    }
                }
            }

            private fun readArray(): List<Any?> {
                val list = ArrayList<Any?>()
                pos++ // 跳过 '['
                skipSpace()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (true) {
                    list.add(readValue())
                    skipSpace()
                    if (pos >= src.length) throw IllegalArgumentException("数组没有闭合")
                    when (src[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return list
                        }
                        else -> throw IllegalArgumentException("数组里出现意外字符 '${src[pos]}'")
                    }
                }
            }

            private fun readString(): String {
                if (pos >= src.length || src[pos] != '"') throw IllegalArgumentException("缺少字符串引号")
                pos++
                val builder = StringBuilder()
                while (pos < src.length) {
                    when (val ch = src[pos++]) {
                        '"' -> return builder.toString()
                        '\\' -> {
                            if (pos >= src.length) throw IllegalArgumentException("转义被截断")
                            when (val esc = src[pos++]) {
                                '"' -> builder.append('"')
                                '\\' -> builder.append('\\')
                                '/' -> builder.append('/')
                                'b' -> builder.append('\b')
                                'f' -> builder.append('\u000C')
                                'n' -> builder.append('\n')
                                'r' -> builder.append('\r')
                                't' -> builder.append('\t')
                                'u' -> {
                                    if (pos + 4 > src.length) throw IllegalArgumentException("\\u 转义被截断")
                                    val hex = src.substring(pos, pos + 4)
                                    pos += 4
                                    builder.append(hex.toInt(16).toChar())
                                }
                                else -> throw IllegalArgumentException("未知转义 '\\$esc'")
                            }
                        }
                        else -> builder.append(ch)
                    }
                }
                throw IllegalArgumentException("字符串没有闭合")
            }

            private fun readNumber(): Double {
                val start = pos
                if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
                while (pos < src.length && isNumberChar(src[pos])) pos++
                val text = src.substring(start, pos)
                return text.toDoubleOrNull() ?: throw IllegalArgumentException("数字格式不对：$text")
            }

            private fun isNumberChar(ch: Char): Boolean =
                ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-'

            private fun <T> readLiteral(literal: String, value: T): T {
                if (!src.startsWith(literal, pos)) {
                    throw IllegalArgumentException("字面量不对：期望 $literal")
                }
                pos += literal.length
                return value
            }
        }
    }
}
