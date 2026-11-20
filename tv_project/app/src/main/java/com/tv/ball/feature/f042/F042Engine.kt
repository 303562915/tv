package com.tv.ball.feature.f042

/**
 * F042「焦点记忆」的能力层。
 *
 * 纯 Kotlin，不引用任何 Android API：记忆表怎么存、怎么找回来、失效怎么兜底，
 * 全在这里决定；界面只负责把结果画出来、把焦点挪过去。
 *
 * 记忆的键是「页面」（Tab / 列表）的 pageKey，值是这一页最后停留的那条：
 * itemId 是条目的稳定标识（不是下标，列表插入 / 排序后下标会变），
 * index 是记录当时的下标，只作参考——恢复时优先按 itemId 定位，
 * 因为 itemId 才是「同一条内容」的身份。
 */
object F042Engine {

    /** 持久化格式版本，将来字段变了靠它决定怎么读旧文件。 */
    const val VERSION: Int = 1

    /** 第一个页面（Tab）的 key。 */
    const val KEY_PAGE_A: String = "page_a"

    /** 第二个页面（Tab）的 key。 */
    const val KEY_PAGE_B: String = "page_b"

    /** 一条记忆：哪个页面、最后停在哪个条目、当时的第几条、什么时候记的。 */
    data class Record(
        val pageKey: String,
        val itemId: String,
        val index: Int,
        val savedAt: Long
    )

    /**
     * 一次恢复的结果。
     *
     * [fromMemory] 为 true 表示命中了记忆；[fallback] 为 true 表示记忆失效、
     * 已经退回首项（界面会提示一句，避免用户以为「怎么跳回上面了」）。
     * [index] 永远是当前列表里的真实下标，界面拿它去要焦点即可。
     */
    data class Resolved(
        val pageKey: String,
        val itemId: String,
        val index: Int,
        val fromMemory: Boolean,
        val fallback: Boolean
    )

    /**
     * 焦点记忆表。
     *
     * [clock] 注入时间源，测试里给定固定值就能断言 savedAt，不用等真实时间。
     * 内部用 LinkedHashMap 保持写入顺序，[records] 输出时按 pageKey 排一下，
     * 界面列记忆的顺序才是稳定的。
     */
    class FocusMemory(private val clock: () -> Long = { System.currentTimeMillis() }) {

        private val store: LinkedHashMap<String, Record> = LinkedHashMap()

        /** 记下某个页面最后停留的条目。pageKey / itemId 为空视为无效调用，直接忽略。 */
        fun save(pageKey: String, itemId: String, index: Int) {
            if (pageKey.isBlank() || itemId.isBlank()) return
            store[pageKey] = Record(
                pageKey = pageKey,
                itemId = itemId,
                index = if (index < 0) 0 else index,
                savedAt = clock()
            )
        }

        /**
         * 原样放回一条记录（读文件用）。
         *
         * 不走 [save]：文件里的 savedAt 必须保留，否则每次启动都被抹成当前时间，
         * 「这条记忆是什么时候留的」就永远显示成刚才。
         */
        fun load(record: Record) {
            if (record.pageKey.isBlank() || record.itemId.isBlank()) return
            store[record.pageKey] = record.copy(index = if (record.index < 0) 0 else record.index)
        }

        /** 取某个页面的原始记忆；没有就返回 null。 */
        fun restore(pageKey: String): Record? = store[pageKey]

        /** 忘掉某个页面；返回是否真的删掉了一条。 */
        fun forget(pageKey: String): Boolean = store.remove(pageKey) != null

        /** 清空全部记忆，返回清掉的条数。 */
        fun forgetAll(): Int {
            val count = store.size
            store.clear()
            return count
        }

        /** 记忆条数。 */
        fun count(): Int = store.size

        /** 全部记忆，按 pageKey 排序，供界面逐条展示 pageKey → itemId。 */
        fun records(): List<Record> = store.values.sortedBy { it.pageKey }

        /** 记忆里出现过的页面 key。 */
        fun pageKeys(): List<String> = records().map { it.pageKey }

        /**
         * 按当前列表恢复焦点，失效就退回首项。
         *
         * 判定顺序：
         * 1. 没记忆 → 首项，fromMemory=false、fallback=false；
         * 2. itemId 还在列表里 → 用它，下标以实际位置为准（顺手把过期的 index 修正）；
         * 3. itemId 已经不在（内容下架 / 列表换了）→ 退回首项，fallback=true，
         *    并且把这条脏记忆删掉，免得下次还拿它去白跑一遍。
         * 列表为空时没有可落的位置，返回 null。
         */
        fun resolve(pageKey: String, itemIds: List<String>): Resolved? {
            if (itemIds.isEmpty()) return null
            val record = store[pageKey]
                ?: return Resolved(pageKey, itemIds.first(), 0, fromMemory = false, fallback = false)

            val position = itemIds.indexOf(record.itemId)
            if (position >= 0) {
                if (position != record.index) store[pageKey] = record.copy(index = position)
                return Resolved(pageKey, record.itemId, position, fromMemory = true, fallback = false)
            }

            forget(pageKey)
            return Resolved(pageKey, itemIds.first(), 0, fromMemory = false, fallback = true)
        }

        /** 某条记忆在当前列表里是否还有效，界面展示记忆时用它打「已失效」标记。 */
        fun isValid(pageKey: String, itemIds: List<String>): Boolean {
            val record = store[pageKey] ?: return false
            return itemIds.contains(record.itemId)
        }

        /** 序列化成 JSON。手写而不是用 org.json：这层要保持纯 Kotlin，单测跑在 JVM 上。 */
        fun toJson(): String {
            val builder = StringBuilder(64)
            builder.append("{\"version\":").append(VERSION).append(",\"records\":[")
            var first = true
            for (record in records()) {
                if (!first) builder.append(',')
                first = false
                builder.append("{\"pageKey\":").append(quote(record.pageKey))
                    .append(",\"itemId\":").append(quote(record.itemId))
                    .append(",\"index\":").append(record.index)
                    .append(",\"savedAt\":").append(record.savedAt)
                    .append('}')
            }
            builder.append("]}")
            return builder.toString()
        }

        /** 从本对象当前内容再导出一份新表，测试里用来验证 JSON 往返。 */
        fun roundTrip(clock: () -> Long = this.clock): FocusMemory = parse(toJson(), clock)
    }

    /**
     * 解析持久化 JSON。
     *
     * 容忍三种坏输入：null / 空白 / 结构不对——一律返回空记忆。
     * 记忆是「锦上添花」的数据，读坏了顶多回不到上次位置，不该让页面打不开。
     * 单条记录字段缺失或类型不对时只丢这一条，其余继续读。
     */
    fun parse(json: String?, clock: () -> Long = { System.currentTimeMillis() }): FocusMemory {
        val memory = FocusMemory(clock)
        if (json.isNullOrBlank()) return memory
        val root = runCatching { MiniJson.parseObject(json) }.getOrNull() ?: return memory
        val records = root["records"] as? List<*> ?: return memory
        for (element in records) {
            val item = element as? Map<*, *> ?: continue
            val pageKey = item["pageKey"] as? String ?: continue
            val itemId = item["itemId"] as? String ?: continue
            val index = (item["index"] as? Double)?.toInt() ?: 0
            val savedAt = (item["savedAt"] as? Double)?.toLong() ?: 0L
            memory.load(Record(pageKey, itemId, index, savedAt))
        }
        return memory
    }

    /** JSON 字符串转义：只处理必须转义的字符，够覆盖 pageKey / itemId 的实际取值。 */
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
     * 遇到不认识的字符直接抛，外层 runCatching 兜成空记忆。
     */
    private object MiniJson {

        fun parseObject(text: String): Map<String, Any?> {
            val reader = Reader(text)
            val value = reader.readValue()
            return value as? Map<String, Any?> ?: emptyMap()
        }

        private class Reader(private val src: String) {

            private var pos = 0

            fun skipSpace() {
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