package com.tv.ball.feature.f080

import android.content.Context

/**
 * 一条待落盘的播放记录。
 *
 * 历史与续播进度在真实实现里是两张表，这里统一成一个轻量对象：
 * 演示页只需要"标题 + 看到第几秒"，判断要不要写由 [PrivacyMode] 决定。
 *
 * @param title           标题（可能很长，打码时只保留首字）
 * @param progressSeconds 看到第几秒
 */
data class PlayRecord(
    val title: String,
    val progressSeconds: Int
) {
    /**
     * 这一条记录落盘后的样子：`标题 · 12:30`。
     *
     * 注意 Kotlin 里的格式化串：`%1$s` 必须写成 `%1\$s`，否则 `$s` 会被当成模板占位符。
     */
    fun line(): String = "%1\$s · %2\$s".format(title, F080Engine.progressText(progressSeconds))
}

/**
 * 隐私模式的完整状态。
 *
 * 只有一个总开关 [enabled]，另外三个是它的从属项：
 *
 * - [hideHistory] / [hideProgress]：总开关打开后要不要拦下这一类写入。
 *   默认都拦，"只想不留进度、历史照旧"的人把 [hideProgress] 之外的关掉就行。
 * - [maskTitles]：可选的"标题打码"，只保留首字、其余换成 `•`；
 *   只在总开关打开时生效，关掉总开关标题就恢复原样。
 *
 * 总开关关闭时 [shouldRecordHistory] / [shouldRecordProgress] 一律返回 `true`，
 * 也就是"关闭后恢复记录"这条验收要求——由这个返回值直接保证，
 * 调用方不需要自己再判一遍总开关。
 */
data class PrivacyMode(
    val enabled: Boolean = false,
    val hideHistory: Boolean = true,
    val hideProgress: Boolean = true,
    val maskTitles: Boolean = false
) {

    /** 打码用的替换字符。 */
    companion object {
        const val MASK_CHAR: Char = '•'
    }

    /** 观看历史还要不要写。 */
    fun shouldRecordHistory(): Boolean = !(enabled && hideHistory)

    /** 续播进度还要不要写。 */
    fun shouldRecordProgress(): Boolean = !(enabled && hideProgress)

    /** 当前这一条会不会被拦下（两类都不写才算拦下）。 */
    fun blocksEverything(): Boolean = !shouldRecordHistory() && !shouldRecordProgress()

    /**
     * 过滤一批记录：被拦下就返回空列表，没被拦下就原样返回（需要时把标题打码）。
     *
     * 返回空列表而不是 `null`：调用方直接 `forEach` 就行，不用多一层判空；
     * 空列表在界面上表现为"暂无记录"，正是隐私模式想要的效果。
     */
    fun stripRecords(items: List<PlayRecord>): List<PlayRecord> {
        if (!shouldRecordHistory()) return emptyList()
        if (!enabled || !maskTitles) return items
        return items.map { it.copy(title = redactTitle(it.title)) }
    }

    /**
     * 标题打码：保留首字，其余每个字符换成一个 `•`。
     *
     * 长度按字符数算，观感上"两个字 → 首字 + 1 个点"，一眼能看出这里有个被藏起来的标题，
     * 又不泄露它是哪一部。未开启打码（或总开关关着）时原样返回，方便调用方无脑套一层。
     */
    fun redactTitle(title: String): String {
        if (!enabled || !maskTitles) return title
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return title
        val builder = StringBuilder(trimmed.length)
        builder.append(trimmed[0])
        for (index in 1 until trimmed.length) {
            builder.append(MASK_CHAR)
        }
        return builder.toString()
    }

    /** 一行状态摘要，给本页状态行和首页的"隐私模式已开启"提示共用。 */
    fun summary(): String {
        val builder = StringBuilder(if (enabled) "隐私模式：已开启" else "隐私模式：已关闭")
        builder.append(" · 观看历史").append(if (shouldRecordHistory()) "照常写入" else "不写入")
        builder.append(" · 续播进度").append(if (shouldRecordProgress()) "照常写入" else "不写入")
        builder.append(" · 标题").append(if (enabled && maskTitles) "打码" else "原样显示")
        return builder.toString()
    }

    /** 改总开关，其余从属项原样保留。 */
    fun withEnabled(value: Boolean): PrivacyMode = copy(enabled = value)

    /** 改历史开关。 */
    fun withHideHistory(value: Boolean): PrivacyMode = copy(hideHistory = value)

    /** 改进度开关。 */
    fun withHideProgress(value: Boolean): PrivacyMode = copy(hideProgress = value)

    /** 改打码开关。 */
    fun withMaskTitles(value: Boolean): PrivacyMode = copy(maskTitles = value)
}

/**
 * F080「隐私模式」的能力层。
 *
 * 只做两件事：
 *
 * 1. **持久化**：整份 [PrivacyMode] 存进 `f080_privacy` 这份 SharedPreferences，
 *    读写都走 [load] / [save]，页面不需要认识 `SharedPreferences` 的 API。
 * 2. **演示**：[simulate] 用同一个 [PrivacyMode] 算出"这条记录现在会被写成什么样"，
 *    页面把它和"若开启会写入"并排显示，开关拨一下就能看到差别，不必真去翻历史列表。
 *
 * 判断逻辑全部在 [PrivacyMode] 里，[F080Engine] 自己不含任何 if：
 * 这样接真实写入点时，只要把 [PrivacyMode] 传过去问一句 [PrivacyMode.shouldRecordHistory]，
 * 行为与这个演示页永远一致。
 */
object F080Engine {

    /** prefs 文件名。 */
    const val PREFS_NAME: String = "f080_privacy"

    /** 总开关。 */
    const val KEY_ENABLED: String = "enabled"

    /** 不写观看历史。 */
    const val KEY_HIDE_HISTORY: String = "hide_history"

    /** 不写续播进度。 */
    const val KEY_HIDE_PROGRESS: String = "hide_progress"

    /** 标题打码。 */
    const val KEY_MASK_TITLES: String = "mask_titles"

    /** 演示用的标题，按一下「播放一条」换下一个。 */
    val SAMPLE_TITLES: List<String> = listOf(
        "蓝色星球 II 第 1 集",
        "街角美食 第 12 集",
        "夜行者 特别篇",
        "地球脉动 第 3 集"
    )

    // region 持久化

    /** 读当前状态；没存过就是"关闭 + 两个子开关待命 + 不打码"。 */
    fun load(context: Context): PrivacyMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PrivacyMode(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hideHistory = prefs.getBoolean(KEY_HIDE_HISTORY, true),
            hideProgress = prefs.getBoolean(KEY_HIDE_PROGRESS, true),
            maskTitles = prefs.getBoolean(KEY_MASK_TITLES, false)
        )
    }

    /**
     * 写回状态并返回同一份对象。
     *
     * 用 `apply()`：开关是高频、低价值的写入，等磁盘回执没有意义；
     * 而且这里不返回布尔值——落盘失败也不该让开关弹回去。
     */
    fun save(context: Context, mode: PrivacyMode): PrivacyMode {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, mode.enabled)
            .putBoolean(KEY_HIDE_HISTORY, mode.hideHistory)
            .putBoolean(KEY_HIDE_PROGRESS, mode.hideProgress)
            .putBoolean(KEY_MASK_TITLES, mode.maskTitles)
            .apply()
        return mode
    }

    /** 默认状态（没读过 prefs 时给界面一个初值，避免 `lateinit` 没初始化的时序坑）。 */
    fun defaultMode(): PrivacyMode = PrivacyMode()

    // endregion

    // region 演示

    /** 一次"播放一条"的结果。 */
    data class PlayOutcome(
        /** 这条到底有没有被写进去。 */
        val kept: Boolean,
        /** 被写进去的话长什么样；[kept] 为 false 时是空串。 */
        val line: String
    )

    /**
     * 用某个 [mode] 演算一条记录的结果。
     *
     * 组合规则：
     * - 两类都不写 → [PlayOutcome.kept] 为 false，[PlayOutcome.line] 为空；
     * - 只写进度 → 行里没有标题，只有进度（"不含标题"就是这种情况）；
     * - 都写 → `标题 · 进度`，标题在打码开启时已被换成 `首字 + •••`。
     *
     * 传 `PrivacyMode(enabled = false)` 进来就是"若开启会写入"的那一份基准。
     */
    fun simulate(mode: PrivacyMode, record: PlayRecord): PlayOutcome {
        val parts = ArrayList<String>(2)
        if (mode.shouldRecordHistory()) parts.add(mode.redactTitle(record.title))
        if (mode.shouldRecordProgress()) parts.add(progressText(record.progressSeconds))
        if (parts.isEmpty()) return PlayOutcome(kept = false, line = "")
        return PlayOutcome(kept = true, line = parts.joinToString(" · "))
    }

    /** 第 [index] 条演示记录（标题循环取，进度每次加 7 分钟，看得到变化）。 */
    fun sampleRecord(index: Int): PlayRecord {
        val size = SAMPLE_TITLES.size
        val safeIndex = if (size == 0) 0 else ((index % size) + size) % size
        val title = if (size == 0) "示例影片" else SAMPLE_TITLES[safeIndex]
        val seconds = (12 + safeIndex * 7) * 60 + 30
        return PlayRecord(title, seconds)
    }

    // endregion

    // region 小工具

    /** 秒数 → `分:秒`，例如 `750` → `12:30`。负数按 0 处理。 */
    fun progressText(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "%1\$d:%2\$02d".format(safe / 60, safe % 60)
    }

    // endregion
}