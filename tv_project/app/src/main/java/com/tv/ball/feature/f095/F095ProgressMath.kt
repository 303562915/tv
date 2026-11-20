package com.tv.ball.feature.f095

/**
 * F095 播放进度纯逻辑：进度换算、续播判定、剩余时长估算。
 * 全部为无副作用纯函数，便于单元测试回归。
 */
object F095ProgressMath {

    /** 续播下界：进度 >= 5% 才考虑续播。 */
    private const val RESUME_MIN_PERCENT = 5.0

    /** 续播上界：进度 <= 95% 才考虑续播。 */
    private const val RESUME_MAX_PERCENT = 95.0

    /**
     * 把进度钳制到 [0, durationMs] 区间。
     * durationMs <= 0 视为无有效时长，统一返回 0。
     */
    fun clamp(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return when {
            positionMs <= 0L -> 0L
            positionMs >= durationMs -> durationMs
            else -> positionMs
        }
    }

    /**
     * 进度百分比，范围 0f..1f。
     * durationMs <= 0 返回 0f；越界进度被钳制。
     */
    fun percent(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (clamp(positionMs, durationMs).toDouble() / durationMs.toDouble()).toFloat()
    }

    /**
     * 是否应该续播：仅当进度落在 5%..95%（含端点）之间时返回 true。
     * 起点（<=5%）从头开始，末尾（>=95%）视为已看完。
     */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (positionMs <= 0L || positionMs >= durationMs) return false
        val p = percent(positionMs, durationMs) * 100.0
        return p >= RESUME_MIN_PERCENT && p <= RESUME_MAX_PERCENT
    }

    /**
     * 计算续播起点：越界时归一到 0 或 durationMs。
     * 处于续播区间内返回钳制后的 positionMs，否则返回 0。
     */
    fun resumeFrom(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return if (shouldResume(positionMs, durationMs)) clamp(positionMs, durationMs) else 0L
    }

    /**
     * 剩余时长估算，结果永不小于 0。
     */
    fun estimateRemaining(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val remaining = durationMs - clamp(positionMs, durationMs)
        return if (remaining < 0L) 0L else remaining
    }
}
