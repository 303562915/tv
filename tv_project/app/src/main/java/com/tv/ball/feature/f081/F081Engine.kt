package com.tv.ball.feature.f081

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.abs

/**
 * 一份完整的播放默认值快照（六项）。
 *
 * 为什么单独做成一份不可变数据类，而不是六个散在 SharedPreferences 里的键：
 * 播放页拿到的必须是"某一刻的完整一份"。如果播放中逐个键去读，别的页面正好写了一半，
 * 正在播的这一集就会出现"倍速变了、比例没变"这种半新半旧的状态。所以约定是：
 * 引擎只放出 [PlaybackDefaults] 快照，播放页在起播那一刻读一次，然后整集都不再变。
 *
 * @param speed            起播倍速，范围 [F081Engine.MIN_SPEED] ~ [F081Engine.MAX_SPEED]，落盘前会吸附到最近的档位
 * @param aspectMode       画面比例，取值见 [F081Engine.ASPECT_OPTIONS]，未知值按 [F081Engine.ASPECT_FIT] 处理
 * @param autoPlayNext     播完自动连播下一集
 * @param seekStepSec      快进 / 快退步长（秒），范围 [F081Engine.MIN_SEEK_STEP_SEC] ~ [F081Engine.MAX_SEEK_STEP_SEC]
 * @param longPressSpeed   长按快进的倍速，范围 [F081Engine.MIN_LONG_PRESS_SPEED] ~ [F081Engine.MAX_LONG_PRESS_SPEED]
 * @param subtitleAutoLoad 起播后自动加载同名字幕
 */
data class PlaybackDefaults(
    val speed: Float = F081Engine.DEFAULT_SPEED,
    val aspectMode: String = F081Engine.DEFAULT_ASPECT,
    val autoPlayNext: Boolean = F081Engine.DEFAULT_AUTO_NEXT,
    val seekStepSec: Int = F081Engine.DEFAULT_SEEK_STEP_SEC,
    val longPressSpeed: Float = F081Engine.DEFAULT_LONG_PRESS_SPEED,
    val subtitleAutoLoad: Boolean = F081Engine.DEFAULT_SUBTITLE_AUTO_LOAD
)

/**
 * 「播放默认值中心」引擎（六项：倍速 / 比例 / 自动连播 / 快进步长 / 长按倍速 / 字幕自动加载）。
 *
 * 三个动作分得很清楚，界面不该混用：
 *
 * 1. [load]：读出当前落盘的默认值并夹进合法区间。页面进来时调一次。
 * 2. [save]：校验 + 落盘，并把这份值发布成"当前快照"。用户点「保存默认值」时调。
 * 3. [applyTo]：**不落盘**，只把一份配置校验后发布成新快照，然后把快照返回给调用方。
 *    这就是"对新播放生效"的实现方式——播放页（或下一次起播）来读 [current]，
 *    读到的永远是最近一次发布的那一份完整快照；正在播的那一集手里握着旧引用，不受影响。
 *
 * 校验（[sanitize]）不是防御性摆设：SharedPreferences 里的值可能来自旧版本 APK、手改的 XML、
 * 或者别的功能写串了类型。倍速 0 会让播放卡死，比例是个不认识的字符串会让画面直接黑屏，
 * 所以所有入口（[load] / [save] / [applyTo]）都过同一套夹取逻辑，外面不需要再判一次。
 *
 * 本类无状态副作用、不碰主线程约束：SharedPreferences 只有六个小键，同步读写即可。
 */
object F081Engine {

    /** 落盘用的 SharedPreferences 文件名。 */
    const val PREFS_NAME: String = "f081_playback_defaults"

    private const val KEY_SPEED = "speed"
    private const val KEY_ASPECT = "aspect_mode"
    private const val KEY_AUTO_NEXT = "auto_play_next"
    private const val KEY_SEEK_STEP = "seek_step_sec"
    private const val KEY_LONG_PRESS = "long_press_speed"
    private const val KEY_SUBTITLE = "subtitle_auto_load"

    /** 画面比例：适应屏幕（留黑边，不裁画面）。 */
    const val ASPECT_FIT: String = "fit"

    /** 画面比例：裁切填满（可能裁掉边缘）。 */
    const val ASPECT_CROP: String = "crop"

    /** 画面比例：拉伸铺满（会变形）。 */
    const val ASPECT_STRETCH: String = "stretch"

    /** 画面比例：原始比例（按片源本身的比例居中）。 */
    const val ASPECT_ORIGINAL: String = "original"

    /** 出厂默认倍速。 */
    const val DEFAULT_SPEED: Float = 1.0f

    /** 出厂默认比例。 */
    const val DEFAULT_ASPECT: String = ASPECT_FIT

    /** 出厂默认：自动连播开。 */
    const val DEFAULT_AUTO_NEXT: Boolean = true

    /** 出厂默认快进步长（秒）。 */
    const val DEFAULT_SEEK_STEP_SEC: Int = 10

    /** 出厂默认长按倍速。 */
    const val DEFAULT_LONG_PRESS_SPEED: Float = 2.0f

    /** 出厂默认：字幕自动加载开。 */
    const val DEFAULT_SUBTITLE_AUTO_LOAD: Boolean = true

    /** 倍速下限：再低就没有观看意义，而且部分解码器会把 0.25x 播成静帧。 */
    const val MIN_SPEED: Float = 0.5f

    /** 倍速上限：超过 3x 音频基本听不出内容。 */
    const val MAX_SPEED: Float = 3.0f

    /** 快进步长下限（秒）。 */
    const val MIN_SEEK_STEP_SEC: Int = 5

    /** 快进步长上限（秒）：单次超过 2 分钟等于没法微调。 */
    const val MAX_SEEK_STEP_SEC: Int = 120

    /** 长按倍速下限。 */
    const val MIN_LONG_PRESS_SPEED: Float = 1.5f

    /** 长按倍速上限。 */
    const val MAX_LONG_PRESS_SPEED: Float = 4.0f

    /** 倍速下拉的可选档位（升序，[sanitize] 会把越界值吸附到最近的档位）。 */
    val SPEED_OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 比例下拉的可选值。 */
    val ASPECT_OPTIONS: List<String> = listOf(ASPECT_FIT, ASPECT_CROP, ASPECT_STRETCH, ASPECT_ORIGINAL)

    /** 快进步长下拉的常用档位；允许的其实是 [MIN_SEEK_STEP_SEC] ~ [MAX_SEEK_STEP_SEC] 内任意整数。 */
    val SEEK_STEP_OPTIONS: List<Int> = listOf(5, 10, 15, 30, 60)

    /** 长按倍速下拉的可选档位。 */
    val LONG_PRESS_SPEED_OPTIONS: List<Float> = listOf(1.5f, 2.0f, 3.0f, 4.0f)

    /**
     * 最近一次被发布出来的快照。
     *
     * volatile：保存动作可能发生在设置页的线程上，播放页在播放线程上读；
     * 一份不可变对象整体替换，天然不会有"读到一半"的问题。
     */
    @Volatile
    private var applied: PlaybackDefaults = PlaybackDefaults()

    // ===== 读写 =====

    /** 出厂默认组合：一键恢复默认就是回到这一份。 */
    fun defaults(): PlaybackDefaults = PlaybackDefaults(
        speed = DEFAULT_SPEED,
        aspectMode = DEFAULT_ASPECT,
        autoPlayNext = DEFAULT_AUTO_NEXT,
        seekStepSec = DEFAULT_SEEK_STEP_SEC,
        longPressSpeed = DEFAULT_LONG_PRESS_SPEED,
        subtitleAutoLoad = DEFAULT_SUBTITLE_AUTO_LOAD
    )

    /**
     * 读出当前默认值。
     *
     * 任何一项缺失或类型不对（旧版本残留、被手改过的 prefs）都退回到出厂值的那一项，
     * 不抛异常：设置页在坏数据面前也应该能正常打开，否则用户连"恢复默认"都点不到。
     */
    fun load(context: Context): PlaybackDefaults {
        val prefs = prefs(context)
        val fallback = defaults()
        return sanitize(
            PlaybackDefaults(
                speed = readFloat(prefs, KEY_SPEED, fallback.speed),
                aspectMode = readString(prefs, KEY_ASPECT, fallback.aspectMode),
                autoPlayNext = readBoolean(prefs, KEY_AUTO_NEXT, fallback.autoPlayNext),
                seekStepSec = readInt(prefs, KEY_SEEK_STEP, fallback.seekStepSec),
                longPressSpeed = readFloat(prefs, KEY_LONG_PRESS, fallback.longPressSpeed),
                subtitleAutoLoad = readBoolean(prefs, KEY_SUBTITLE, fallback.subtitleAutoLoad)
            )
        )
    }

    /**
     * 校验后落盘，并把这份值发布成当前快照。
     *
     * @return 真正写下去的那一份（可能和传入的不同：越界的倍速会被吸附到最近档位）。
     *
     * 用 `apply()` 而不是 `commit()`：设置页不需要等磁盘回写，下一次起播读的就是内存里的新值；
     * 快照本身在落盘之前就已经发布，界面拿到的返回值就是权威值。
     */
    fun save(context: Context, d: PlaybackDefaults): PlaybackDefaults {
        val safe = sanitize(d)
        prefs(context).edit()
            .putFloat(KEY_SPEED, safe.speed)
            .putString(KEY_ASPECT, safe.aspectMode)
            .putBoolean(KEY_AUTO_NEXT, safe.autoPlayNext)
            .putInt(KEY_SEEK_STEP, safe.seekStepSec)
            .putFloat(KEY_LONG_PRESS, safe.longPressSpeed)
            .putBoolean(KEY_SUBTITLE, safe.subtitleAutoLoad)
            .apply()
        return applyTo(safe)
    }

    /**
     * 把一份默认值发布成"对新播放生效"的快照，并原样返回它。
     *
     * 语义要点（设置页和播放页都按这个约定写）：
     * - **不改盘**：这是运行期发布，落盘是 [save] 的事；
     * - **只影响此后的新播放**：正在播的那一集手里握的是起播时读到的旧快照，这里替换的只是
     *   "下一次起播会读到什么"，所以不会出现播放中途倍速突变的观感；
     * - 返回值和 [current] 是同一个对象，调用方可以直接把它交给播放器构造参数，不必再读一次。
     *
     * 传入的配置同样过 [sanitize]，所以手搓一份越界值进来也不会把播放页带崩。
     */
    fun applyTo(newDefaults: PlaybackDefaults): PlaybackDefaults {
        val snapshot = sanitize(newDefaults)
        applied = snapshot
        return snapshot
    }

    /** 当前快照：下一次起播读到的就是这一份。 */
    fun current(): PlaybackDefaults = applied

    /** 这份配置是否就是出厂组合（摘要区用它区分"默认"和"自定义"）。 */
    fun isDefault(d: PlaybackDefaults): Boolean = sanitize(d) == defaults()

    // ===== 校验 =====

    /**
     * 把任意来源的一份配置夹进合法区间。
     *
     * 规则：
     * - 倍速：非有限值（NaN / Infinity）退回 [DEFAULT_SPEED]；否则夹到
     *   [MIN_SPEED] ~ [MAX_SPEED] 再吸附到 [SPEED_OPTIONS] 里最近的一档（等距时取小的一档）；
     * - 比例：不认识的 key 退到 [ASPECT_FIT]——比让播放器收到一个空比例安全得多；
     * - 快进步长：夹到 [MIN_SEEK_STEP_SEC] ~ [MAX_SEEK_STEP_SEC]，不吸附档位（允许用户要 7 秒这种值）；
     * - 长按倍速：同倍速，但用 [LONG_PRESS_SPEED_OPTIONS] 吸附；
     * - 两个开关原样保留。
     */
    fun sanitize(d: PlaybackDefaults): PlaybackDefaults = PlaybackDefaults(
        speed = snapSpeed(d.speed),
        aspectMode = if (ASPECT_OPTIONS.contains(d.aspectMode)) d.aspectMode else ASPECT_FIT,
        autoPlayNext = d.autoPlayNext,
        seekStepSec = d.seekStepSec.coerceIn(MIN_SEEK_STEP_SEC, MAX_SEEK_STEP_SEC),
        longPressSpeed = snapLongPressSpeed(d.longPressSpeed),
        subtitleAutoLoad = d.subtitleAutoLoad
    )

    private fun snapSpeed(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_SPEED
        val clamped = value.coerceIn(MIN_SPEED, MAX_SPEED)
        return SPEED_OPTIONS.minByOrNull { abs(it - clamped) } ?: DEFAULT_SPEED
    }

    private fun snapLongPressSpeed(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_LONG_PRESS_SPEED
        val clamped = value.coerceIn(MIN_LONG_PRESS_SPEED, MAX_LONG_PRESS_SPEED)
        return LONG_PRESS_SPEED_OPTIONS.minByOrNull { abs(it - clamped) } ?: DEFAULT_LONG_PRESS_SPEED
    }

    // ===== 文案（界面与摘要共用同一套格式化，避免两处各写一份） =====

    /** 倍速文案：`1.0x` / `0.75x` / `2.5x`（至少保留一位小数，档位读起来才整齐）。 */
    fun speedLabel(speed: Float): String = formatTier(speed) + "x"

    /** 比例文案。 */
    fun aspectLabel(mode: String): String = when (mode) {
        ASPECT_CROP -> "裁切填满"
        ASPECT_STRETCH -> "拉伸铺满"
        ASPECT_ORIGINAL -> "原始比例"
        else -> "适应屏幕"
    }

    /** 快进步长文案：`10 秒`。 */
    fun seekStepLabel(sec: Int): String = "$sec 秒"

    /** 长按倍速文案：`2.0x`。 */
    fun longPressSpeedLabel(speed: Float): String = formatTier(speed) + "x"

    /** 开关状态的单字写法。 */
    fun onOffLabel(on: Boolean): String = if (on) "开" else "关"

    /**
     * 一行摘要，设置页「当前生效配置」用的就是它：
     * `倍速 1.0x · 比例 适应屏幕 · 自动连播 开 · 快进 10 秒 · 长按 2.0x · 字幕自动加载 开`
     */
    fun summary(d: PlaybackDefaults): String {
        val safe = sanitize(d)
        return listOf(
            "倍速 " + speedLabel(safe.speed),
            "比例 " + aspectLabel(safe.aspectMode),
            "自动连播 " + onOffLabel(safe.autoPlayNext),
            "快进 " + seekStepLabel(safe.seekStepSec),
            "长按 " + longPressSpeedLabel(safe.longPressSpeed),
            "字幕自动加载 " + onOffLabel(safe.subtitleAutoLoad)
        ).joinToString(" · ")
    }

    /** 档位格式化：两位小数起步，末尾多余的 0 去掉但至少留一位小数。 */
    private fun formatTier(value: Float): String {
        val text = String.format(Locale.US, "%.2f", value).trimEnd('0')
        return if (text.endsWith('.')) text + "0" else text
    }

    // ===== prefs 读取（类型不对就当没有） =====

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readFloat(prefs: SharedPreferences, key: String, fallback: Float): Float =
        try {
            prefs.getFloat(key, fallback)
        } catch (e: ClassCastException) {
            fallback
        }

    private fun readInt(prefs: SharedPreferences, key: String, fallback: Int): Int =
        try {
            prefs.getInt(key, fallback)
        } catch (e: ClassCastException) {
            fallback
        }

    private fun readBoolean(prefs: SharedPreferences, key: String, fallback: Boolean): Boolean =
        try {
            prefs.getBoolean(key, fallback)
        } catch (e: ClassCastException) {
            fallback
        }

    private fun readString(prefs: SharedPreferences, key: String, fallback: String): String =
        try {
            prefs.getString(key, fallback) ?: fallback
        } catch (e: ClassCastException) {
            fallback
        }
}
