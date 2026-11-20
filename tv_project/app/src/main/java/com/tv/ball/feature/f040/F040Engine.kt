package com.tv.ball.feature.f040

import android.content.Context

/**
 * 循环策略中心：三种播放策略的判定与持久化。
 *
 * 判定逻辑纯 Kotlin：不持有播放器、不碰资源，`onTrackEnded` 只回答「这一首放完之后该干什么」。
 * 播放页接线方式：
 * 1. 用户在播放页/本页切策略 → `saveMode(context, mode)`；
 * 2. 每次播放器回调 `onCompletion` → `onTrackEnded(index, size, mode)`；
 * 3. 拿到 [NextAction.Play] 就 `seekTo(0)` 播下一首，拿到 [NextAction.Stop] 就停在片尾等用户操作。
 *
 * 策略与队列位置都落 SharedPreferences，重进本页、切集、进程重建后读回同一份。
 */
object F040Engine {

    /** 三档循环策略。 */
    enum class RepeatMode(val code: String) {
        /** 顺序播放：放完最后一首就停，不回头。 */
        OFF("off"),

        /** 单曲循环：同一首反复放。 */
        ONE("one"),

        /** 列表循环：放完最后一首回到第一首。 */
        ALL("all");

        companion object {
            /** 存档里的值坏了/没写过就退回顺序播放，不抛异常。 */
            fun fromCode(raw: String?): RepeatMode =
                values().firstOrNull { it.code == raw } ?: OFF
        }
    }

    /**
     * 一首结束后的动作。
     *
     * 用密封类而不是返回 null / -1：调用方不会把「停止」误当成「第 -1 首」。
     */
    sealed class NextAction {
        /** 播放 [index] 那一首（0 基）。 */
        data class Play(val index: Int) : NextAction()

        /** 队列结束，停在原地。 */
        object Stop : NextAction()
    }

    /** 界面里的模拟队列长度。 */
    const val QUEUE_SIZE: Int = 5

    /** 队列起点。 */
    const val DEFAULT_INDEX: Int = 0

    /** 模拟队列的曲目名，和页面上的列表一一对应。 */
    val TRACKS: List<String> = listOf(
        "城市清晨",
        "海边公路",
        "旧唱片店",
        "午夜电台",
        "终点之前"
    )

    /**
     * 这一首放完之后做什么。
     *
     * [index] 越界时先收进 `[0, size)`，避免存档里的旧下标把判定带偏；
     * [size] <= 0 视为空队列，一律停止，循环策略对空队列没有意义。
     */
    fun onTrackEnded(index: Int, size: Int, mode: RepeatMode): NextAction {
        if (size <= 0) return NextAction.Stop
        val current = index.coerceIn(0, size - 1)
        return when (mode) {
            RepeatMode.OFF ->
                if (current >= size - 1) NextAction.Stop else NextAction.Play(current + 1)

            RepeatMode.ONE -> NextAction.Play(current)

            RepeatMode.ALL -> NextAction.Play((current + 1) % size)
        }
    }

    /** 按钮轮换顺序：顺序 → 单曲 → 列表 → 顺序。 */
    fun nextMode(mode: RepeatMode): RepeatMode = when (mode) {
        RepeatMode.OFF -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.OFF
    }

    /** 是否是队列最后一首（顺序播放时用它在界面上预告「再放就结束」）。 */
    fun isLastTrack(index: Int, size: Int): Boolean = size > 0 && index >= size - 1

    /** 把下标收进合法范围；空队列返回 0，调用方自己判断 size。 */
    fun clampIndex(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)

    /** 曲目名；下标越界时给一个可读的兜底，不让界面出现空白行。 */
    fun trackTitle(index: Int): String = TRACKS.getOrElse(index) { "第 ${index + 1} 首" }

    /** 读回上次的策略。 */
    fun loadMode(context: Context): RepeatMode = F040PrefsStore(context).readMode()

    /** 策略一变就落盘，下次进页直接恢复。 */
    fun saveMode(context: Context, mode: RepeatMode) = F040PrefsStore(context).writeMode(mode)

    /** 读回队列位置：重进页面时高亮的那一首和上次一致。 */
    fun loadIndex(context: Context): Int =
        clampIndex(F040PrefsStore(context).readIndex(), QUEUE_SIZE)

    /** 队列位置随时落盘，只在真正换曲时写。 */
    fun saveIndex(context: Context, index: Int) =
        F040PrefsStore(context).writeIndex(clampIndex(index, QUEUE_SIZE))
}

/**
 * 循环策略的 SharedPreferences 存储。
 *
 * 用 applicationContext 取实例，不持有 Activity；写用 apply() 异步落盘，不卡主线程。
 * 都存字符串：读回来能区分「没写过」与「写成了默认值」，坏值也能被 [RepeatMode.fromCode] 收掉。
 */
class F040PrefsStore(context: Context) {

    private val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 没写过返回 null 由调用方决定默认值；写过但认不出就当没写过。 */
    fun readMode(): F040Engine.RepeatMode {
        val raw = prefs.getString(KEY_MODE, null)
        return F040Engine.RepeatMode.fromCode(raw)
    }

    fun writeMode(mode: F040Engine.RepeatMode) {
        prefs.edit().putString(KEY_MODE, mode.code).apply()
    }

    fun readIndex(): Int = prefs.getInt(KEY_INDEX, F040Engine.DEFAULT_INDEX)

    fun writeIndex(index: Int) {
        prefs.edit().putInt(KEY_INDEX, index).apply()
    }

    private companion object {
        const val PREFS_NAME = "f040_repeat_center"
        const val KEY_MODE = "repeat_mode"
        const val KEY_INDEX = "last_index"
    }
}