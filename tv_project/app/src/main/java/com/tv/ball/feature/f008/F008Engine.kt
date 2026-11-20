package com.tv.ball.feature.f008

import java.util.Locale

/**
 * 音画同步偏移的纯状态机。
 *
 * 只有整数毫秒的加减与边界裁剪，不持有播放器、不碰 Android，
 * 所以「步进多少、到没到边界、校正后该跳到哪」都能脱离页面直接验证。
 *
 * 偏移符号约定：正数表示音轨相对画面往后推（声音更晚出来），负数表示提前。
 */
class F008Engine {

    companion object {
        /** 遥控器点一次的步进。 */
        const val STEP_MS = 50

        /** 下限：音轨最多提前 2 秒。 */
        const val MIN_OFFSET_MS = -2000

        /** 上限：音轨最多延后 2 秒。 */
        const val MAX_OFFSET_MS = 2000

        /** 带符号的偏移文案：`+150 ms` / `-150 ms` / 归零时 `0 ms`。 */
        fun formatOffset(ms: Int): String =
            if (ms == 0) "0 ms" else String.format(Locale.US, "%+d ms", ms)

        /** 秒级辅助文案，方便和 ±2 秒的边界对照。 */
        fun formatSeconds(ms: Int): String = String.format(Locale.US, "%.2f s", ms / 1000.0)

        /** 把任意值裁进允许区间；持久化读回来的脏数据也走这里。 */
        fun clamp(ms: Int): Int = clamp(ms.toLong())

        private fun clamp(sum: Long): Int =
            sum.coerceIn(MIN_OFFSET_MS.toLong(), MAX_OFFSET_MS.toLong()).toInt()
    }

    /** 当前偏移（毫秒），永远落在 [MIN_OFFSET_MS] ~ [MAX_OFFSET_MS] 之间。 */
    var offsetMs: Int = 0
        private set

    /**
     * 按 [deltaMs] 走一步并返回调整后的偏移。
     *
     * 越界不做异常：先按 Long 求和再裁，避免 Int 溢出把偏移翻到反方向；
     * 调用方用 [canAdjust] 先判断按钮能不能按，用返回值刷新界面。
     */
    fun adjust(deltaMs: Int): Int {
        offsetMs = clamp(offsetMs.toLong() + deltaMs)
        return offsetMs
    }

    /** 归零：画面与音轨重新对齐。 */
    fun reset() {
        offsetMs = 0
    }

    /** 是否已经顶到任一边界（-2000 或 +2000）。 */
    fun isAtLimit(): Boolean = isAtLowerLimit() || isAtUpperLimit()

    /** 是否已到下限，此时「减小」方向不能再走。 */
    fun isAtLowerLimit(): Boolean = offsetMs <= MIN_OFFSET_MS

    /** 是否已到上限，此时「增大」方向不能再走。 */
    fun isAtUpperLimit(): Boolean = offsetMs >= MAX_OFFSET_MS

    /** 这一步是否真的能改变偏移；不能就让对应按钮置灰，别让用户按了没反应。 */
    fun canAdjust(deltaMs: Int): Boolean = when {
        deltaMs > 0 -> offsetMs < MAX_OFFSET_MS
        deltaMs < 0 -> offsetMs > MIN_OFFSET_MS
        else -> false
    }

    /**
     * 把偏移应用到一条轨道的时间点上（微秒，与 ExoPlayer 的 currentPosition 同单位）。
     *
     * 返回 `baseUs + offsetMs * 1000`：正偏移把该轨道位置往后推，负偏移提前。
     * 结果夹在 0 以上，避免算出负时间去 seek。
     */
    fun applyTo(baseUs: Long): Long {
        val adjusted = baseUs + offsetMs.toLong() * 1000L
        return if (adjusted < 0L) 0L else adjusted
    }

    /**
     * 用持久化值恢复偏移。
     *
     * @return 原值就在合法区间内为 true；被裁过（历史数据被手改成越界值）为 false，
     *         调用方据此提示「已修正」，而不是悄悄改掉用户看到的数字。
     */
    fun restore(ms: Int): Boolean {
        offsetMs = clamp(ms)
        return offsetMs == ms
    }
}