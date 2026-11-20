package com.tv.ball.feature.f022

import java.util.Locale

/**
 * F022 本地视频筛选 · 纯 Kotlin 引擎。
 *
 * 不依赖 Android / 协程 / IO：入参出参都是普通数据类，方便单测与后续接到真实媒体库扫描结果上。
 *
 * 筛选语义（与 UI 上「生效条件」文案一致）：
 * - 扩展名集合为空 → 表示「不限扩展名」，不是「全部排除」；
 * - [Filter.minDurationMs] / [Filter.maxDurationMs] 传 [UNLIMITED] 表示该端不限；
 * - 大小区间单位为 MB，传 [UNLIMITED] 表示该端不限；比较用字节换算，避免亚 MB 文件被整数截断误判。
 */
object F022Engine {

    /** 区间不限的哨兵值，时长与大小共用。 */
    const val UNLIMITED: Long = -1L

    /** 一 MB 的字节数，用来把「整数 MB」区间换算成字节比较。 */
    private const val BYTES_PER_MB: Long = 1024L * 1024L

    /** 一条本地视频。 */
    data class VideoItem(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val ext: String
    )

    /**
     * 筛选条件。
     *
     * @param exts 允许的扩展名（不带点，大小写不敏感）；空集合代表不限扩展名
     * @param minDurationMs 最短时长，[UNLIMITED] 表示不限
     * @param maxDurationMs 最长时长，[UNLIMITED] 表示不限
     * @param minSizeMb 最小体积（MB），[UNLIMITED] 表示不限
     * @param maxSizeMb 最大体积（MB），[UNLIMITED] 表示不限
     */
    data class Filter(
        val exts: Set<String> = emptySet(),
        val minDurationMs: Long = UNLIMITED,
        val maxDurationMs: Long = UNLIMITED,
        val minSizeMb: Int = -1,
        val maxSizeMb: Int = -1
    )

    /**
     * 按 [filter] 过滤 [items]，顺序保持入参顺序（稳定筛选，不重排）。
     *
     * 扩展名比较前统一去掉点并转小写，避免 ".MP4" 与 "mp4" 被当成两个东西。
     */
    fun apply(items: List<VideoItem>, filter: Filter): List<VideoItem> {
        val wanted = filter.exts
            .asSequence()
            .map { normalizeExt(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        return items.filter { item ->
            matchesExt(item.ext, wanted) &&
                withinRange(item.durationMs, filter.minDurationMs, filter.maxDurationMs) &&
                withinSize(item.sizeBytes, filter.minSizeMb, filter.maxSizeMb)
        }
    }

    /**
     * 把 [filter] 翻成人话，直接显示在页面的「生效条件」区。
     *
     * 示例：`扩展名 mp4/mkv · 时长 ≥ 1 分钟 且 ≤ 10 分钟 · 大小 10–200 MB`。
     */
    fun describe(filter: Filter): String {
        val parts = ArrayList<String>(3)

        val exts = filter.exts
            .asSequence()
            .map { normalizeExt(it) }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()
        parts += if (exts.isEmpty()) "扩展名不限" else "扩展名 " + exts.joinToString("/")

        val duration = joinRange(
            lower = if (filter.minDurationMs == UNLIMITED) null else formatDuration(filter.minDurationMs),
            upper = if (filter.maxDurationMs == UNLIMITED) null else formatDuration(filter.maxDurationMs)
        )
        parts += if (duration == null) "时长不限" else "时长 $duration"

        val minMb = filter.minSizeMb.takeIf { it >= 0 }
        val maxMb = filter.maxSizeMb.takeIf { it >= 0 }
        val size = joinRange(
            lower = minMb?.let { "$it MB" },
            upper = maxMb?.let { "$it MB" }
        )
        parts += if (size == null) "大小不限" else "大小 $size"

        return parts.joinToString(" · ")
    }

    /** 扩展名归一：去点、去空白、转小写。 */
    fun normalizeExt(raw: String): String =
        raw.trim().removePrefix(".").trim().lowercase(Locale.ROOT)

    /** 空集合视为不限；否则必须命中。 */
    private fun matchesExt(ext: String, wanted: Set<String>): Boolean =
        wanted.isEmpty() || normalizeExt(ext) in wanted

    /** 闭区间；[UNLIMITED] 那一端不检查。 */
    private fun withinRange(value: Long, min: Long, max: Long): Boolean {
        if (min != UNLIMITED && value < min) return false
        if (max != UNLIMITED && value > max) return false
        return true
    }

    /** 体积按字节比，避免 0.5 MB 的文件被 minSizeMb=1 之外的情况整数截断算错。 */
    private fun withinSize(sizeBytes: Long, minMb: Int, maxMb: Int): Boolean {
        if (minMb >= 0 && sizeBytes < minMb.toLong() * BYTES_PER_MB) return false
        if (maxMb >= 0 && sizeBytes > maxMb.toLong() * BYTES_PER_MB) return false
        return true
    }

    /** 上下界至少有一端时拼成区间文案。 */
    private fun joinRange(lower: String?, upper: String?): String? = when {
        lower != null && upper != null -> "$lower – $upper"
        lower != null -> "≥ $lower"
        upper != null -> "≤ $upper"
        else -> null
    }

    /** 毫秒转 秒/分钟/小时 的可读时长。 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 秒"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "$hours 小时 $minutes 分"
            minutes > 0L -> if (seconds > 0L) "$minutes 分 $seconds 秒" else "$minutes 分钟"
            else -> "$seconds 秒"
        }
    }

    /** 字节转 MB 文本，保留一位小数，用于列表里的体积列。 */
    fun formatSize(sizeBytes: Long): String {
        val mb = sizeBytes.toDouble() / BYTES_PER_MB
        return if (mb >= 1024.0) {
            String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
        } else {
            String.format(Locale.ROOT, "%.1f MB", mb)
        }
    }
}
