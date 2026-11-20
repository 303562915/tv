package com.tv.ball.feature.f001

import android.content.Context
import android.view.KeyEvent

/**
 * 播放器锁定的唯一入口。
 *
 * 播放页接线方式（主智能体负责接线，这里把契约写清楚）：
 * 1. `dispatchKeyEvent(event)` 里先问 [shouldIntercept]：返回 true 就 `return true` 把键吞掉；
 * 2. 返回键 / 确认键要放行进 [handleKey]，由状态机判断是不是「长按返回」或「按确认键」并解锁；
 * 3. 手势层（双击、滑动调进度、长按倍速）调 [shouldBlockGestures] 决定是否整段忽略；
 * 4. 切集、旋转、进程重建都只读 SharedPreferences，不需要额外保存 locked。
 *
 * 锁定状态持久化在 SharedPreferences，状态机本身是纯 Kotlin，可脱离 Android 单测。
 */
class F001Engine private constructor(private val policy: F001LockPolicy) {

    /** 当前是否锁定。 */
    val isLocked: Boolean
        get() = policy.isLocked()

    /** 累计锁定次数（本地持久化，用来给界面一个真实计数）。 */
    val lockCount: Int
        get() = policy.lockCount()

    /** 锁定：返回 true 表示本次真的切到了锁定态。 */
    fun lock(): Boolean = policy.lock()

    /** 解锁：返回 true 表示本次真的解开了。 */
    fun unlock(): Boolean = policy.unlock()

    /** 直接设置状态，返回 true 表示状态发生了变化。 */
    fun setLocked(locked: Boolean): Boolean = policy.setLocked(locked)

    /** 取反，返回切换后的状态。 */
    fun toggle(): Boolean = policy.toggle()

    /**
     * 这个按键要不要吞掉。
     *
     * 锁定中：白名单外的键全部吞（返回 true）；白名单内的返回键、确认键返回 false，
     * 让它们还能进到 [handleKey] 去完成长按解锁 —— 否则解锁键会被一起吞掉，人就锁死在播放页里了。
     */
    fun shouldIntercept(keyCode: Int): Boolean = policy.shouldIntercept(keyCode)

    /** 锁定中是否要屏蔽手势（双击、滑动、长按倍速、边缘手势）。 */
    fun shouldBlockGestures(): Boolean = policy.shouldBlockGestures()

    /** 该键是否参与解锁判定（确认键 / 返回键），播放页据此决定要不要把事件转给 [handleKey]。 */
    fun isUnlockTrigger(keyCode: Int): Boolean = policy.isUnlockTrigger(keyCode)

    /**
     * 带长按判定的按键入口。
     *
     * @param action 直接用 `KeyEvent.getAction()`（DOWN = 0，UP = 1）
     * @param eventTime 直接用 `KeyEvent.getEventTime()`（uptimeMillis）
     * @return [F001KeyDecision.INTERCEPT] / [F001KeyDecision.UNLOCK] 时调用方要消费掉事件
     */
    fun handleKey(
        keyCode: Int,
        action: Int,
        eventTime: Long,
        repeatCount: Int = 0
    ): F001KeyDecision = policy.onKeyEvent(keyCode, action, eventTime, repeatCount)

    /**
     * KeyEvent 重载：播放页在 onKeyDown / onKeyUp 里直接把事件丢进来即可，
     * 不需要自己去拆 keyCode / action / eventTime。返回不是 [F001KeyDecision.PASS] 就消费掉事件。
     */
    fun handleKey(event: KeyEvent): F001KeyDecision =
        handleKey(event.keyCode, event.action, event.eventTime, event.repeatCount)

    companion object {
        /** 进程级单例：播放页各层拿到的是同一份锁定状态。 */
        @Volatile
        private var instance: F001Engine? = null

        fun get(context: Context): F001Engine =
            instance ?: synchronized(this) {
                instance ?: F001Engine(
                    F001LockPolicy(F001PrefsStore(context.applicationContext))
                ).also { instance = it }
            }

        /** 单测入口：自己塞 store 与时钟，不依赖 Android 运行时。 */
        fun create(
            store: F001LockStore,
            clock: () -> Long = { System.currentTimeMillis() }
        ): F001Engine = F001Engine(F001LockPolicy(store, clock))
    }
}

/**
 * 锁定状态的存储抽象。
 *
 * 状态机 [F001LockPolicy] 只依赖这层，不碰 Android 类型，判定逻辑可在 JVM 单测里直接跑；
 * 线上实现见 [F001PrefsStore]。
 */
interface F001LockStore {
    fun readLocked(): Boolean
    fun writeLocked(locked: Boolean)
    fun readLockCount(): Int
    fun writeLockCount(count: Int)
}

/**
 * SharedPreferences 实现。
 *
 * 用 applicationContext 取实例，避免持有 Activity（旋转重建后旧实例会被泄漏）。
 * 首读是一次很小的 xml 解析，之后 SharedPreferences 自带内存缓存；写用 apply() 异步落盘。
 */
class F001PrefsStore(context: Context) : F001LockStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readLocked(): Boolean = prefs.getBoolean(KEY_LOCKED, false)

    override fun writeLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    override fun readLockCount(): Int = prefs.getInt(KEY_LOCK_COUNT, 0)

    override fun writeLockCount(count: Int) {
        prefs.edit().putInt(KEY_LOCK_COUNT, count).apply()
    }

    private companion object {
        const val PREFS_NAME = "f001_player_lock"
        const val KEY_LOCKED = "locked"
        const val KEY_LOCK_COUNT = "lock_count"
    }
}

/** 一次按键判定的结果。 */
enum class F001KeyDecision {
    /** 未锁定，事件原样交给播放器。 */
    PASS,

    /** 锁定中，按键被吞掉。 */
    INTERCEPT,

    /** 命中解锁规则：状态已切回未锁定，本此按键仍然吞掉（不放行给上层，免得同一下顺带触发别的动作）。 */
    UNLOCK
}

/**
 * 锁定状态机：纯 Kotlin，无 Android 依赖。
 *
 * 白名单只有两类：确认键（按一下即解锁）、返回键（按住到阈值才解锁，短按照样拦）。
 */
class F001LockPolicy(
    private val store: F001LockStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** 返回键按下的时间基准；[NO_HOLD] 表示当前没有按住。 */
    private var backDownAt: Long = NO_HOLD

    fun isLocked(): Boolean = store.readLocked()

    fun lockCount(): Int = store.readLockCount()

    fun lock(): Boolean {
        if (store.readLocked()) return false
        store.writeLocked(true)
        store.writeLockCount(store.readLockCount() + 1)
        backDownAt = NO_HOLD
        return true
    }

    fun unlock(): Boolean {
        if (!store.readLocked()) return false
        store.writeLocked(false)
        backDownAt = NO_HOLD
        return true
    }

    fun setLocked(locked: Boolean): Boolean = if (locked) lock() else unlock()

    /** 返回切换后的状态。 */
    fun toggle(): Boolean {
        setLocked(!store.readLocked())
        return store.readLocked()
    }

    /** 是否在按键白名单里（确认键 / 返回键）。 */
    fun isWhitelisted(keyCode: Int): Boolean = F001Keys.WHITELIST.contains(keyCode)

    fun isUnlockTrigger(keyCode: Int): Boolean = isWhitelisted(keyCode)

    /** 锁定中且不在白名单 → 吞掉。 */
    fun shouldIntercept(keyCode: Int): Boolean = store.readLocked() && !isWhitelisted(keyCode)

    /** 锁定中屏蔽全部手势。 */
    fun shouldBlockGestures(): Boolean = store.readLocked()

    /**
     * 按键判定。
     *
     * 返回键的长按判定同时看 eventTime 差值（正常遥控器会给重复事件的时间戳）和 repeatCount
     * （少数遥控器的时间戳不可靠，只能靠重复次数折算），两条都认，避免出现「按住也解不开」。
     */
    fun onKeyEvent(keyCode: Int, action: Int, eventTime: Long, repeatCount: Int = 0): F001KeyDecision {
        if (!store.readLocked()) {
            backDownAt = NO_HOLD
            return F001KeyDecision.PASS
        }

        // 确认键：按下即解锁
        if (F001Keys.isConfirm(keyCode)) {
            if (action == F001Keys.ACTION_DOWN) {
                unlock()
                return F001KeyDecision.UNLOCK
            }
            return F001KeyDecision.INTERCEPT
        }

        // 返回键：短按拦掉（防误退出），按住到阈值才解锁
        if (keyCode == F001Keys.BACK) {
            if (action != F001Keys.ACTION_DOWN) {
                backDownAt = NO_HOLD
                return F001KeyDecision.INTERCEPT
            }
            val now = if (eventTime > 0L) eventTime else clock()
            // 时间基准不同源（eventTime 是 uptime，兜底是墙钟）时重新起算，避免算出负的保持时长
            if (backDownAt == NO_HOLD || now < backDownAt) backDownAt = now
            val held = now - backDownAt
            if (held >= UNLOCK_HOLD_MS || repeatCount >= REPEAT_UNLOCK_COUNT) {
                unlock()
                return F001KeyDecision.UNLOCK
            }
            return F001KeyDecision.INTERCEPT
        }

        return F001KeyDecision.INTERCEPT
    }

    private companion object {
        /** 长按返回键解锁的阈值。 */
        const val UNLOCK_HOLD_MS = 1300L

        /** 时间戳不可靠时，用重复次数兜底（约 200ms 一次重复，折算 1300/200）。 */
        const val REPEAT_UNLOCK_COUNT = 6

        const val NO_HOLD = -1L
    }
}

/**
 * 按键码常量与可读名。
 *
 * 数值与 `android.view.KeyEvent.KEYCODE_*` 一一对应。这里不直接引用 KeyEvent 常量，
 * 是为了让锁定判定脱离 Android 运行时也能单测。
 */
object F001Keys {

    const val BACK = 4
    const val HOME = 3
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val ENTER = 66
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_FORWARD = 90
    const val MEDIA_REWIND = 89
    const val NUM_ENTER = 160

    /** 与 KeyEvent.getAction() 对齐。 */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    /** 确认键：按一下就解锁。 */
    val CONFIRM_KEYS: Set<Int> = setOf(DPAD_CENTER, ENTER, NUM_ENTER)

    /** 按键白名单：锁定期间允许进到判定逻辑的只有确认键和返回键，其余全部拦截。 */
    val WHITELIST: Set<Int> = CONFIRM_KEYS + BACK

    fun isConfirm(keyCode: Int): Boolean = CONFIRM_KEYS.contains(keyCode)

    /** 按键的可读名，拦截提示与日志都用它，避免界面里散落一堆数字。 */
    fun nameOf(keyCode: Int): String = when (keyCode) {
        BACK -> "返回键"
        HOME -> "主页键"
        DPAD_UP -> "上键"
        DPAD_DOWN -> "下键"
        DPAD_LEFT -> "左键"
        DPAD_RIGHT -> "右键"
        DPAD_CENTER -> "确认键"
        VOLUME_UP -> "音量+键"
        VOLUME_DOWN -> "音量-键"
        ENTER -> "确认键"
        NUM_ENTER -> "确认键"
        MENU -> "菜单键"
        MEDIA_PLAY_PAUSE -> "播放/暂停键"
        MEDIA_NEXT -> "下一集键"
        MEDIA_PREVIOUS -> "上一集键"
        MEDIA_FORWARD -> "快进键"
        MEDIA_REWIND -> "快退键"
        else -> "按键 $keyCode"
    }
}
