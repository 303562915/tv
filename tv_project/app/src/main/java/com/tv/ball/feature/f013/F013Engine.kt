package com.tv.ball.feature.f013

import kotlin.math.roundToInt

/**
 * 一条字幕轨。
 *
 * @param id 轨道稳定标识（接 ExoPlayer 时直接用 `TrackGroup.id`，跨会话不变才能持久化选中态）
 * @param label 展示名，例如「中文（简体）」
 * @param language BCP-47 语言码，例如 zh-Hans / en
 * @param embedded 是否是容器里内嵌的字幕流（false 表示外挂字幕文件）
 */
data class Track(
    val id: String,
    val label: String,
    val language: String,
    val embedded: Boolean
)

/** 需要落盘的完整状态：选中的轨 + 时间轴延迟。 */
data class F013State(val selectedTrackId: String?, val delayMs: Int)

/**
 * 状态持久化端口。
 *
 * 故意抽成纯 Kotlin 接口：引擎本身不 import 任何 android.*，可以脱离设备单测；
 * Android 侧实现见 `F013Activity.kt` 里的 `F013PrefsStore`（写 `video_player_prefs`，
 * 和播放页读的是同一份 SharedPreferences）。
 */
interface F013StateStore {
    /** 读已保存的状态；从未保存过或读失败时返回 null，由引擎回落到默认值。 */
    fun load(): F013State?

    fun save(state: F013State)
}

/**
 * 字幕轨道与延迟引擎。
 *
 * 两条互不干扰的状态轴：
 * - [selectedTrackId]：当前放哪条字幕轨；
 * - [delayMs]：整条字幕时间轴的平移量，正数让字幕晚出现、负数让它早出现。
 *
 * 切轨不动延迟、调延迟不换轨 —— 用户调好的对口型和语言选择是两件事，
 * 换语言时不希望延迟被重置。两者每次变更都立即写盘，播放页接线后读同一份键。
 *
 * 时间轴口径：字幕的 cue 时间戳保持原样，播放时统一加 [delayMs] 再比较播放位置，
 * 所以延迟是「整体平移」而不是「逐条修改」，切换延迟不需要重建字幕源。
 */
class F013Engine(private val store: F013StateStore, val tracks: List<Track>) {

    /** 当前延迟（毫秒），范围 [MIN_DELAY_MS]~[MAX_DELAY_MS]，始终对齐 [STEP_MS] 档位。 */
    var delayMs: Int = 0
        private set

    /** 当前选中轨的 id；轨道列表为空时是空串。 */
    var selectedTrackId: String = tracks.firstOrNull()?.id.orEmpty()
        private set

    val selectedTrack: Track?
        get() = tracks.firstOrNull { it.id == selectedTrackId }

    init {
        // 存盘值可能来自旧版本或被手动改脏：延迟先夹回范围并对齐档位，
        // 轨道 id 在当前片源的轨道列表里找不到时保留默认选中，避免出现悬空选中态
        store.load()?.let { saved ->
            delayMs = snapDelay(saved.delayMs)
            val savedId = saved.selectedTrackId
            if (savedId != null && tracks.any { it.id == savedId }) {
                selectedTrackId = savedId
            }
        }
    }

    /** 切到指定轨道，返回是否真的换了（换了才写盘）。id 不在列表里时忽略并返回 false。 */
    fun selectTrack(id: String): Boolean {
        if (tracks.none { it.id == id }) return false
        if (id == selectedTrackId) return false
        selectedTrackId = id
        persist()
        return true
    }

    /** 按档位步进延迟（一般传 ±[STEP_MS]），返回夹好之后的当前值。 */
    fun adjustDelay(deltaMs: Int): Int = setDelay(this.delayMs + deltaMs)

    /** 直接设置延迟：夹进范围并对齐最近档位，返回夹好之后的当前值。 */
    fun setDelay(valueMs: Int): Int {
        val next = snapDelay(valueMs)
        if (next != delayMs) {
            delayMs = next
            persist()
        }
        return delayMs
    }

    /** 延迟归零，返回是否真的变了。 */
    fun resetDelay(): Boolean {
        if (delayMs == 0) return false
        delayMs = 0
        persist()
        return true
    }

    /** 还能不能继续往下调；到下限后界面据此禁用减号按钮。 */
    val canStepDown: Boolean get() = delayMs > MIN_DELAY_MS

    /** 还能不能继续往上调；到上限后界面据此禁用加号按钮。 */
    val canStepUp: Boolean get() = delayMs < MAX_DELAY_MS

    /** 原始时间轴上的时刻 → 加上延迟后真正生效的播放时刻。 */
    fun effectiveTime(cueStartMs: Long): Long = cueStartMs + delayMs

    /**
     * 这一条字幕实际消失的时刻。
     *
     * 极短 cue（end 与 start 相差不到 [MIN_DISPLAY_MS]）按最小可读时长兜底：
     * 延迟取整之后不至于连一帧都看不到，判定可见性的口径与界面展示用的是同一个值。
     */
    fun visibleEnd(cueStartMs: Long, cueEndMs: Long): Long =
        maxOf(effectiveTime(cueEndMs), effectiveTime(cueStartMs) + MIN_DISPLAY_MS)

    /** 当前播放位置 [nowMs] 是否落在这条字幕的可见区间里。 */
    fun isVisibleAt(cueStartMs: Long, cueEndMs: Long, nowMs: Long): Boolean {
        val start = effectiveTime(cueStartMs)
        return nowMs >= start && nowMs < visibleEnd(cueStartMs, cueEndMs)
    }

    private fun snapDelay(valueMs: Int): Int {
        val clamped = valueMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        val steps = (clamped.toFloat() / STEP_MS).roundToInt()
        return (steps * STEP_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    private fun persist() {
        store.save(F013State(selectedTrackId, delayMs))
    }

    companion object {
        /** 下限：字幕可以提前 5 秒出现。 */
        const val MIN_DELAY_MS = -5_000

        /** 上限：字幕可以推后 5 秒出现。 */
        const val MAX_DELAY_MS = 5_000

        /** 单档步进，界面上就是 ±100ms 按钮和滑杆的一格。 */
        const val STEP_MS = 100

        /** 一条字幕的最小可见时长，防止延迟取整后出现零长度 cue。 */
        const val MIN_DISPLAY_MS = 200L
    }
}
