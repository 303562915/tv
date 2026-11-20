package com.tv.ball.feature.f012

import android.content.Context
import android.graphics.Color

/**
 * 字幕底色档位。
 *
 * 持久化只认 [name]，不存枚举序数：以后中间插一档也不会把老用户的设置读串。
 */
enum class SubtitleBackground {
    /** 无底色，字幕直接压在画面上。 */
    NONE,

    /** 半透明黑框，制片方最常见的基线样式。 */
    TRANSLUCENT,

    /** 不透明黑框，亮场景下也不糊。 */
    OPAQUE;

    companion object {
        /** 读档位时的兜底：存的名字不认识就回半透明，不抛异常。 */
        fun ofName(name: String?): SubtitleBackground =
            values().firstOrNull { it.name == name } ?: TRANSLUCENT
    }
}

/**
 * 一份字幕样式。四个维度互相独立：改字号不动颜色，开关描边不动背景。
 *
 * 所有字段在 [sanitized] 里夹到合法区间，外部拿到的实例永远可以直接丢给 TextView。
 */
data class SubtitleStyle(
    /** 字号，单位 sp，区间 [FONT_MIN_SP]..[FONT_MAX_SP]。 */
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    /** 文字颜色，取值来自 [Palette]。 */
    val textColor: Int = Palette.WHITE,
    /** 描边开关。 */
    val outlineEnabled: Boolean = true,
    /** 描边宽度，单位 dp，区间 [OUTLINE_MIN_DP]..[OUTLINE_MAX_DP]。 */
    val outlineWidthDp: Int = DEFAULT_OUTLINE_WIDTH_DP,
    /** 背景档位。 */
    val background: SubtitleBackground = SubtitleBackground.TRANSLUCENT
) {

    /** 描边是否真的会画出来：开关打开且宽度大于 0。 */
    val outlineEffective: Boolean
        get() = outlineEnabled && outlineWidthDp > OUTLINE_MIN_DP

    fun withFontSize(sp: Int): SubtitleStyle = copy(fontSizeSp = sp).sanitized()

    fun withTextColor(color: Int): SubtitleStyle = copy(textColor = color).sanitized()

    fun withOutlineEnabled(enabled: Boolean): SubtitleStyle =
        copy(outlineEnabled = enabled).sanitized()

    fun withOutlineWidth(dp: Int): SubtitleStyle = copy(outlineWidthDp = dp).sanitized()

    fun withBackground(bg: SubtitleBackground): SubtitleStyle = copy(background = bg).sanitized()

    /** 夹到合法区间；颜色不在预设里就当白色，避免读到脏数据后预览变透明。 */
    fun sanitized(): SubtitleStyle = SubtitleStyle(
        fontSizeSp = fontSizeSp.coerceIn(FONT_MIN_SP, FONT_MAX_SP),
        textColor = Palette.normalize(textColor),
        outlineEnabled = outlineEnabled,
        outlineWidthDp = outlineWidthDp.coerceIn(OUTLINE_MIN_DP, OUTLINE_MAX_DP),
        background = background
    )

    companion object {
        /** 大屏默认 18sp：1080p 电视上离 3 米也能看清，又不至于盖住画面。 */
        const val DEFAULT_FONT_SIZE_SP = 18

        /** 默认 2dp 描边：亮场景下白字不糊，暗场景也看不出明显的黑边。 */
        const val DEFAULT_OUTLINE_WIDTH_DP = 2

        const val FONT_MIN_SP = 12
        const val FONT_MAX_SP = 40
        const val OUTLINE_MIN_DP = 0
        const val OUTLINE_MAX_DP = 6

        /** 字幕默认样式，恢复默认按钮与首次进入都用它。 */
        val DEFAULT: SubtitleStyle = SubtitleStyle()
    }
}

/**
 * 字幕预设文字色。
 *
 * 颜色只能这样定义，因为 colors.xml 是共享文件：多个功能并行开发时它会被并行任务覆盖，
 * 而字幕预设色属于本功能的私有表现，不是全站语义 token，也不该往全局色板里塞。
 * 所以六个色值集中放在这里，用 Color.parseColor 一次性解析，避免每帧重新解析字符串。
 */
object Palette {

    val WHITE: Int = Color.parseColor("#FFFFFFFF")

    /** 淡黄：纪录片与访谈最常用的可读色。 */
    val YELLOW: Int = Color.parseColor("#FFFFE066")

    val CYAN: Int = Color.parseColor("#FF4DD0E1")

    val GREEN: Int = Color.parseColor("#FFB2FF59")

    val PINK: Int = Color.parseColor("#FFFF80AB")

    /** 黑色：浅背景画面（雪景、白墙）下用。 */
    val BLACK: Int = Color.parseColor("#FF000000")

    /** 界面上的排列顺序，颜色块按这个顺序生成。 */
    val ALL: List<Int> = listOf(WHITE, YELLOW, CYAN, GREEN, PINK, BLACK)

    /** 保证拿到的颜色一定是六个预设之一；非法值回白色而不是留个透明。 */
    fun normalize(color: Int): Int = if (ALL.contains(color)) color else WHITE
}

/**
 * 字幕样式的读写。
 *
 * 只做持久化，不含任何 View 逻辑：播放页拿 [load] 的结果直接套到字幕 TextView 上，
 * 设置页拿同一份结果做预览，两边口径一致。
 */
object F012Engine {

    /** 需求里约定的 SharedPreferences 文件名。 */
    private const val PREFS_NAME = "f012_subtitle_style"

    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_TEXT_COLOR = "text_color"
    private const val KEY_OUTLINE_ON = "outline_enabled"
    private const val KEY_OUTLINE_WIDTH = "outline_width_dp"
    private const val KEY_BACKGROUND = "background_mode"

    /** 用 applicationContext 取实例，不持有 Activity。 */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读设置；字段缺省或越界都会被 [SubtitleStyle.sanitized] 夹回合法值。 */
    fun load(context: Context): SubtitleStyle {
        val p = prefs(context)
        return SubtitleStyle(
            fontSizeSp = p.getInt(KEY_FONT_SIZE, SubtitleStyle.DEFAULT_FONT_SIZE_SP),
            textColor = p.getInt(KEY_TEXT_COLOR, Palette.WHITE),
            outlineEnabled = p.getBoolean(KEY_OUTLINE_ON, true),
            outlineWidthDp = p.getInt(KEY_OUTLINE_WIDTH, SubtitleStyle.DEFAULT_OUTLINE_WIDTH_DP),
            background = SubtitleBackground.ofName(
                p.getString(KEY_BACKGROUND, SubtitleBackground.TRANSLUCENT.name)
            )
        ).sanitized()
    }

    /** 写设置；apply() 异步落盘，主线程不做磁盘 IO。 */
    fun save(context: Context, style: SubtitleStyle) {
        val safe = style.sanitized()
        prefs(context).edit()
            .putInt(KEY_FONT_SIZE, safe.fontSizeSp)
            .putInt(KEY_TEXT_COLOR, safe.textColor)
            .putBoolean(KEY_OUTLINE_ON, safe.outlineEnabled)
            .putInt(KEY_OUTLINE_WIDTH, safe.outlineWidthDp)
            .putString(KEY_BACKGROUND, safe.background.name)
            .apply()
    }

    /** 恢复默认并落盘，返回落盘后的样式，调用方直接拿去渲染。 */
    fun reset(context: Context): SubtitleStyle {
        val style = SubtitleStyle.DEFAULT
        save(context, style)
        return style
    }
}