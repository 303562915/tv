package com.tv.ball.feature.f009

import android.content.Context

/**
 * 快进快退步长的四个档位。
 *
 * 持久化只认 [seconds]，不存枚举序数 —— 以后增删档位不会把老用户的设置读串。
 */
enum class StepPolicy(val seconds: Int) {
    FIVE(5),
    TEN(10),
    THIRTY(30),
    SIXTY(60);

    /** 折算成毫秒，播放器位置本身就是毫秒。 */
    val stepMs: Long
        get() = seconds * 1000L

    companion object {
        /** 默认 10 秒：绝大多数片源按一次刚好跳过片头字幕。 */
        val DEFAULT: StepPolicy = StepPolicy.TEN

        /** 读档位时的兜底：存的秒数不认识就回默认档，不抛异常。 */
        fun ofSeconds(seconds: Int): StepPolicy =
            StepPolicy.values().firstOrNull { it.seconds == seconds } ?: DEFAULT

        fun all(): List<StepPolicy> = StepPolicy.values().toList()
    }
}

/** 一次跳转的成因，界面据此给不同的提示文案。 */
enum class F009JumpKind {
    /** 正常加减一个步长。 */
    PLAIN,

    /** 智能模式：一次跨过片头（正/反向都算）。 */
    SKIP_INTRO,

    /** 智能模式：一次跨过片尾（正/反向都算）。 */
    SKIP_CREDITS,

    /** 剩余不足一个步长，停在结尾。 */
    CLAMPED_END,

    /** 已经/只能停在开头。 */
    CLAMPED_START,

    /** 本来就在结尾，按前进没有位移。 */
    EDGE_END,

    /** 本来就在开头，按后退没有位移。 */
    EDGE_START
}

/** 跳转结果：落点 + 成因。 */
data class F009Jump(val positionMs: Long, val kind: F009JumpKind)

/**
 * 步长状态的存储抽象。
 *
 * 判定逻辑 [F009Engine] 只依赖这一层，实现换成 SharedPreferences 之外的方案不用改调用方。
 */
interface F009Store {
    fun readStepSeconds(): Int
    fun writeStepSeconds(seconds: Int)

    fun readSmart(): Boolean
    fun writeSmart(smart: Boolean)

    fun readIntroEndMs(): Long
    fun writeIntroEndMs(value: Long)

    fun readCreditsStartMs(): Long
    fun writeCreditsStartMs(value: Long)

    fun readSimulatedPositionMs(): Long
    fun writeSimulatedPositionMs(value: Long)
}

/**
 * SharedPreferences 实现。
 *
 * 用 applicationContext 取实例，不持有 Activity；写用 apply() 异步落盘，主线程不做磁盘 IO。
 */
class F009PrefsStore(context: Context) : F009Store {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readStepSeconds(): Int =
        prefs.getInt(KEY_STEP_SECONDS, StepPolicy.DEFAULT.seconds)

    override fun writeStepSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_STEP_SECONDS, seconds).apply()
    }

    override fun readSmart(): Boolean = prefs.getBoolean(KEY_SMART, false)

    override fun writeSmart(smart: Boolean) {
        prefs.edit().putBoolean(KEY_SMART, smart).apply()
    }

    override fun readIntroEndMs(): Long = prefs.getLong(KEY_INTRO_END_MS, F009Engine.NO_MARK)

    override fun writeIntroEndMs(value: Long) {
        prefs.edit().putLong(KEY_INTRO_END_MS, value).apply()
    }

    override fun readCreditsStartMs(): Long =
        prefs.getLong(KEY_CREDITS_START_MS, F009Engine.NO_MARK)

    override fun writeCreditsStartMs(value: Long) {
        prefs.edit().putLong(KEY_CREDITS_START_MS, value).apply()
    }

    override fun readSimulatedPositionMs(): Long = prefs.getLong(KEY_SIM_POSITION_MS, 0L)

    override fun writeSimulatedPositionMs(value: Long) {
        prefs.edit().putLong(KEY_SIM_POSITION_MS, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "f009_step_policy"
        const val KEY_STEP_SECONDS = "step_seconds"
        const val KEY_SMART = "smart_skip"
        const val KEY_INTRO_END_MS = "intro_end_ms"
        const val KEY_CREDITS_START_MS = "credits_start_ms"
        const val KEY_SIM_POSITION_MS = "sim_position_ms"
    }
}

/**
 * 快进快退步长策略。
 *
 * 纯计算，不碰播放器：播放页拿到 [forward] / [backward] 的返回值直接 seekTo 即可。
 * 四个入口参数（位置、时长、片头结束、片尾开始）由调用方给，本类自身只持久化「用户设置」，
 * 所以同一个实例既能给播放页用，也能给设置页的模拟区用。
 *
 * 智能模式的判定只做两件事：
 * 1. 片头内按前进 → 一步落到片头结束，按住连按也不会被片头吞掉步长；
 * 2. 差一步就要踏进片尾 / 已经在片尾 → 一步跨过整个片尾（正向到结尾，反向退到片尾前）。
 */
class F009Engine(private val store: F009Store) {

    /** 当前档位，写回即持久化。 */
    var policy: StepPolicy
        get() = StepPolicy.ofSeconds(store.readStepSeconds())
        set(value) {
            store.writeStepSeconds(value.seconds)
        }

    /** 智能跳过片头片尾开关。 */
    var smartEnabled: Boolean
        get() = store.readSmart()
        set(value) {
            store.writeSmart(value)
        }

    /** 片头结束位置；[NO_MARK] 表示未标记。 */
    var introEndMs: Long
        get() = store.readIntroEndMs()
        set(value) {
            store.writeIntroEndMs(value)
        }

    /** 片尾开始位置；[NO_MARK] 表示未标记。 */
    var creditsStartMs: Long
        get() = store.readCreditsStartMs()
        set(value) {
            store.writeCreditsStartMs(value)
        }

    /**
     * 设置页模拟用的播放位置。
     *
     * 单独存一份是为了「重进页面沿用上次位置」，和真实播放器进度互不影响。
     */
    var simulatedPositionMs: Long
        get() = store.readSimulatedPositionMs()
        set(value) {
            store.writeSimulatedPositionMs(value)
        }

    /** 当前步长毫秒数，界面文案与判定用的是同一个值。 */
    val stepMs: Long
        get() = policy.stepMs

    fun hasIntroMark(): Boolean = introEndMs != NO_MARK

    fun hasCreditsMark(): Boolean = creditsStartMs != NO_MARK

    /**
     * 前进。
     *
     * @param posMs 当前位置
     * @param durationMs 总时长，<= 0 视为未知
     * @param introEndMs 片头结束位置，[NO_MARK] 表示未标记
     * @param creditsStartMs 片尾开始位置，[NO_MARK] 表示未标记
     * @param smart 是否启用智能跳过
     * @return 目标位置（已 clamp 在 0..duration 内）
     */
    fun forward(
        posMs: Long,
        durationMs: Long,
        introEndMs: Long,
        creditsStartMs: Long,
        smart: Boolean
    ): Long = forwardDecision(posMs, durationMs, introEndMs, creditsStartMs, smart).positionMs

    /** 后退，参数含义同 [forward]。 */
    fun backward(
        posMs: Long,
        durationMs: Long,
        introEndMs: Long,
        creditsStartMs: Long,
        smart: Boolean
    ): Long = backwardDecision(posMs, durationMs, introEndMs, creditsStartMs, smart).positionMs

    /** 把任意位置夹到 0..duration；时长未知（<= 0）时只保证不为负。 */
    fun clamp(posMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return if (posMs < 0L) 0L else posMs
        return posMs.coerceIn(0L, durationMs)
    }

    /** 带成因的前进，界面的边界提示用它。 */
    fun forwardDecision(
        posMs: Long,
        durationMs: Long,
        introEndMs: Long,
        creditsStartMs: Long,
        smart: Boolean
    ): F009Jump {
        val duration = if (durationMs > 0L) durationMs else 0L
        val pos = clamp(posMs, duration)
        if (duration == 0L) return F009Jump(0L, F009JumpKind.EDGE_START)
        if (pos >= duration) return F009Jump(duration, F009JumpKind.EDGE_END)

        val step = stepMs
        val intro = if (smart) introMark(introEndMs, duration) else NO_MARK
        val credits = if (smart) creditsMark(creditsStartMs, duration) else NO_MARK

        // 片头里按前进：不管步长多大，一步落到片头结束，避免停在片头中段
        if (intro != NO_MARK && pos < intro) return F009Jump(intro, F009JumpKind.SKIP_INTRO)

        val target = pos + step
        // 剩余不足一个步长：停在结尾，不然会算出超过时长的位置
        if (target > duration) return F009Jump(duration, F009JumpKind.CLAMPED_END)
        // 这一步会踏进片尾：直接跨过整个片尾到结尾
        if (credits != NO_MARK && pos < credits && target >= credits) {
            return F009Jump(duration, F009JumpKind.SKIP_CREDITS)
        }
        return F009Jump(target, F009JumpKind.PLAIN)
    }

    /** 带成因的后退。 */
    fun backwardDecision(
        posMs: Long,
        durationMs: Long,
        introEndMs: Long,
        creditsStartMs: Long,
        smart: Boolean
    ): F009Jump {
        val duration = if (durationMs > 0L) durationMs else 0L
        val pos = clamp(posMs, duration)
        if (duration == 0L) return F009Jump(0L, F009JumpKind.EDGE_START)
        if (pos <= 0L) return F009Jump(0L, F009JumpKind.EDGE_START)

        val step = stepMs
        val intro = if (smart) introMark(introEndMs, duration) else NO_MARK
        val credits = if (smart) creditsMark(creditsStartMs, duration) else NO_MARK

        val target = pos - step
        if (target < 0L) return F009Jump(0L, F009JumpKind.CLAMPED_START)

        // 在片尾里（或这一步会退回片尾）：一步跨过整个片尾，落到片尾前一个步长
        if (credits != NO_MARK && pos >= credits && target < credits) {
            return F009Jump(clamp(credits - step, duration), F009JumpKind.SKIP_CREDITS)
        }
        // 片头之后一步之内退回：一步跨过片头，落到片头之前
        if (intro != NO_MARK && pos > intro && target < intro) {
            return F009Jump(clamp(intro - step, duration), F009JumpKind.SKIP_INTRO)
        }
        return F009Jump(target, F009JumpKind.PLAIN)
    }

    /** 用持久化设置直接前进 / 后退，播放页接线时只需传位置和时长。 */
    fun forwardFromSettings(posMs: Long, durationMs: Long): F009Jump =
        forwardDecision(posMs, durationMs, introEndMs, creditsStartMs, smartEnabled)

    fun backwardFromSettings(posMs: Long, durationMs: Long): F009Jump =
        backwardDecision(posMs, durationMs, introEndMs, creditsStartMs, smartEnabled)

    /** 标记只在 0..duration 开区间内才有效，否则当没标。 */
    private fun introMark(mark: Long, durationMs: Long): Long =
        if (mark > 0L && mark < durationMs) mark else NO_MARK

    /** 片尾起点必须留出比片头更靠后的位置，否则忽略。 */
    private fun creditsMark(mark: Long, durationMs: Long): Long =
        if (mark > 0L && mark < durationMs) mark else NO_MARK

    companion object {
        /** 未标记的哨兵值；用 -1 而不是 0，因为 0 是个合法的「片头结束在 0」的边界。 */
        const val NO_MARK = -1L

        /** 进程级单例：设置页和播放页拿到的是同一份设置。 */
        @Volatile
        private var instance: F009Engine? = null

        fun get(context: Context): F009Engine =
            instance ?: synchronized(this) {
                instance ?: F009Engine(F009PrefsStore(context.applicationContext)).also {
                    instance = it
                }
            }
    }
}