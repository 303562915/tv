package com.tv.ball.feature.f083

import android.content.Context
import android.provider.Settings
import com.tv.ball.R

/**
 * 动效强度档位。
 *
 * 只有两档：能不能忍动画。低性能盒子（Cortex-A53 那一档）上，
 * 一次页面转场默认 300ms 的交叉淡入 + 缩放就足以掉十几帧，
 * 而本应用的操作路径是「首页 → 详情 → 播放」高频来回，卡顿会被放大成"整个应用都卡"。
 *
 * 所以档位不设五级滑杆：要么按原样播，要么一律瞬时切换。
 */
enum class MotionMode(val storageValue: Int) {
    /** 标准动效：页面转场按系统默认播放，控件缩放/淡入 300ms。 */
    NORMAL(0),

    /** 减弱动效：转场与缩放全部瞬时完成，时长一律 0。 */
    REDUCED(1);

    companion object {
        /**
         * 从存储值还原档位。
         *
         * 认不出来的值（旧版本写进去的、手工改过的、越界的）一律当 [NORMAL]：
         * 宁可多播一次动画，也不该因为一个坏值把动效永久关掉却没人知道怎么恢复。
         */
        fun fromStorage(value: Int): MotionMode =
            values().firstOrNull { it.storageValue == value } ?: NORMAL
    }
}

/**
 * F083「减弱动效」的能力层。
 *
 * 这一层只回答一个问题：**这次动效该播多久**。页面层把时长交给
 * `ValueAnimator.setDuration` 或转场动画参数，因此"减弱"不是一个到处判断的全局布尔值，
 * 而是一组纯函数的返回值——任何想播动画的地方都从 [durationMs] 取时长，
 * 就不会出现"设置里关了、某个页面还在播"。
 *
 * 纯计算部分（[durationMs] / [scale] / [shouldAnimate]）不碰 Android 类型，可脱离设备单测；
 * [read] / [write] 负责持久化，[systemPrefersReduced] 读系统级动画缩放开关做默认值。
 */
object F083Engine {

    /** 标准档的转场时长（毫秒）：与系统 Activity 默认转场一致，改成别的值会和系统页面对不上。 */
    const val BASE_DURATION_MS: Long = 300L

    /** 标准档下的起始缩放：从 0.92 放到 1.0，够看出"进场"又不至于让内容糊一下。 */
    const val NORMAL_SCALE: Float = 0.92f

    /** 减弱档下的缩放：恒为 1.0，也就是没有任何缩放。 */
    const val REDUCED_SCALE: Float = 1.0f

    private const val PREFS_NAME = "f083_motion"
    private const val KEY_MODE = "mode"

    // ===== 纯计算 =====

    /**
     * 某个动效在指定档位下应该播多久。
     *
     * [base] 是这个动效自己的标准时长（转场 300、列表项 200、按钮反馈 120 都由调用方给），
     * 减弱档直接归零，**不按比例缩短**：150ms 的动画在低端盒子上同样掉帧，
     * 而且缩一半会让用户以为"没播完就跳了"。归零则明确是"瞬时切换"。
     *
     * [base] 传负数时按 0 处理，避免把负数交给 ValueAnimator（那会抛 IllegalArgumentException）。
     */
    fun durationMs(mode: MotionMode, base: Long = BASE_DURATION_MS): Long =
        if (mode == MotionMode.REDUCED) 0L else base.coerceAtLeast(0L)

    /** 动效的起始缩放：标准档 0.92，减弱档 1.0（等于不缩）。 */
    fun scale(mode: MotionMode): Float =
        if (mode == MotionMode.REDUCED) REDUCED_SCALE else NORMAL_SCALE

    /** 这次到底要不要真的播动画（减弱档或时长为 0 都算"不播"）。 */
    fun shouldAnimate(mode: MotionMode): Boolean =
        mode == MotionMode.NORMAL && durationMs(mode) > 0L

    /** 档位给人看的名字；页面与状态行共用同一份资源，避免两处文案各写一套。 */
    fun labelRes(mode: MotionMode): Int =
        if (mode == MotionMode.REDUCED) R.string.f083_mode_reduced else R.string.f083_mode_normal

    // ===== 持久化 =====

    /** 读当前档位；从没选过时返回 null，页面据此区分"没人为选过"与"选的是标准档"。 */
    fun read(context: Context): MotionMode? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_MODE)) return null
        return MotionMode.fromStorage(prefs.getInt(KEY_MODE, MotionMode.NORMAL.storageValue))
    }

    /** 写档位；用 apply() 异步落盘，切换动作不能因为一次磁盘 IO 卡住遥控器的确认键。 */
    fun write(context: Context, mode: MotionMode) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, mode.storageValue)
            .apply()
    }

    /**
     * 实际生效的档位：本功能自己存过就以本功能为准，没存过则跟随系统的动画缩放设置。
     *
     * 语义与系统一致：系统里把动画缩放调成 0（不少"省电 / 开发者选项"工具会这么干），
     * 本应用也按减弱处理，省得用户以为这个开关没生效。
     * 读不到这个设置（部分定制 ROM 会挡）时退回标准档——只是读失败，不该影响页面打开。
     */
    fun effective(context: Context): MotionMode {
        read(context)?.let { return it }
        return if (systemPrefersReduced(context)) MotionMode.REDUCED else MotionMode.NORMAL
    }

    /** 系统是否已经把动画缩放关到 0；取不到时按「没关」处理。 */
    fun systemPrefersReduced(context: Context): Boolean {
        val resolver = context.applicationContext?.contentResolver ?: return false
        val scale = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale <= 0f
    }

    /**
     * 当前档位下「播放转场动画」演示实际占用多久。
     *
     * 页面把这个数字显示在按钮旁边：减弱档是 0 ms（瞬时），标准档是真实时长。
     * 它必须和演示里真正交给 ValueAnimator 的时长同源，否则界面上的数字就是假的。
     */
    fun demoDurationMs(mode: MotionMode): Long = durationMs(mode, BASE_DURATION_MS)

    /** 一句人话：减弱档说它影响了什么，标准档说现在是什么状态。 */
    fun summary(context: Context, mode: MotionMode): String =
        if (mode == MotionMode.REDUCED) {
            context.getString(R.string.f083_summary_reduced)
        } else {
            context.getString(R.string.f083_summary_normal)
        }
}