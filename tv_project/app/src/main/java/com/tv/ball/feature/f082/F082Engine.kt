package com.tv.ball.feature.f082

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.abs

/**
 * 界面整体缩放的五个档位。
 *
 * 为什么是 90% ~ 130% 这五档，而不是一个连续滑杆：
 *
 * - 电视上的观看距离远，字号只有"偏小 / 正常 / 偏大"这几级能被一眼分辨；
 *   连续值反而让人反复微调到不敢确认，最后停在 103% 这种没有任何意义的数上；
 * - 每档间隔 10%，既保证相邻档肉眼可分，又让 5 个单选按钮在一屏里排得下；
 * - 90% 是给"小屏电视 + 片名特别长"的场景留的，130% 是给大客厅远距离看的。
 *
 * [percent] 是**百分比整数**，也是唯一会被落盘的值。枚举本身的具名常量（P90 等）
 * 只是给代码读的：prefs 里存 120，读回来换算成 P120，不存枚举名——
 * 枚举改名（比如以后加 P95）时旧数据不会整片失效。
 */
enum class ScaleLevel(val percent: Int) {
    P90(90),
    P100(100),
    P110(110),
    P120(120),
    P130(130);

    companion object {

        /** 出厂档位：100%，也就是"什么都没开"的状态。 */
        val DEFAULT: ScaleLevel = P100

        /** 所有档位，升序。单选按钮和"吸附到最近一档"都按这个顺序来。 */
        val TIERS: List<ScaleLevel> = ScaleLevel.values().toList()

        /**
         * 把一个任意百分比吸附到最近的档位（等距取小的一档）。
         *
         * 用于读盘：旧版本写下的 105、被手改过的 prefs、上游按 dp 换算过的值，
         * 全都落到某个确定档位上，不会有"半个档位"这种界面无法表示的状态。
         * 非正数（0 / 负数）一律退回 [DEFAULT]——视觉上相当于"缩没了"，没有意义。
         */
        fun fromPercent(percent: Int): ScaleLevel {
            if (percent <= 0) return DEFAULT
            return ScaleLevel.values().minByOrNull { abs(it.percent - percent) } ?: DEFAULT
        }
    }
}

/**
 * 「界面缩放与字号」引擎。
 *
 * 只做三件事，界面和全站都按同一套算法用：
 *
 * 1. [scale]：档位 → 尺寸系数（90 → 0.90f）。所有 dp 尺寸乘它。
 * 2. [fontScale]：档位 → 字号系数（90 → 0.90f）。所有 sp 字号乘它。
 *    现在两者数值相同，但**故意分成两个函数**：字号有最小可读下限，将来若要
 *    "尺寸缩到 90% 但字号只缩到 95%"，只改 [fontScale] 一处，全站调用方不用动。
 * 3. [load] / [save]：`f082_ui_scale` 里 `ui_scale_percent` 这个整数键的读写。
 *
 * 为什么强调"重启应用后全站生效"（见 [APPLY_NOTE]）：
 * 缩放最终要落到 `Resources` 的 `Configuration.fontScale` / `densityDpi` 上，
 * 而已经 inflate 出来的 View、已经量过一遍的布局树都不会自己重算。
 * 所以本页的做法是"立即写盘 + 页内实时预览"，全站生效交给下一次进程启动 ——
 * 这样不会出现"设置页自己重排到一半、其它 Activity 还停在旧尺寸"的撕裂状态。
 */
object F082Engine {

    /** 落盘用的 SharedPreferences 文件名（每个功能一份，互不干扰）。 */
    const val PREFS_NAME: String = "f082_ui_scale"

    /** 缩放的百分比整数键名。 */
    const val KEY_SCALE_PERCENT: String = "ui_scale_percent"

    /** 预览区的基准字号（sp）。示例文案就是按这个值的 `× scale` 渲染的。 */
    const val BASE_TEXT_SP: Float = 16f

    /** 生效方式的统一说明，界面和文档引用同一句，避免两处口径不一致。 */
    const val APPLY_NOTE: String = "重启应用后全站生效"

    /**
     * 档位 → 尺寸系数：90 → 0.90f，130 → 1.30f。
     *
     * 返回的是**纯系数**，不带单位：调用方自己决定乘在 dp、px 还是 sp 上。
     */
    fun scale(level: ScaleLevel): Float = level.percent / 100f

    /**
     * 档位 → 字号系数。
     *
     * 与 [scale] 分开的原因见类注释：字号将来可能要单独夹一个下限
     * （比如尺寸缩到 90%、字号只缩到 95% 以免 4K 电视上小字糊掉）。
     */
    fun fontScale(level: ScaleLevel): Float = scale(level)

    /**
     * 档位的稳定标识：`scale_90` / `scale_120`。
     *
     * 给状态行、埋点、以及将来 prefs 迁移用。**不是**给人看的文案：
     * 界面上显示的"120%"走 [percentLabel]，两者不要混用。
     */
    fun describeKey(level: ScaleLevel): String = "scale_" + level.percent

    /** 界面显示用的百分比文案：`90%` / `120%`。 */
    fun percentLabel(level: ScaleLevel): String = level.percent.toString() + "%"

    /**
     * 把基准字号按档位换算成实际字号（sp）。
     *
     * 预览区用的就是它，全站字号也应该走同一条路径，保证"预览看到的"和
     * "重启后全站得到的"是同一个算法算出来的，不会差几个 px。
     */
    fun scaledSp(baseSp: Float, level: ScaleLevel): Float = baseSp * fontScale(level)

    /** 字号的显示格式：`16.0` / `19.2`（固定一位小数，预览对比时两行才对得齐）。 */
    fun formatSp(value: Float): String = String.format(Locale.US, "%.1f", value)

    /** 出厂档位。 */
    fun defaults(): ScaleLevel = ScaleLevel.DEFAULT

    // ===== 读写 =====

    /**
     * 读当前落盘的档位。
     *
     * 缺失、类型不对（被别的功能写串了类型）、超出五档之外的整数，全部吸附到最近的档位，
     * 不抛异常：设置页在坏数据面前也要能正常打开，否则用户连"恢复 100%"都点不到。
     */
    fun load(context: Context): ScaleLevel {
        val store = prefs(context)
        val raw = try {
            store.getInt(KEY_SCALE_PERCENT, ScaleLevel.DEFAULT.percent)
        } catch (e: ClassCastException) {
            ScaleLevel.DEFAULT.percent
        }
        val level = ScaleLevel.fromPercent(raw)
        applied = level
        return level
    }

    /**
     * 校验后落盘，并把它发布成"当前档位"。
     *
     * 用 `apply()` 而不是 `commit()`：设置页不需要等磁盘回写，选完就返回；
     * 下一次进程启动读到的就是这里写下去的值。
     *
     * @return 真正写下去的那一档（传入值本身已经是枚举，所以就是它自己）。
     */
    fun save(context: Context, level: ScaleLevel): ScaleLevel {
        prefs(context).edit()
            .putInt(KEY_SCALE_PERCENT, level.percent)
            .apply()
        applied = level
        return level
    }

    /** 最近一次读到 / 写入的档位：进程内其它页面要即时读数时用它，不必再碰盘。 */
    fun current(): ScaleLevel = applied

    /** 是否就是出厂档位（摘要区用它区分"默认"和"自定义"）。 */
    fun isDefault(level: ScaleLevel): Boolean = level == ScaleLevel.DEFAULT

    /** 一行摘要：`界面缩放 120%（scale_120）`。 */
    fun summary(level: ScaleLevel): String =
        "界面缩放 " + percentLabel(level) + "（" + describeKey(level) + "）"

    /**
     * 落盘值换算成 `Configuration.fontScale` 的相对比例时用的就是它。
     *
     * 之所以单独列出来：系统 base fontScale 可能被用户在系统设置里调过（比如 1.15），
     * 本功能的档位是**乘在系统值之上**的，直接覆盖会让系统无障碍设置失效。
     */
    fun composeWithSystem(systemFontScale: Float, level: ScaleLevel): Float {
        val base = if (systemFontScale.isFinite() && systemFontScale > 0f) systemFontScale else 1f
        return base * fontScale(level)
    }

    /**
     * 最近一次被读到 / 写入的档位。
     *
     * volatile：写发生在设置页线程，读可能发生在播放页；枚举引用整体替换，不会有中间态。
     */
    @Volatile
    private var applied: ScaleLevel = ScaleLevel.DEFAULT

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
