package com.tv.ball.feature.f002

import java.util.Locale

/**
 * A-B 段落复读状态机。
 *
 * 只回答「现在这个时间点该不该跳回 A」，不持有播放器也不碰 Android，
 * 所以能脱离 Activity 直接单测：给 [shouldLoop] 喂任意时间点都不会产生副作用。
 *
 * 设点用返回值 + [rejectReason] 表达结果，而不是抛异常：
 * 遥控器场景里崩掉的异常用户看不见，而「为什么设不上」必须原样显示在页面上。
 */
class F002Engine {

    companion object {
        /** A、B 至少间隔这么久，否则跳回后几乎原地循环，看起来像卡住。 */
        const val MIN_GAP_MS = 200L

        /** 毫秒转 `mm:ss.SSS`（超过一小时才带小时段），UI 与日志共用一份格式。 */
        fun formatMillis(ms: Long): String {
            val safe = if (ms < 0) 0L else ms
            val totalSec = safe / 1000
            val hour = totalSec / 3600
            val minute = (totalSec % 3600) / 60
            val second = totalSec % 60
            val milli = safe % 1000
            return if (hour > 0) {
                String.format(Locale.US, "%d:%02d:%02d.%03d", hour, minute, second, milli)
            } else {
                String.format(Locale.US, "%02d:%02d.%03d", minute, second, milli)
            }
        }
    }

    /** A 点（毫秒），null 表示未设置。 */
    var aMs: Long? = null
        private set

    /** B 点（毫秒），null 表示未设置。 */
    var bMs: Long? = null
        private set

    /** 最近一次被拒的原因，可直接展示；设点成功或 [clear] 后清空。 */
    var rejectReason: String? = null
        private set

    /** 只有两个点都齐了才可能循环，单点只是「预备标记」。 */
    val isMarked: Boolean
        get() = aMs != null && bMs != null

    /**
     * 设置 A 点。已存在 B 点时要求 A < B（且间隔不小于 [MIN_GAP_MS]）。
     *
     * @return 成功为 true；失败为 false，原因写在 [rejectReason]。
     */
    fun setA(ms: Long): Boolean {
        if (ms < 0) {
            return reject("A 点不能为负数")
        }
        val b = bMs
        if (b != null) {
            if (ms >= b) {
                return reject("A 点必须早于 B 点（B 点是 ${formatMillis(b)}）")
            }
            if (b - ms < MIN_GAP_MS) {
                return reject("A 点与 B 点至少间隔 $MIN_GAP_MS 毫秒")
            }
        }
        aMs = ms
        rejectReason = null
        return true
    }

    /**
     * 设置 B 点。已存在 A 点时要求 B > A（且间隔不小于 [MIN_GAP_MS]）。
     *
     * @return 成功为 true；失败为 false，原因写在 [rejectReason]。
     */
    fun setB(ms: Long): Boolean {
        if (ms < 0) {
            return reject("B 点不能为负数")
        }
        val a = aMs
        if (a != null) {
            if (ms <= a) {
                return reject("B 点必须晚于 A 点（A 点是 ${formatMillis(a)}）")
            }
            if (ms - a < MIN_GAP_MS) {
                return reject("A 点与 B 点至少间隔 $MIN_GAP_MS 毫秒")
            }
        }
        bMs = ms
        rejectReason = null
        return true
    }

    /** 当前播放位置是否已经越过 B 点，即该跳回 A 了。 */
    fun shouldLoop(nowMs: Long): Boolean {
        val a = aMs ?: return false
        val b = bMs ?: return false
        if (b <= a) return false
        return nowMs >= b
    }

    /** 该跳回的目标位置；标记不完整时返回 null，调用方据此不做任何跳转。 */
    fun loopTo(): Long? = if (isMarked) aMs else null

    /** 清除标记，播放随即回到「到 B 也不跳」的普通状态。 */
    fun clear() {
        aMs = null
        bMs = null
        rejectReason = null
    }

    /**
     * 从持久化数据恢复标记。历史数据可能已被手改成非法组合，
     * 这里只恢复能站得住的部分，非法则整体丢弃并给出原因。
     */
    fun restore(aMs: Long?, bMs: Long?): Boolean {
        clear()
        if (aMs == null && bMs == null) return true
        if (aMs != null && bMs != null) {
            if (aMs < 0 || bMs < 0 || bMs - aMs < MIN_GAP_MS) {
                return reject("历史标记不合法，已丢弃")
            }
            this.aMs = aMs
            this.bMs = bMs
            rejectReason = null
            return true
        }
        val single = aMs ?: bMs!!
        if (single < 0) {
            return reject("历史标记不合法，已丢弃")
        }
        if (aMs != null) this.aMs = aMs else this.bMs = bMs
        rejectReason = null
        return true
    }

    private fun reject(reason: String): Boolean {
        rejectReason = reason
        return false
    }
}