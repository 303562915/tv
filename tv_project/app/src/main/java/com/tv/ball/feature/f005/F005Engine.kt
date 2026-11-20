package com.tv.ball.feature.f005

/**
 * 下集倒计时状态机。
 *
 * 只回答「下一集还差多久开播」这一件事：不持有 Handler、不碰 Android，
 * 时间推进完全由外部通过 [tick] 喂进来，所以能脱离 Activity 直接单测。
 *
 * 状态迁移：
 * ```
 * IDLE --start--> COUNTING --归零--> DONE
 *                    |--cancel()--> CANCELED（停在片尾，本轮不再提示）
 * CANCELED / DONE --start--> COUNTING（下一轮）
 * ```
 *
 * 取消不回到 IDLE，是为了让调用方能从 [state] 直接读出「本集停在片尾」，
 * 从而不做自动跳集；想重新开始只能显式 [start]（对应 UI 上的「继续播放下一集」）。
 */
class F005Engine {

    enum class State {
        /** 还没到片尾。 */
        IDLE,
        /** 浮层已出现，正在倒计时。 */
        COUNTING,
        /** 用户取消，本轮不再提示，停在片尾。 */
        CANCELED,
        /** 倒计时走完。 */
        DONE
    }

    companion object {
        /** 验收要求默认 5 秒。 */
        const val DEFAULT_SECONDS = 5
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 15

        /** 每次 tick 推进 100ms：秒数变化能跟上，主线程也不会被打得太密。 */
        const val TICK_STEP_MS = 100L

        fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

        fun secondsToMs(seconds: Int): Long = clampSeconds(seconds).toLong() * 1000L
    }

    var state: State = State.IDLE
        private set

    /** 本轮总时长。 */
    var totalMs: Long = secondsToMs(DEFAULT_SECONDS)
        private set

    /** 剩余时长；CANCELED 时保持在取消的那一刻，方便展示「停在片尾还剩几秒」。 */
    var remainingMs: Long = secondsToMs(DEFAULT_SECONDS)
        private set

    /**
     * 只在「跨入 DONE 的那一次 tick」为 true。
     * 之后的 tick 一律清成 false，所以上层不会把同一轮接播触发两次。
     */
    var justReachedDone: Boolean = false
        private set

    val elapsedMs: Long
        get() = (totalMs - remainingMs).coerceAtLeast(0L)

    /** 剩余整数秒，向上取整：4.2 秒要显示 5 而不是 4。 */
    val remainingSeconds: Int
        get() = ((remainingMs + 999L) / 1000L).toInt()

    /** 剩余比例，1f 表示满格，用于 ProgressBar。 */
    val remainingFraction: Float
        get() = if (totalMs <= 0L) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)

    /**
     * 开始一轮倒计时。任何状态都可以调用（换集、下一轮重来都走这里）。
     *
     * 传 0 或负数表示「立刻到点」：直接进入 DONE 并置一次边沿标记，避免上层要特判。
     */
    fun start(totalMs: Long) {
        justReachedDone = false
        this.totalMs = totalMs.coerceAtLeast(0L)
        this.remainingMs = this.totalMs
        state = if (this.totalMs == 0L) {
            justReachedDone = true
            State.DONE
        } else {
            State.COUNTING
        }
    }

    /**
     * 推进一步。
     *
     * @param deltaMs 自上次 tick 走过的时间，非正数视为无效调用（不推进也不改状态）。
     * @return 推进之后的状态，顺带把 [justReachedDone] 更新好。
     */
    fun tick(deltaMs: Long): State {
        // 每次 tick 先清边沿标记：DONE 之后再怎么 tick 都不会重复触发接播
        justReachedDone = false
        if (state != State.COUNTING || deltaMs <= 0L) return state

        remainingMs = (remainingMs - deltaMs).coerceAtLeast(0L)
        if (remainingMs == 0L) {
            state = State.DONE
            justReachedDone = true
        }
        return state
    }

    /** 取消本轮。只有 COUNTING 有意义；剩余时长原样冻结，状态落到 CANCELED。 */
    fun cancel() {
        justReachedDone = false
        if (state == State.COUNTING) {
            state = State.CANCELED
        }
    }

    /** 回到空闲，手动接播 / 换集后复用同一个实例；总时长等下一次 [start] 覆盖。 */
    fun reset() {
        justReachedDone = false
        state = State.IDLE
        remainingMs = totalMs
    }
}