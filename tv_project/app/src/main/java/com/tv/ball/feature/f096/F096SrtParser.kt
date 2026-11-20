package com.tv.ball.feature.f096

/**
 * F096 字幕解析（SRT）核心实现。
 *
 * 纯 Kotlin，不依赖 Android / 系统 API，可直接跑 JVM 单元测试。
 * 支持：BOM 头、CRLF / LF / CR 换行、"," 或 "." 毫秒分隔、序号行可缺、多行文本。
 */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

sealed class Result {
    data class Ok(val cues: List<Cue>) : Result()
    data class Fail(val line: Int, val reason: String) : Result()
}

object F096SrtParser {

    private const val BOM = "\uFEFF"

    /** 时间轴行：00:00:01,000 --> 00:00:03,500，毫秒分隔符兼容 "." / ","，行尾可带坐标信息。 */
    private val TIME_LINE = Regex(
        """^\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})(?:\s+\S.*)?$"""
    )

    /** 序号行：纯数字，可缺。 */
    private fun isIndexLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t.all { it in '0'..'9' }
    }

    fun parse(text: String): Result {
        val body = if (text.startsWith(BOM)) text.substring(1) else text
        if (body.isBlank()) return Result.Fail(0, "空字幕内容")

        val lines = body.lines()
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank()) {
                i++
                continue
            }
            val blockStart = i
            var cursor = i

            // 序号行可缺：有则跳过
            if (isIndexLine(lines[cursor])) {
                cursor++
                if (cursor >= lines.size) return Result.Fail(blockStart + 1, "字幕块缺少时间轴行")
            }

            val timeLine = lines[cursor]
            val m = TIME_LINE.matchEntire(timeLine)
                ?: return Result.Fail(cursor + 1, "时间轴格式错误: $timeLine")

            val startMs = toMillis(m.groupValues, 1)
            val endMs = toMillis(m.groupValues, 5)
            if (endMs < startMs) return Result.Fail(cursor + 1, "结束时间早于开始时间: $timeLine")

            cursor++
            val textLines = ArrayList<String>()
            while (cursor < lines.size && !lines[cursor].isBlank()) {
                textLines.add(lines[cursor])
                cursor++
            }
            cues.add(Cue(startMs, endMs, textLines.joinToString("\n")))
            i = cursor
        }

        if (cues.isEmpty()) return Result.Fail(0, "未解析到任何字幕条目")
        return Result.Ok(cues)
    }

    /**
     * 把 4 段分组（时/分/秒/毫秒）换算成毫秒。
     * @param g TIME_LINE 的 groupValues
     * @param at 起始分组下标
     */
    private fun toMillis(g: List<String>, at: Int): Long {
        val h = g[at].toLong()
        val mi = g[at + 1].toLong()
        val s = g[at + 2].toLong()
        val ms = g[at + 3].padEnd(3, '0').toLong()
        return ((h * 60L + mi) * 60L + s) * 1000L + ms
    }
}
