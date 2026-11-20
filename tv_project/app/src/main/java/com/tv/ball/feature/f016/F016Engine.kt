package com.tv.ball.feature.f016

import java.util.Locale

/**
 * 「双语双行字幕合成」的纯计算核心。
 *
 * 两条时间轴（主字幕 / 副字幕）各自是一串 [Cue]，合成时只看「时间边界」：
 * 把两边的 startMs / endMs 合在一起排序去重，得到一条分段边界表；
 * 每相邻两个边界之间就是一个时间片段，片段内不可能有任何字幕开始或结束。
 * 对该片段取一个中点，看它落在哪几条主字幕、哪几条副字幕里，就得到这一片段的双行内容。
 * 这样两边的任意错位都能被精确切开，不依赖「每 P 条字幕对齐一次」这种脆弱假设。
 *
 * 合并规则（起止取并集 / 交集）：
 * - 相邻片段的内容（主行文案 + 副行文案）完全一致时合并成一条，时间取**并集**：
 *   start = 前一条 start，end = 后一条 end。
 *   SRT 里常见「一句话被拆成两条叠着显示」（上一句 end = 下一句 start 或略错开），
 *   并集后才会还原成一整句，而不是被切成两条残句。
 * - 内容不一致就断开，新的一条从本片段起点开始，时间取本片段的**交集**边界——
 *   也就是两条字幕真正共有的那段，保证视频任意时刻去查，拿到的是当时真实可见的内容。
 * - 片段两侧的「矩形覆盖」缺口（只出现主字幕或只出现副字幕）本身就是一条单行结果，
 *   缺失的另一边为 null，界面上只画一行。
 */
object F016Engine {

    /** 单条字幕。时间单位毫秒，[endMs] 必须大于 [startMs]，[text] 已去首尾空白。 */
    data class Cue(val startMs: Long, val endMs: Long, val text: String) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * 合成后的一条结果。
     *
     * 两行都有 → 重叠区间，上行是 [primaryText]、下行是 [secondaryText]；
     * 只有一行 → 只有那一路字幕覆盖这个区间。
     */
    data class MergedCue(
        val startMs: Long,
        val endMs: Long,
        val primaryText: String?,
        val secondaryText: String?
    ) {
        val durationMs: Long get() = endMs - startMs

        /** 是否双行（主副同时有内容）。 */
        val isOverlap: Boolean get() = primaryText != null && secondaryText != null

        /** 时间轴上的一行（或两行）显示内容，主行在上。 */
        val displayLines: List<String> get() = listOfNotNull(primaryText, secondaryText)
    }

    /** 合成统计，给界面上的「合成条数 / 重叠条数」用。 */
    data class Summary(
        val total: Int,
        val overlap: Int,
        val primaryOnly: Int,
        val secondaryOnly: Int
    ) {
        val single: Int get() = primaryOnly + secondaryOnly
    }

    private const val TIME_SEPARATOR = "-->"

    /** 单个时间点：`00:00:01,000`。 */
    private val SRT_TIME_RE =
        Regex("""(\d{1,3}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")

    // ------------------------------------------------------------------ 合成

    /**
     * 按时间轴合成两条字幕。
     *
     * @param primary 主字幕（重叠时显示在上行）
     * @param secondary 副字幕（重叠时显示在下行）
     * @return 按 startMs 升序、互不重叠、相邻同类片段已并成一条的结果列表
     */
    fun merge(primary: List<Cue>, secondary: List<Cue>): List<MergedCue> {
        // 脏数据先清掉：结束不晚于开始、空文案的字幕不参与合成
        val a = sanitize(primary)
        val b = sanitize(secondary)
        if (a.isEmpty() && b.isEmpty()) return emptyList()

        // 1. 时间边界表：两边的 start / end 全部丢进去排序去重
        val boundaries = sortedSetOf<Long>()
        a.forEach { boundaries.add(it.startMs); boundaries.add(it.endMs) }
        b.forEach { boundaries.add(it.startMs); boundaries.add(it.endMs) }
        val points = boundaries.toList()

        // 2. 逐片段取中点判断覆盖情况；边界本身不做判断，避免相邻字幕的首尾相接被算成重叠
        val segments = ArrayList<MergedCue>(points.size)
        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            if (end <= start) continue
            val probe = start + (end - start) / 2

            val pa = activeTextAt(a, probe)
            val pb = activeTextAt(b, probe)
            if (pa == null && pb == null) continue

            // 3. 与上一条内容完全一致 → 并集合并；否则新起一条
            val last = segments.lastOrNull()
            if (last != null && last.endMs == start &&
                last.primaryText == pa && last.secondaryText == pb
            ) {
                segments[segments.size - 1] = last.copy(endMs = end)
            } else {
                segments.add(MergedCue(start, end, pa, pb))
            }
        }
        return segments
    }

    /** 合成结果的统计。 */
    fun summarize(merged: List<MergedCue>): Summary {
        var primaryOnly = 0
        var secondaryOnly = 0
        for (item in merged) {
            when {
                item.isOverlap -> Unit
                item.primaryText != null -> primaryOnly++
                else -> secondaryOnly++
            }
        }
        return Summary(
            total = merged.size,
            overlap = merged.count { it.isOverlap },
            primaryOnly = primaryOnly,
            secondaryOnly = secondaryOnly
        )
    }

    // ------------------------------------------------------------------ 展示

    /**
     * 两条字幕轨道在 [startMs, endMs] 上的覆盖率。
     *
     * 用来在界面上解释「重叠」有多实：1.0 表示这段时间两路字幕都有内容。
     * 区间为空或任一路是空的轨道时返回 0。
     */
    fun overlapRatio(primary: List<Cue>, secondary: List<Cue>, startMs: Long, endMs: Long): Float {
        if (endMs <= startMs) return 0f
        val covered = (coveredMs(primary, startMs, endMs) + coveredMs(secondary, startMs, endMs)) / 2f
        return (covered / (endMs - startMs)).coerceIn(0f, 1f)
    }

    /** 整条时间轴上的覆盖率：两路字幕轨道都铺满时常为 1.0。 */
    fun overlapRatio(primary: List<Cue>, secondary: List<Cue>): Float {
        val span = timelineSpan(primary, secondary) ?: return 0f
        return overlapRatio(primary, secondary, span.first, span.second)
    }

    /** 单条合成结果的重叠占比：双行时按两路覆盖算，单行结果自然偏低。 */
    fun overlapRatio(merged: MergedCue): Float = when {
        merged.durationMs <= 0L -> 0f
        merged.isOverlap -> 1f
        else -> 0.5f
    }

    /** 整条时间轴（两路字幕的并集起止）；都没有时返回 null。 */
    fun timelineSpan(primary: List<Cue>, secondary: List<Cue>): Pair<Long, Long>? {
        val all = primary + secondary
        if (all.isEmpty()) return null
        val start = all.minOf { it.startMs }
        val end = all.maxOf { it.endMs }
        return if (end > start) start to end else null
    }

    /** 毫秒 → `mm:ss.mmm`，界面上的时间轴列。 */
    fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    /** 毫秒 → SRT 风格 `HH:mm:ss,mmm`，报告文案里用。 */
    fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    // ------------------------------------------------------------------ SRT 解析

    /**
     * 解析 SRT 文本。
     *
     * 容忍的偏差：序号行可缺、时间行用逗号或点、正文多行（用空格并成一行）、
     * 空行错位、CRLF。解析不出时间行的块直接跳过，不让一条坏字幕毁掉整份文件。
     */
    fun parseSrt(raw: String): List<Cue> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val result = ArrayList<Cue>()

        val blocks = normalized.split(Regex("""\n[ \t]*\n"""))
        for (block in blocks) {
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            // 时间行是块里第一个含 "-->" 的行，它前面的都是序号
            val timeIndex = lines.indexOfFirst { it.contains(TIME_SEPARATOR) }
            if (timeIndex < 0 || timeIndex >= lines.size - 1) continue

            val times = lines[timeIndex].split(TIME_SEPARATOR)
            if (times.size < 2) continue
            val startMs = parseSrtTime(times[0]) ?: continue
            val endMs = parseSrtTime(times[1]) ?: continue
            if (endMs <= startMs) continue

            // 时间行之后全部是正文；空行已在上面滤掉，这里并成一行保证两行显示固定
            val text = lines.drop(timeIndex + 1).joinToString(" ")
            if (text.isEmpty()) continue

            result.add(Cue(startMs, endMs, text))
        }
        // 按开始时间排好，后面取中点的逻辑依赖有序输入更省事
        result.sortWith(compareBy({ it.startMs }, { it.endMs }))
        return result
    }

    /** `00:01:02,345` / `00:01:02.345` → 毫秒；解析失败返回 null。 */
    fun parseSrtTime(raw: String): Long? {
        val match = SRT_TIME_RE.find(raw) ?: return null
        val (h, m, s, ms) = match.destructured
        val hours = h.toLongOrNull() ?: return null
        val minutes = m.toLongOrNull() ?: return null
        val seconds = s.toLongOrNull() ?: return null
        // 毫秒部分按位数补零：",5" 是 500ms
        val millis = (ms.padEnd(3, '0')).toLongOrNull() ?: return null
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    /** 把字幕列表再渲染成 SRT 文本，界面上「合成结果」以外的导出对照用。 */
    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(formatSrtTime(cue.startMs))
                .append(" --> ")
                .append(formatSrtTime(cue.endMs))
                .append('\n')
            append(cue.text).append('\n')
            if (index != cues.lastIndex) append('\n')
        }
    }

    // ------------------------------------------------------------------ 示例数据

    /** 主字幕示例（中文）。故意设计成：03.5s 处起与副字幕错开、07s 处副字幕先收。 */
    const val SAMPLE_PRIMARY: String = """1
00:00:01,000 --> 00:00:03,500
比赛开始了

2
00:00:03,500 --> 00:00:06,000
他把球传给了队友

3
00:00:06,000 --> 00:00:07,200
一脚劲射

4
00:00:09,000 --> 00:00:11,500
球进了！
"""

    /** 副字幕示例（英文）。与主字幕有三段重叠、两段各自独立。 */
    const val SAMPLE_SECONDARY: String = """1
00:00:01,000 --> 00:00:02,400
The match is under way
Keep your eyes on the ball

2
00:00:03,200 --> 00:00:05,800
He passes it to his teammate

3
00:00:06,500 --> 00:00:10,000
A powerful strike

4
00:00:10,800 --> 00:00:12,000
What a goal!
"""

    // ------------------------------------------------------------------ 内部

    /** 去掉空文案、非法时间；同文同时的重叠条目只留一条，避免中点判断拿到两份同文本。 */
    private fun sanitize(source: List<Cue>): List<Cue> =
        source.asSequence()
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .map { Cue(it.startMs, it.endMs, it.text.trim()) }
            .distinct()
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
            .toList()

    /**
     * [atMs] 时刻该轨道的可见文本。
     *
     * 区间用左闭右开：字幕 A 的 end 恰好等于字幕 B 的 start 时，这一刻算 B，
     * 否则首尾相接会被误判成重叠而多出一条双行。
     */
    private fun activeTextAt(cues: List<Cue>, atMs: Long): String? {
        val hit = cues.filter { atMs >= it.startMs && atMs < it.endMs }
        if (hit.isEmpty()) return null
        // 同一轨道内若有重叠（坏数据），多条并成一行，不丢内容
        return hit.joinToString(" ") { it.text }
    }

    /** 轨道在 [startMs, endMs) 内被覆盖的毫秒数（多重覆盖只算一次）。 */
    private fun coveredMs(cues: List<Cue>, startMs: Long, endMs: Long): Long {
        var covered = 0L
        var cursor = startMs
        for (cue in cues) {
            val from = maxOf(cue.startMs, startMs, cursor)
            val to = minOf(cue.endMs, endMs)
            if (to <= from) continue
            covered += to - from
            cursor = to
        }
        return covered
    }
}
