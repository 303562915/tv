package com.tv.ball.feature.f054

import java.util.Locale
import java.util.Random

/**
 * 一条线路的一次测速结果。
 *
 * [measuredAtMs] 是这台机器的墙上时间，缓存是否过期只看它和当前时间的差，
 * 所以结果可以原样落盘、换进程再读回来。
 */
data class SpeedResult(
    val lineId: String,
    val lineName: String,
    val kbps: Int,
    val measuredAtMs: Long
)

/**
 * F054「线路测速缓存」的全部逻辑。
 *
 * 这一层是纯 Kotlin，不碰 Android：
 * 1. [isStale] 判单条是否过期（`now - measuredAt > ttl` 才算过期，时钟回拨不倒着判）；
 * 2. [cache] 剔除过期项并按 lineId 去重（同一条线路只留最新一次测量）；
 * 3. [sorted] 按速度降序，同速用线路名兜底，保证展示顺序稳定；
 * 4. [measure] / [measureAll] 用固定种子生成模拟速度，同一条线路每次都是同一个数；
 * 5. [toJson] / [fromJson] 手写 JSON 读写，不依赖 org.json，单测可以跑在 JVM 上。
 */
object F054Engine {

    /** 一分钟的毫秒数，TTL 全部按分钟给。 */
    const val MINUTE_MS: Long = 60_000L

    /** 需求约定的三档 TTL：1 / 5 / 30 分钟。 */
    val TTL_MINUTES: List<Int> = listOf(1, 5, 30)

    /** 默认 TTL，进页面时没存过就用 5 分钟。 */
    const val DEFAULT_TTL_MINUTES: Int = 5

    /** 模拟速度的固定种子：只影响生成的数值，不参与过期判断。 */
    private const val SEED: Long = 0x5F054L

    /** 速度的取值范围（kbps），2.0 Mbps ~ 48.0 Mbps，够覆盖「能播」到「很流畅」。 */
    private const val KBPS_MIN: Int = 2_000
    private const val KBPS_SPAN: Int = 46_001

    /** 落盘 JSON 的版本号，将来改结构时用它做迁移。 */
    private const val VERSION: Int = 1

    private const val FIELD_VERSION = "version"
    private const val FIELD_RESULTS = "results"
    private const val FIELD_LINE_ID = "lineId"
    private const val FIELD_LINE_NAME = "lineName"
    private const val FIELD_KBPS = "kbps"
    private const val FIELD_AT = "measuredAtMs"

    /**
     * 页面上的 4 条模拟线路。
     *
     * [salt] 是每条线路独有的偏移，混进种子后线路之间不会得到同一组数字。
     */
    data class LineSpec(val id: String, val name: String, val salt: Int)

    val LINES: List<LineSpec> = listOf(
        LineSpec("line_a", "线路 A · 上海", 13),
        LineSpec("line_b", "线路 B · 广州", 47),
        LineSpec("line_c", "线路 C · 香港", 91),
        LineSpec("line_d", "线路 D · 东京", 137)
    )

    /** TTL 分钟数转毫秒；越界值按边界处理，避免负 TTL 把整份缓存一刀切没。 */
    fun ttlMs(minutes: Int): Long = minutes.coerceIn(1, 24 * 60).toLong() * MINUTE_MS

    /**
     * 单条结果是否已过期。
     *
     * 未来时间戳（时钟回拨、手动改过系统时间）一律当作没过期：宁可多展示一条旧的，
     * 也不要把刚测出来的结果判死。
     */
    fun isStale(result: SpeedResult, nowMs: Long, ttlMs: Long): Boolean {
        val age = nowMs - result.measuredAtMs
        if (age <= 0L) return false
        return age > ttlMs
    }

    /**
     * 剔除过期项后的缓存。
     *
     * 同一条线路可能既有一条旧记录又有一条新记录（重测后旧记录还没被覆盖），
     * 这里按 [SpeedResult.measuredAtMs] 留下最新的一条；顺序沿用首次出现的顺序，
     * 展示排序交给 [sorted]。
     */
    fun cache(list: List<SpeedResult>, nowMs: Long, ttlMs: Long): List<SpeedResult> {
        val newest = LinkedHashMap<String, SpeedResult>()
        for (result in list) {
            val old = newest[result.lineId]
            if (old == null || result.measuredAtMs >= old.measuredAtMs) newest[result.lineId] = result
        }
        val out = ArrayList<SpeedResult>(newest.size)
        for (result in newest.values) {
            if (!isStale(result, nowMs, ttlMs)) out.add(result)
        }
        return out
    }

    /** 按速度降序；同速按线路名升序，保证同一批数据每次渲染顺序一致。 */
    fun sorted(list: List<SpeedResult>): List<SpeedResult> =
        list.sortedWith(compareByDescending<SpeedResult> { it.kbps }.thenBy { it.lineName })

    /**
     * 测一条线路。
     *
     * 固定种子 + 线路 salt：同一条线路不管测几次都是同一个速度值，重测只刷新时间戳。
     * 这样「测速」是幂等的，排序不会因为随机抖动每次都变，验收时也能直接对数字。
     */
    fun measure(line: LineSpec, nowMs: Long): SpeedResult {
        val random = Random(SEED * 31 + line.salt)
        val raw = KBPS_MIN + random.nextInt(KBPS_SPAN)
        return SpeedResult(
            lineId = line.id,
            lineName = line.name,
            kbps = raw / 10 * 10,
            measuredAtMs = nowMs
        )
    }

    /** 一次测完全部线路。 */
    fun measureAll(nowMs: Long): List<SpeedResult> = LINES.map { measure(it, nowMs) }

    /** 把新测的结果并进已有列表：同一线路以更新的时间戳为准，其余原样保留。 */
    fun merge(existing: List<SpeedResult>, fresh: List<SpeedResult>): List<SpeedResult> {
        val byId = LinkedHashMap<String, SpeedResult>()
        for (result in existing) byId[result.lineId] = result
        for (result in fresh) {
            val old = byId[result.lineId]
            if (old == null || result.measuredAtMs >= old.measuredAtMs) byId[result.lineId] = result
        }
        return byId.values.toList()
    }

    /** 还剩多久过期；已过期返回 0，交给页面按「已过期」渲染。 */
    fun remainingMs(result: SpeedResult, nowMs: Long, ttlMs: Long): Long =
        (result.measuredAtMs + ttlMs - nowMs).coerceAtLeast(0L)

    /** `12340` -> `12.3 Mbps`，`860` -> `860 Kbps`。 */
    fun formatSpeed(kbps: Int): String =
        if (kbps >= 1000) {
            String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
        } else {
            "$kbps Kbps"
        }

    /** 序列化成 JSON：`{"version":1,"results":[{...}]}`。 */
    fun toJson(list: List<SpeedResult>): String {
        val builder = StringBuilder(64 + list.size * 96)
        builder.append('{').append('"').append(FIELD_VERSION).append("\":").append(VERSION)
        builder.append(",\"").append(FIELD_RESULTS).append("\":[")
        var first = true
        for (result in list) {
            if (!first) builder.append(',')
            first = false
            builder.append('{')
                .append('"').append(FIELD_LINE_ID).append("\":").append(quote(result.lineId))
                .append(",\"").append(FIELD_LINE_NAME).append("\":").append(quote(result.lineName))
                .append(",\"").append(FIELD_KBPS).append("\":").append(result.kbps)
                .append(",\"").append(FIELD_AT).append("\":").append(result.measuredAtMs)
                .append('}')
        }
        builder.append("]}")
        return builder.toString()
    }

    /**
     * 解析 [toJson] 的输出。
     *
     * 坏输入一律当空缓存处理：null、空白、结构不对都返回空列表；单条记录字段缺失或
     * 类型不对只丢这一条，其余继续读。缓存本来就是可以随时丢掉重建的数据。
     */
    fun fromJson(raw: String?): List<SpeedResult> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { MiniJson(raw).readValue() }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root[FIELD_RESULTS] as? List<*> ?: return emptyList()
        val out = ArrayList<SpeedResult>(items.size)
        for (element in items) {
            val obj = element as? Map<*, *> ?: continue
            val lineId = obj[FIELD_LINE_ID] as? String ?: continue
            if (lineId.isEmpty()) continue
            val lineName = obj[FIELD_LINE_NAME] as? String ?: lineId
            val kbps = (obj[FIELD_KBPS] as? Double)?.toInt() ?: continue
            val atMs = (obj[FIELD_AT] as? Double)?.toLong() ?: continue
            if (kbps <= 0 || atMs <= 0L) continue
            out.add(SpeedResult(lineId, lineName, kbps, atMs))
        }
        return out
    }

    /** JSON 字符串转义：线路名里可能出现引号或反斜杠，这几个字符必须转义。 */
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
     * 只够读回自己写的那份 JSON 的极简解析器。
     *
     * 不引 org.json 是为了让这层保持纯 Kotlin：本类的读写都能在 JVM 单测里跑，
     * 不用 Robolectric 或 instrumentation。
     */
    private class MiniJson(private val text: String) {

        private var pos: Int = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) return null
            return when (val ch = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else null
            }
        }

        private fun readObject(): Map<String, Any?>? {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return out
            }
            while (pos < text.length) {
                skipWhitespace()
                val key = readString() ?: return null
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') return null
                pos++
                out[key] = readValue()
                skipWhitespace()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> return null
                }
            }
            return null
        }

        private fun readArray(): List<Any?>? {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return out
            }
            while (pos < text.length) {
                out.add(readValue())
                skipWhitespace()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> return null
                }
            }
            return null
        }

        private fun readString(): String? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++
            val builder = StringBuilder()
            while (pos < text.length) {
                when (val ch = text[pos]) {
                    '"' -> {
                        pos++
                        return builder.toString()
                    }
                    '\\' -> {
                        pos++
                        if (pos >= text.length) return null
                        when (val escaped = text[pos]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) return null
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: return null
                                builder.append(code.toChar())
                                pos += 4
                            }
                            else -> builder.append(escaped)
                        }
                        pos++
                    }
                    else -> {
                        builder.append(ch)
                        pos++
                    }
                }
            }
            return null
        }

        private fun readNumber(): Double? {
            val start = pos
            if (pos < text.length && (text[pos] == '-' || text[pos] == '+')) pos++
            while (pos < text.length) {
                val ch = text[pos]
                if (ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '-' || ch == '+') {
                    pos++
                } else {
                    break
                }
            }
            return text.substring(start, pos).toDoubleOrNull()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) return null
            pos += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
