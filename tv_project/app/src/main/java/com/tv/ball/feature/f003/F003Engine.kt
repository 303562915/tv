package com.tv.ball.feature.f003

import java.util.Locale

/**
 * 时间码解析 / 边界收敛 / 展示格式化。
 *
 * 纯 Kotlin：不持有 Context、不碰播放器，Activity 只负责把结果画出来。
 * 于是「hh:mm:ss / mm:ss / ss 三种写法」「非法字符」「分秒越界」都能脱离界面单独验证。
 *
 * 解析失败不抛异常出去，而是包进 [Result] 并附带 [Fail] 分类，
 * 文案由 Activity 映射成资源：引擎不碰资源，页面也不必猜错误种类。
 */
object F003Engine {

    /** 片长默认 45 分钟，和常见剧集单集时长对齐。 */
    const val DEFAULT_DURATION_MS: Long = 45L * 60L * 1000L

    /** 「片尾前 30 秒」的偏移量。 */
    const val TAIL_OFFSET_MS: Long = 30L * 1000L

    /** 快捷按钮步长：+30 秒 / +5 分钟。 */
    const val SHORT_STEP_MS: Long = 30L * 1000L
    const val LONG_STEP_MS: Long = 5L * 60L * 1000L

    /** 片长可设置范围：太短没意义，太长基本是手滑多打了一位。 */
    const val MIN_DURATION_MS: Long = 10L * 1000L
    const val MAX_DURATION_MS: Long = 12L * 60L * 60L * 1000L

    /** 每段最多 6 位数字：先卡长度再转 Long，避免超长输入把数值算飞。 */
    private const val MAX_PART_DIGITS = 6

    /** 只写秒数（如 `540`）时允许到的上限，等于 99:59:59。 */
    private const val MAX_SECONDS_ONLY = 99L * 3600L + 59L * 60L + 59L

    /** 时段的合理上限；再多就不像时间码了，按越界处理。 */
    private const val MAX_HOURS = 99L

    private const val SECOND_MS = 1000L
    private const val MINUTE_MS = 60L * SECOND_MS
    private const val HOUR_MS = 60L * MINUTE_MS

    /** 解析失败的分类，Activity 按它挑对应文案。 */
    enum class Fail {
        /** 空串或只有空白。 */
        EMPTY,

        /** 带负号。 */
        NEGATIVE,

        /** 出现数字、冒号、小数点以外的字符。 */
        ILLEGAL_CHAR,

        /** 结构不对：段数超过 3、某段为空、多个小数点。 */
        BAD_FORMAT,

        /** 数值越界：分/秒 >= 60、毫秒超过 3 位、小时过大。 */
        OUT_OF_RANGE
    }

    /**
     * 解析失败时携带的错误，[fail] 决定提示文案，[detail] 是出问题的原始片段。
     */
    class TimecodeException(
        val fail: Fail,
        val detail: String = ""
    ) : IllegalArgumentException("timecode $fail: $detail")

    /**
     * 把用户输入解析成毫秒。
     *
     * 支持三种写法（都可带 `.毫秒` 后缀，1~3 位）：
     * - `hh:mm:ss` 例如 `01:23:45`
     * - `mm:ss`    例如 `12:34`
     * - `ss`       例如 `540`
     *
     * 返回 [Result.success] 时是毫秒；失败时异常为 [TimecodeException]。
     * 越界（分/秒 >= 60）算解析失败，是「写错了」；超过片长由调用方判断，是「跳不到」。
     */
    fun parseTimecode(text: String): Result<Long> {
        val raw = text.trim()
        if (raw.isEmpty()) return fail(Fail.EMPTY)

        if (raw.startsWith("-")) return fail(Fail.NEGATIVE, raw)
        if (raw.startsWith("+")) return fail(Fail.ILLEGAL_CHAR, raw)

        val dotIndex = raw.indexOf('.')
        val head: String
        val fraction: String?
        if (dotIndex >= 0) {
            // 第二个小数点是手误，不必猜用户意图
            if (raw.indexOf('.', dotIndex + 1) >= 0) return fail(Fail.BAD_FORMAT, raw)
            head = raw.substring(0, dotIndex)
            fraction = raw.substring(dotIndex + 1)
            if (fraction.isEmpty()) return fail(Fail.BAD_FORMAT, raw)
            if (!isAllDigits(fraction)) return fail(Fail.ILLEGAL_CHAR, fraction)
            if (fraction.length > 3) return fail(Fail.OUT_OF_RANGE, fraction)
        } else {
            head = raw
            fraction = null
        }
        if (head.isEmpty()) return fail(Fail.BAD_FORMAT, raw)

        val parts = head.split(':')
        if (parts.size > 3) return fail(Fail.BAD_FORMAT, head)
        for (part in parts) {
            if (part.isEmpty()) return fail(Fail.BAD_FORMAT, head)
            if (!isAllDigits(part)) return fail(Fail.ILLEGAL_CHAR, part)
            if (part.length > MAX_PART_DIGITS) return fail(Fail.OUT_OF_RANGE, part)
        }

        val numbers = parts.map { it.toLong() }
        val hours: Long
        val minutes: Long
        val seconds: Long
        when (numbers.size) {
            1 -> {
                // 只写数字按秒理解，方便直接敲「540」跳到第 9 分钟
                if (numbers[0] > MAX_SECONDS_ONLY) return fail(Fail.OUT_OF_RANGE, parts[0])
                hours = 0L
                minutes = 0L
                seconds = numbers[0]
            }

            2 -> {
                if (numbers[1] >= 60L) return fail(Fail.OUT_OF_RANGE, parts[1])
                hours = 0L
                minutes = numbers[0]
                seconds = numbers[1]
            }

            else -> {
                if (numbers[1] >= 60L) return fail(Fail.OUT_OF_RANGE, parts[1])
                if (numbers[2] >= 60L) return fail(Fail.OUT_OF_RANGE, parts[2])
                if (numbers[0] > MAX_HOURS) return fail(Fail.OUT_OF_RANGE, parts[0])
                hours = numbers[0]
                minutes = numbers[1]
                seconds = numbers[2]
            }
        }

        // ".5" 表示 500ms，".05" 表示 50ms：补零到毫秒位再取
        val milli = fraction?.padEnd(3, '0')?.toLong() ?: 0L
        return Result.success(hours * HOUR_MS + minutes * MINUTE_MS + seconds * SECOND_MS + milli)
    }

    /** 把毫秒收进 `[0, durationMs]`；片长非法（<= 0）时一律收到 0。 */
    fun clampToDuration(ms: Long, durationMs: Long): Long {
        val duration = if (durationMs < 0L) 0L else durationMs
        return if (ms < 0L) 0L else if (ms > duration) duration else ms
    }

    /** 快捷跳转：当前位置偏移 [deltaMs] 后收进 `[0, 片长]`，撞到片尾就停在片尾。 */
    fun shift(currentMs: Long, deltaMs: Long, durationMs: Long): Long =
        clampToDuration(currentMs + deltaMs, durationMs)

    /** 「片尾前 30 秒」的目标点；片长不足 30 秒时收到片头。 */
    fun tailTarget(durationMs: Long): Long =
        clampToDuration(durationMs - TAIL_OFFSET_MS, durationMs)

    /**
     * 毫秒转 `hh:mm:ss`，一律带小时段，和输入框里的写法对得上，方便对照。
     * 超过 99 小时不截断，位数自然变长。负数按 0 处理。
     */
    fun format(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = safe / SECOND_MS
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun isAllDigits(value: String): Boolean = value.all { it in '0'..'9' }

    private fun fail(reason: Fail, detail: String = ""): Result<Long> =
        Result.failure(TimecodeException(reason, detail))
}
