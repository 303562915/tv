package com.tv.ball.feature.f017

/**
 * F017 字幕时间轴平移 · 纯 Kotlin 逻辑层（不依赖 Android，方便单测）。
 *
 * 平移规则：
 * - 起点 / 终点同时加上 [shift]；
 * - 负值一律截断到 0（不允许出现负时间轴）；
 * - 截断后终点 <= 0 或终点 <= 起点 的 cue 直接丢弃（已经不可能正常显示）。
 */
data class F017Cue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object F017Engine {

    /** 4 条示例 cue，覆盖普通行、跨行、短行与零偏移起点。 */
    fun sampleCues(): List<F017Cue> = listOf(
        F017Cue(0L, 1_200L, "第一句：故事开始"),
        F017Cue(1_500L, 3_400L, "第二句：镜头切到街道"),
        F017Cue(4_000L, 4_800L, "第三句：短促对白"),
        F017Cue(6_200L, 9_000L, "第四句：这一句比较长，用来观察长字幕的平移效果")
    )

    /**
     * 按 [deltaMs] 平移所有 cue。
     *
     * @param cues 原始 cue 序列
     * @param deltaMs 平移量，正值向后推迟，负值提前；负值结果截断到 0
     */
    fun shift(cues: List<F017Cue>, deltaMs: Long): List<F017Cue> = cues.mapNotNull { cue ->
        val start = (cue.startMs + deltaMs).coerceAtLeast(0L)
        val end = (cue.endMs + deltaMs).coerceAtLeast(0L)
        if (end <= 0L || end <= start) {
            null
        } else {
            cue.copy(startMs = start, endMs = end)
        }
    }

    /** 毫秒格式化成 `mm:ss.SSS`（超过 1 小时自动进位到 `HH:mm:ss.SSS`）。 */
    fun format(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = safe / 1000L
        val millis = safe % 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
        } else {
            String.format("%02d:%02d.%03d", minutes, seconds, millis)
        }
    }

    /** 平移量展示用，带正负号：`+1.5s` / `-0.3s` / `0.0s`。 */
    fun formatShift(deltaMs: Long): String {
        val sign = if (deltaMs > 0L) "+" else if (deltaMs < 0L) "-" else ""
        val abs = if (deltaMs < 0L) -deltaMs else deltaMs
        return String.format("%s%.1fs", sign, abs / 1000.0)
    }
}
