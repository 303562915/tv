package com.tv.ball.feature.f010

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一组手势灵敏度参数：竖直滑动触发阈值（dp）+ 数值增益（倍数）。
 */
data class F010Settings(val thresholdDp: Int, val gain: Float)

/**
 * 参数持久化端口。
 *
 * 故意抽成纯 Kotlin 接口：引擎本身不 import 任何 android.*，可以脱离设备单测；
 * Android 侧实现见 `F010Activity.kt` 里的 `F010PrefsStore`（写 `video_player_prefs`，
 * 和播放页读的是同一份 SharedPreferences）。
 */
interface F010SettingsStore {
    /** 读已保存的参数；从未保存过或读失败时返回 null，由引擎回落到默认值。 */
    fun load(): F010Settings?
    fun save(settings: F010Settings)
}

/**
 * 手势灵敏度引擎。
 *
 * 语义对齐 `presentation/player/GestureHandler.kt`（那边写死了 `abs(deltaY) > 50` 像素才起调，
 * 并把 `-deltaY / view.height` 直接换算成亮度/音量），播放页接线时只需要三处改动：
 * 1. 触发判定：`if (engine.applyThreshold(dyDp))` 取代写死的 50px 比较；
 * 2. 亮度：`engine.deltaToBrightness(dyDp, screenHeightDp)` 取代 `-deltaY / view.height`；
 * 3. 音量：`engine.deltaToVolume(dyDp)` 取代 `-deltaY / view.height`，再乘 `maxVolume`。
 *
 * 其中 dyDp 是手指相对按下点的竖直位移，按“上滑为正”统一（像素转 dp 用
 * `px / resources.displayMetrics.density`），这样和 GestureHandler 里 `-deltaY` 的方向一致。
 *
 * 阈值只管“什么时候开始响应”，不参与数值换算；增益只管“滑同样距离变多少”，不改触发门槛 ——
 * 两者解耦，所以调增益不会让手势变得更容易误触。
 */
class F010Engine(private val store: F010SettingsStore) {

    /** 当前触发阈值（dp），越大越钝。 */
    var thresholdDp: Int = DEFAULT_THRESHOLD_DP
        private set

    /** 当前增益倍数，1.0 为原速。 */
    var gain: Float = DEFAULT_GAIN
        private set

    init {
        // 存盘值可能来自旧版本或被手动改脏，读出来先夹回合法区间
        val saved = store.load()
        if (saved != null) {
            thresholdDp = saved.thresholdDp.coerceIn(MIN_THRESHOLD_DP, MAX_THRESHOLD_DP)
            gain = quantizeGain(saved.gain)
        }
    }

    /** 设置阈值，返回是否真的变了（没变就不重复写盘）。 */
    fun setThresholdDp(value: Int): Boolean {
        val clamped = value.coerceIn(MIN_THRESHOLD_DP, MAX_THRESHOLD_DP)
        if (clamped == thresholdDp) return false
        thresholdDp = clamped
        persist()
        return true
    }

    /** 设置增益：按 0.1 量化后夹进 0.2~3.0，返回是否真的变了。 */
    fun setGain(value: Float): Boolean {
        val quantized = quantizeGain(value)
        if (abs(quantized - gain) < GAIN_EPSILON) return false
        gain = quantized
        persist()
        return true
    }

    /** 一键恢复默认，返回恢复前是否偏离过默认值。 */
    fun resetToDefaults(): Boolean {
        val changed = !isDefault()
        thresholdDp = DEFAULT_THRESHOLD_DP
        gain = DEFAULT_GAIN
        persist()
        return changed
    }

    /** 当前参数是不是默认值。 */
    fun isDefault(): Boolean =
        thresholdDp == DEFAULT_THRESHOLD_DP && abs(gain - DEFAULT_GAIN) < GAIN_EPSILON

    /**
     * 竖直位移 → 亮度变化量，取值 [-1, 1]，正数表示调亮。
     *
     * @param dyDp 手指竖直位移（dp，上滑为正）
     * @param screenHeightDp 手势区高度（dp），整屏高度对应 100% 亮度行程，与 GestureHandler 一致
     */
    fun deltaToBrightness(dyDp: Float, screenHeightDp: Float): Float {
        if (screenHeightDp <= 0f) return 0f
        return (-dyDp / screenHeightDp * gain).coerceIn(-1f, 1f)
    }

    /**
     * 竖直位移 → 音量变化量，取值 [-1, 1]，正数表示调大（乘 maxVolume 得到档位变化）。
     *
     * 音量没有屏幕高度参数，按 [VOLUME_FULL_SCALE_DP] 折算全程行程：
     * 480dp 约等于 1080p 电视在 density 2.0 下的整屏高度，跟原实现的手感是一个量级。
     */
    fun deltaToVolume(dyDp: Float): Float {
        return (-dyDp / VOLUME_FULL_SCALE_DP * gain).coerceIn(-1f, 1f)
    }

    /** 位移是否已经越过阈值 —— 越过后手势才开始真正调亮度/音量。 */
    fun applyThreshold(dyDp: Float): Boolean = abs(dyDp) >= thresholdDp.toFloat()

    private fun persist() {
        store.save(F010Settings(thresholdDp, gain))
    }

    private fun quantizeGain(value: Float): Float =
        ((value * 10f).roundToInt() / 10f).coerceIn(MIN_GAIN, MAX_GAIN)

    companion object {
        const val MIN_THRESHOLD_DP = 1
        const val MAX_THRESHOLD_DP = 60

        /** 原实现写死 50px 起调，1080p@2x 的盒子上大概 24dp，取它做默认值。 */
        const val DEFAULT_THRESHOLD_DP = 24

        const val MIN_GAIN = 0.2f
        const val MAX_GAIN = 3.0f
        const val DEFAULT_GAIN = 1.0f

        /** 增益滑杆的最小步进。 */
        const val GAIN_STEP = 0.1f

        /** 音量全程行程（dp）。 */
        const val VOLUME_FULL_SCALE_DP = 480f

        private const val GAIN_EPSILON = 0.01f

        /** 默认参数，界面「恢复默认」与首次进页面都取这里。 */
        val defaults: F010Settings = F010Settings(DEFAULT_THRESHOLD_DP, DEFAULT_GAIN)
    }
}