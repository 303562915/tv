package com.tv.ball.feature.f078

import android.content.Context

/**
 * 一条可被「分组重置」的设置项。
 *
 * [prefsName] 是这个项目里真实存在的 SharedPreferences 文件名（如 `f009_step_policy`），
 * [key] 是那份文件里的真实键名，[defaultValue] 是读这份设置的代码在「键不存在」时使用的兜底值
 * （`f009_step_policy/step_seconds` 的兜底就是 `StepPolicy.DEFAULT.seconds`，即 10 秒），
 * [group] 是它在重置页里归属的分组。
 *
 * [defaultValue] 用 `Any?`：项目里的设置项本来就是 Boolean / Int / Long / Float / String 混着存，
 * 硬塞成字符串再解析回来只会在写回时把 Boolean 写成 `"false"` 这类字符串，读侧 `getBoolean` 会直接抛
 * ClassCastException。保留原类型，写回时才敢按类型落盘；`null` 表示「这一项默认就是没有存储值」
 * （如 F091 的启动采样记录），重置即清掉这个键。
 */
data class SettingKey(
    val prefsName: String,
    val key: String,
    val defaultValue: Any?,
    val group: String
)

/**
 * 一项设置此刻的样子，供界面直接展示。
 *
 * [current] / [default] 是已经格式化好的文案（带单位、开关转「开/关」），
 * [isDefault] 表示「存储值不存在，或存储值就等于默认值」。
 * 刻意只给字符串：界面层不需要知道这一项到底是 Int 还是 Boolean，也就不需要每个键写一遍分支。
 */
data class KeyState(
    val key: SettingKey,
    val current: String,
    val default: String,
    val isDefault: Boolean
)

/**
 * F078「设置分组重置」的能力层。
 *
 * 设置散在十几个功能页里，各有各的 SharedPreferences 文件（`f009_step_policy`、`f012_subtitle_style`、
 * `f056_proxy_prefs`……）。用户把某一组调乱之后，没有人愿意一个个页面点回去，
 * 所以这一层维护一份**跨文件的键清单**，并提供三种粒度：
 *
 *   · [catalog] 12 个项目里真实存在的设置键，按分组排好；
 *   · [resetGroup] 只重置某一组，返回真正被恢复的键数；
 *   · [resetAll] 全部重置。
 *
 * 重置动作是「把键从文件里删掉」而不是「写一个和默认值相同的值」：项目里每个读设置的入口都是
 * `prefs.getInt(KEY, DEFAULT)` 这种写法，键不存在时读到的就是各功能自己声明的那份兜底值——
 * 那才是「默认」的唯一权威。写回一个值反而有两个坏处：默认值将来改了口径，老用户文件里会留着旧值；
 * 而且不同读侧类型不一致时（Boolean 写成字符串）会直接崩。
 *
 * 这一层只依赖 `Context`，不碰界面、不持有 Activity；[catalog] 里的中文标题是字面量，
 * 和 F077 的设置项索引一样，为的是没有 Application 初始化时也能构造出来做单测。
 */
object F078Engine {

    /** 清单规模：12 项，验收与测试直接比这个常量。 */
    const val CATALOG_SIZE: Int = 12

    /** 没有存储值（或默认值本身为空）时展示的占位文案。 */
    const val UNSET: String = "—"

    /**
     * 12 个真实设置键，按分组排列（同组内保持声明顺序）。
     *
     * 默认值不是拍脑袋写的，全部取自对应功能的兜底值：
     *   · `f009_step_policy/step_seconds` → `StepPolicy.DEFAULT.seconds` = 10；
     *   · `f009_step_policy/smart_skip` → `readSmart()` 的兜底 false；
     *   · `f012_subtitle_style/font_size_sp` → `DEFAULT_FONT_SIZE_SP` = 18；
     *   · `f012_subtitle_style/outline_width_dp` → `DEFAULT_OUTLINE_WIDTH_DP` = 2；
     *   · `f017_prefs/f017_shift_ms` → `getLong(KEY_SHIFT_MS, 0L)`；
     *   · `video_player_prefs/f030_queue_start_percent` → `DEFAULT_START_PERCENT` = 5；
     *   · `f080_privacy/enabled` → `getBoolean(KEY_ENABLED, false)`；
     *   · `f082_ui_scale/ui_scale_percent` → 缩放基准 100%；
     *   · `f083_motion/mode` → `MotionMode.NORMAL.storageValue` = 0；
     *   · `f056_proxy_prefs/f056_proxy_enabled` → `getBoolean(..., false)`；
     *   · `f057_buffer_prefs/f057_buffer_ms` → 均衡档 `Preset.BALANCED` = 18000；
     *   · `f091_startup_trace/runs` → 没有存储值，重置即清空采样记录。
     */
    private val CATALOG: List<SettingKey> = listOf(
        // ===== 播放核心 =====
        SettingKey(
            prefsName = "f009_step_policy",
            key = "step_seconds",
            defaultValue = 10,
            group = "播放核心"
        ),
        SettingKey(
            prefsName = "f009_step_policy",
            key = "smart_skip",
            defaultValue = false,
            group = "播放核心"
        ),

        // ===== 字幕 =====
        SettingKey(
            prefsName = "f012_subtitle_style",
            key = "font_size_sp",
            defaultValue = 18,
            group = "字幕"
        ),
        SettingKey(
            prefsName = "f012_subtitle_style",
            key = "outline_width_dp",
            defaultValue = 2,
            group = "字幕"
        ),
        SettingKey(
            prefsName = "f017_prefs",
            key = "f017_shift_ms",
            defaultValue = 0L,
            group = "字幕"
        ),

        // ===== 播放队列 =====
        SettingKey(
            prefsName = "video_player_prefs",
            key = "f030_queue_start_percent",
            defaultValue = 5,
            group = "播放队列"
        ),

        // ===== 设置 =====
        SettingKey(
            prefsName = "f080_privacy",
            key = "enabled",
            defaultValue = false,
            group = "设置"
        ),

        // ===== 界面 =====
        SettingKey(
            prefsName = "f082_ui_scale",
            key = "ui_scale_percent",
            defaultValue = 100,
            group = "界面"
        ),
        SettingKey(
            prefsName = "f083_motion",
            key = "mode",
            defaultValue = 0,
            group = "界面"
        ),

        // ===== 网络 =====
        SettingKey(
            prefsName = "f056_proxy_prefs",
            key = "f056_proxy_enabled",
            defaultValue = false,
            group = "网络"
        ),
        SettingKey(
            prefsName = "f057_buffer_prefs",
            key = "f057_buffer_ms",
            defaultValue = 18_000,
            group = "网络"
        ),

        // ===== 诊断 =====
        SettingKey(
            prefsName = "f091_startup_trace",
            key = "runs",
            defaultValue = null,
            group = "诊断"
        )
    )

    /** 分组顺序 = [CATALOG] 的声明顺序，去重后固定，不随时间/哈希变化。 */
    private val GROUPS: List<String> = CATALOG.map { it.group }.distinct()

    /** 键名 → 中文标题：界面行标题用，避免直接把 `f057_buffer_ms` 这种实现细节怼到用户脸上。 */
    private val TITLES: Map<String, String> = mapOf(
        "step_seconds" to "快进快退步长",
        "smart_skip" to "智能跳过片头片尾",
        "font_size_sp" to "字幕字号",
        "outline_width_dp" to "字幕描边宽度",
        "f017_shift_ms" to "字幕时间轴平移",
        "f030_queue_start_percent" to "续播队列起播位置",
        "enabled" to "隐私模式开关",
        "ui_scale_percent" to "界面缩放比例",
        "mode" to "动效强度",
        "f056_proxy_enabled" to "代理开关",
        "f057_buffer_ms" to "缓冲目标水位",
        "runs" to "启动采样记录"
    )

    /** 数值类设置的展示单位，按键名索引；没登记的键就是纯数字。 */
    private val UNITS: Map<String, String> = mapOf(
        "step_seconds" to " 秒",
        "font_size_sp" to " sp",
        "outline_width_dp" to " dp",
        "f017_shift_ms" to " 毫秒",
        "f030_queue_start_percent" to "%",
        "ui_scale_percent" to "%",
        "f057_buffer_ms" to " 毫秒",
        "runs" to " 条记录"
    )

    /** 枚举型整数的展示文案：`f083_motion/mode` 存的是 0/1，直接显示数字没人看得懂。 */
    private val MODE_LABELS: Map<Int, String> = mapOf(0 to "标准", 1 to "减弱")

    /** 全部设置键（新列表，调用方改不坏常量表）。 */
    fun catalog(): List<SettingKey> = CATALOG.toList()

    /** 分组名，按声明顺序；界面按这个顺序铺分区。 */
    fun groups(): List<String> = GROUPS.toList()

    /** 某一组里的键，保持声明顺序。 */
    fun byGroup(group: String): List<SettingKey> = CATALOG.filter { it.group == group }

    /** 某一组里此刻不是默认值的键数——二次确认里那句「有 N 项会被重置」就是它。 */
    fun changedCount(context: Context, group: String? = null): Int {
        val states = snapshot(context)
        val scope = if (group == null) states else states.filter { it.key.group == group }
        return scope.count { !it.isDefault }
    }

    /**
     * 把 [group] 这一组的设置恢复默认，返回**真正被恢复**的键数。
     *
     * 已经是默认值（键不存在）的那些不算：界面要说「重置了 3 项」，而不是把 5 项全算上让用户以为
     * 动了几处。返回值只用于回执文案，不影响正确性。
     *
     * 同一个 prefs 文件只开一次 editor：`f009_step_policy` 里有两项、`f012_subtitle_style` 里也有两项，
     * 分开 commit 会让这两组各产生两次写盘与两次监听回调。
     */
    fun resetGroup(context: Context, group: String): Int = reset(context, byGroup(group))

    /** 全部恢复默认，返回真正被恢复的键数。 */
    fun resetAll(context: Context): Int = reset(context, CATALOG)

    /**
     * 全部键的当前状态，按「分组顺序 → 组内声明顺序」排好，界面直接铺。
     *
     * 每一次调用都重新读盘：重置之后立刻再调一次就能拿到刷新后的真实状态，
     * 不需要界面自己维护一份缓存再猜哪里变了。
     */
    fun snapshot(context: Context): List<KeyState> = CATALOG.map { stateOf(context, it) }

    /** 键的中文标题；没登记的键回落成键名，至少不会显示空行。 */
    fun title(key: SettingKey): String = TITLES[key.key] ?: key.key

    /** 改前的存储值（原始类型），没有存储值时返回 null。 */
    fun storedValue(context: Context, key: SettingKey): Any? = runCatching {
        context.applicationContext
            .getSharedPreferences(key.prefsName, Context.MODE_PRIVATE)
            .all[key.key]
    }.getOrNull()

    // ===== 内部 =====

    /** 按文件归并后逐文件清键，返回被清掉的键数。 */
    private fun reset(context: Context, keys: List<SettingKey>): Int {
        if (keys.isEmpty()) return 0
        val app = context.applicationContext
        var cleared = 0
        keys.groupBy { it.prefsName }.forEach { (prefsName, group) ->
            val prefs = runCatching {
                app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            }.getOrNull() ?: return@forEach
            val editor = prefs.edit()
            var touched = false
            group.forEach { key ->
                if (prefs.contains(key.key)) {
                    editor.remove(key.key)
                    touched = true
                    cleared++
                }
            }
            // 一项都不在这个文件里时不要白 commit 一次，避免无意义的写盘与监听回调
            if (touched) editor.apply()
        }
        return cleared
    }

    private fun stateOf(context: Context, key: SettingKey): KeyState {
        val stored = storedValue(context, key)
        val isDefault = stored == null || sameAs(stored, key.defaultValue)
        return KeyState(
            key = key,
            current = display(key, stored ?: key.defaultValue),
            default = display(key, key.defaultValue),
            isDefault = isDefault
        )
    }

    /** 类型相同才比；类型不同（历史遗留写错类型）一律算「被改过」，让用户有机会一键修回去。 */
    private fun sameAs(stored: Any, defaultValue: Any?): Boolean = when {
        defaultValue == null -> false
        stored::class != defaultValue::class -> false
        else -> stored == defaultValue
    }

    /** 展示文案：开关转「开/关」，模式转档位名，数值补单位，其余原样。 */
    private fun display(key: SettingKey, value: Any?): String = when (value) {
        null -> UNSET
        is Boolean -> if (value) "开" else "关"
        is Number -> numberText(key, value)
        else -> value.toString()
    }

    private fun numberText(key: SettingKey, value: Number): String {
        val unit = UNITS[key.key].orEmpty()
        if (key.key == "mode" && value is Int) {
            return MODE_LABELS[value] ?: value.toString()
        }
        return if (value is Float || value is Double) {
            trimDecimal(value.toDouble()) + unit
        } else {
            value.toString() + unit
        }
    }

    /** 浮点去掉无意义的小数位：1.0 显示 1，1.25 显示 1.25。 */
    private fun trimDecimal(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
