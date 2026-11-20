package com.tv.ball.feature.f011

/**
 * 字幕文件解析：SRT / WebVTT / ASS。
 *
 * 三种格式的共同点都是「一段时间 + 一段文本」，差异在头部与分隔符。
 * 解析失败时给出**具体位置与原因**（第几行、哪一段），因为电视上没法看日志，
 * 用户只会看到页面上的那行提示，说清楚比抛异常有用。
 */
object F011SubtitleParser {

    data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

    data class Result(
        val cues: List<Cue> = emptyList(),
        val format: Format = Format.UNKNOWN,
        val error: Error? = null
    ) {
        val ok: Boolean get() = error == null && cues.isNotEmpty()
    }

    enum class Format { SRT, VTT, ASS, UNKNOWN }

    enum class Error(val line: Int) {
        EMPTY(0),
        BAD_TIMECODE(0),
        NO_CUES(0);

        var detailLine: Int = 0
            private set

        fun at(line: Int): Error {
            detailLine = line
            return this
        }
    }

    private val TIME_RE = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val ASS_TIME_RE = Regex("""(\d{1,2}):(\d{2}):(\d{2})[.](\d{1,2})""")

    fun parse(raw: String): Result {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) return Result(error = Error.EMPTY.at(0))
        return when {
            text.contains("[Script Info]") || text.contains("Dialogue:") -> parseAss(text)
            text.trimStart().startsWith("WEBVTT") -> parseVtt(text)
            else -> parseSrt(text)
        }
    }

    // ---------------------------------------------------------------- SRT

    private fun parseSrt(text: String): Result {
        val lines = text.lines()
        val cues = mutableListOf<Cue>()
        var i = 0
        var index = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            // 序号行可以缺，但时间行必须在
            val timeLine = if (TIME_RE.containsMatchIn(line)) line else {
                i++
                if (i < lines.size && TIME_RE.containsMatchIn(lines[i])) lines[i].trim() else {
                    return Result(format = Format.SRT, error = Error.BAD_TIMECODE.at(i + 1))
                }
            }
            val (start, end) = parseRange(timeLine)
                ?: return Result(format = Format.SRT, error = Error.BAD_TIMECODE.at(i + 1))
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            index++
            cues += Cue(index, start, end, body.toString())
        }
        return if (cues.isEmpty()) Result(format = Format.SRT, error = Error.NO_CUES.at(0))
        else Result(cues, Format.SRT)
    }

    // ---------------------------------------------------------------- VTT

    private fun parseVtt(text: String): Result {
        val lines = text.lines().dropWhile { !it.trim().startsWith("WEBVTT") }.drop(1)
        val cues = mutableListOf<Cue>()
        var i = 0
        var index = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("NOTE") || line.startsWith("STYLE") ||
                line.startsWith("REGION") || line == "WEBVTT"
            ) {
                i++
                continue
            }
            val timeLine = if (line.contains("-->")) line else {
                i++
                if (i < lines.size && lines[i].contains("-->")) lines[i].trim()
                else return Result(format = Format.VTT, error = Error.BAD_TIMECODE.at(i + 1))
            }
            val (start, end) = parseRange(timeLine)
                ?: return Result(format = Format.VTT, error = Error.BAD_TIMECODE.at(i + 1))
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            index++
            cues += Cue(index, start, end, body.toString())
        }
        return if (cues.isEmpty()) Result(format = Format.VTT, error = Error.NO_CUES.at(0))
        else Result(cues, Format.VTT)
    }

    // ---------------------------------------------------------------- ASS

    private fun parseAss(text: String): Result {
        val cues = mutableListOf<Cue>()
        var index = 0
        text.lines().forEach { line ->
            val raw = line.trim()
            if (!raw.startsWith("Dialogue:", ignoreCase = true)) return@forEach
            // 格式固定：Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            val parts = raw.substringAfter(':').split(',', limit = 10)
            if (parts.size < 10) return@forEach
            val start = parseAssTime(parts[1].trim()) ?: return@forEach
            val end = parseAssTime(parts[2].trim()) ?: return@forEach
            // {\i1} 这类内联样式标签在文本预览里先剥掉，免得看不成句
            val body = parts[9].replace(Regex("""\{[^}]*}"""), "").trim()
            if (body.isEmpty()) return@forEach
            index++
            cues += Cue(index, start, end, body)
        }
        return if (cues.isEmpty()) Result(format = Format.ASS, error = Error.NO_CUES.at(0))
        else Result(cues, Format.ASS)
    }

    // ---------------------------------------------------------------- 时间

    private fun parseRange(line: String): Pair<Long, Long>? {
        val found = TIME_RE.findAll(line).toList()
        if (found.size < 2) return null
        val s = toMs(found[0])
        val e = toMs(found[1])
        if (e < s) return null
        return s to e
    }

    private fun toMs(m: MatchResult): Long {
        val (h, mm, ss, ms) = m.destructured
        val millis = ms.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000 + mm.toLong() * 60_000 + ss.toLong() * 1_000 + millis
    }

    private fun parseAssTime(text: String): Long? {
        val m = ASS_TIME_RE.find(text) ?: return null
        val (h, mm, ss, cs) = m.destructured
        return h.toLong() * 3_600_000 + mm.toLong() * 60_000 + ss.toLong() * 1_000 +
            cs.padEnd(2, '0').take(2).toLong() * 10
    }

    fun format(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        val s = ms % 60_000 / 1_000
        val f = ms % 1_000
        return String.format("%02d:%02d:%02d.%03d", h, m, s, f)
    }
}
