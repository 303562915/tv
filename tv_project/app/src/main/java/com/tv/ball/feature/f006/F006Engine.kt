package com.tv.ball.feature.f006

import java.util.Locale

/**
 * 播放失败后的重试策略状态机。
 *
 * 只回答「这次失败还该不该重试、等多久」，不碰 Handler、不碰协程、不碰 Android，
 * 所以能脱离 Activity 直接单测：喂任意次 [onFailure] 都不会产生副作用。
 *
 * 退避序列固定 1s / 2s / 4s（指数增长，基数 [BASE_DELAY_MS]），最多 [MAX_ATTEMPTS] 次。
 * 真实的等待由调用方（Activity）自己调度并负责取消，这里只给建议值。
 */
class F006Engine {

    /** 一次失败事件的处理结论。 */
    sealed interface RetryDecision {
        /** 还能重试：等 [delayMs] 毫秒后发起第 [attempt] 次（从 1 开始）。 */
        data class Retry(val delayMs: Long, val attempt: Int) : RetryDecision

        /** 不再重试：次数用尽或被手动终止，调用方应提示切换线路。 */
        object GiveUp : RetryDecision
    }

    /** 已经安排过的重试次数（从 1 开始累加，0 表示还没重试过）。 */
    var attempts: Int = 0
        private set

    /** 用户手动终止；置位后 [onFailure] 一律返回 GIVE_UP，直到 [reset]。 */
    var aborted: Boolean = false
        private set

    /** 还可以重试几次；终止后不再消耗，但也不会再增长。 */
    val remainingAttempts: Int
        get() = (MAX_ATTEMPTS - attempts).coerceAtLeast(0)

    /** 次数已经用尽且没有被终止 —— 这才是「该建议切换线路」的状态。 */
    val isExhausted: Boolean
        get() = !aborted && remainingAttempts == 0

    /**
     * 播放失败时调用一次。
     *
     * 注意语义：调用一次就消耗一次重试额度，所以「重试之后又失败」必须再调一次，
     * 这样 1s / 2s / 4s 三档才会逐个走完。
     */
    fun onFailure(): RetryDecision {
        if (aborted) return RetryDecision.GiveUp
        if (attempts >= MAX_ATTEMPTS) return RetryDecision.GiveUp
        attempts += 1
        return RetryDecision.Retry(delayFor(attempts), attempts)
    }

    /** 手动终止：之后不再自动重试，已排队的延时由调用方负责取消。 */
    fun abort() {
        aborted = true
    }

    /** 清空计数与终止标记，重新开始一轮。 */
    fun reset() {
        attempts = 0
        aborted = false
    }

    companion object {
        /** 最多重试 3 次。 */
        const val MAX_ATTEMPTS = 3

        /** 第一次重试的等待时间，后续按位左移翻倍。 */
        const val BASE_DELAY_MS = 1000L

        /** 第 [attempt] 次重试应当等待的毫秒数：1 -> 1000、2 -> 2000、3 -> 4000。 */
        fun delayFor(attempt: Int): Long {
            val index = (attempt - 1).coerceIn(0, MAX_ATTEMPTS - 1)
            return BASE_DELAY_MS shl index
        }

        /** 毫秒转 `1.0s` 这类短文本，时间线文案与状态栏共用一份格式。 */
        fun formatDelay(ms: Long): String =
            String.format(Locale.US, "%.1fs", ms / 1000.0)
    }
}