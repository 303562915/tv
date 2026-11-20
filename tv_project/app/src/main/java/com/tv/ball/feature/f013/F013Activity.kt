package com.tv.ball.feature.f013

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF013Binding
import com.tv.ball.util.CustomToast
import kotlin.math.roundToInt

/**
 * 「字幕轨道切换与延迟」页。
 *
 * 三块内容：轨道单选列表（动态生成，数量随片源字幕流变化）、延迟调节（±100ms 步进 + 滑杆）、
 * 一条模拟字幕的可见性演示。
 *
 * 演示区不是动画：播放头每动一格，都把当前位置交给 [F013Engine.isVisibleAt] 判一次，
 * 界面上的「显示中 / 未显示」就是播放页在同样延迟下真会得到的结果，所以调延迟有确定参照。
 *
 * 电视没有触摸屏：轨道行、延迟滑杆、两个步进按钮和播放头都 focusable，
 * 上下键在轨道行与各小节之间走，左右键调档位。
 */
class F013Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF013Binding
    private lateinit var engine: F013Engine

    /** 动态生成的轨道行，切换后只改状态不重建视图。 */
    private val trackRows = mutableListOf<TrackRow>()

    /** 程序化回填控件时置位，避免把回填当成用户操作再存一次盘。 */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF013Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 引擎只认端口：状态落 video_player_prefs，播放页接线后读的是同一份
        engine = F013Engine(F013PrefsStore(applicationContext), buildTracks())

        // 两处滑杆的量程都由引擎常量算出来，改范围只改引擎一处
        binding.f013DelaySeek.max =
            (F013Engine.MAX_DELAY_MS - F013Engine.MIN_DELAY_MS) / F013Engine.STEP_MS
        binding.f013PlayheadSeek.max = progressOf(PLAYHEAD_MAX_MS)

        binding.f013CueBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.radius_md)
            setColor(ContextCompat.getColor(this@F013Activity, R.color.surface_inset))
        }

        binding.f013DelayMinus.setOnClickListener { stepDelay(-F013Engine.STEP_MS) }
        binding.f013DelayPlus.setOnClickListener { stepDelay(F013Engine.STEP_MS) }
        binding.f013DelayReset.setOnClickListener { resetDelay() }

        binding.f013DelaySeek.setOnSeekBarChangeListener(onSeekChanged { progress ->
            engine.setDelay(F013Engine.MIN_DELAY_MS + progress * F013Engine.STEP_MS)
            renderDelay()
            renderCue()
        })
        binding.f013PlayheadSeek.setOnSeekBarChangeListener(onSeekChanged { renderCue() })

        renderTracks()
        renderDelay()
        setProgressSilently(binding.f013PlayheadSeek, progressOf(PLAYHEAD_START_MS))
        renderCue()

        // 进页面焦点落在当前轨道上，遥控器立刻能上下换轨
        val focusedRow = trackRows.firstOrNull { it.id == engine.selectedTrackId }
            ?: trackRows.firstOrNull()
        focusedRow?.row?.requestFocus()
    }

    // ------------------------------------------------------------------ 轨道

    private fun renderTracks() {
        binding.f013TrackList.removeAllViews()
        trackRows.clear()

        val tracks = engine.tracks
        if (tracks.isEmpty()) {
            binding.f013CurrentTrack.text = getString(R.string.f013_track_empty)
            return
        }

        tracks.forEachIndexed { index, track ->
            val holder = buildTrackRow(track, index)
            trackRows += holder
            binding.f013TrackList.addView(holder.row)
        }
        // 最后一行往下走到延迟滑杆，中间不经过不可聚焦的说明文字
        val lastRow = trackRows.lastOrNull()?.row
        if (lastRow != null) {
            lastRow.nextFocusDownId = R.id.f013DelaySeek
        }

        updateTrackHighlight()
    }

    private fun buildTrackRow(track: Track, index: Int): TrackRow {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(8) }
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = track.label
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@F013Activity, R.color.theme_text_primary))
        }

        // 语言码 + 内嵌/外挂标记合成副行，窄屏也不会把主标题挤断
        val metaView = TextView(this).apply {
            text = getString(
                R.string.f013_track_meta,
                track.language,
                getString(
                    if (track.embedded) R.string.f013_track_embedded else R.string.f013_track_external
                )
            )
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@F013Activity, R.color.theme_text_secondary))
        }

        textColumn.addView(nameView)
        textColumn.addView(metaView)
        row.addView(textColumn)

        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check_primary)
            contentDescription = getString(R.string.f013_track_selected)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12) }
        }
        row.addView(check)

        val holder = TrackRow(track.id, row, nameView, check)
        row.setOnClickListener { onTrackClicked(track) }
        row.setOnFocusChangeListener { _, focused -> applyRowState(holder, focused) }
        return holder
    }

    /** 选中态 = 2dp primary 描边 + 右侧对勾 + 标题用主色；焦点态只换底色，与全站一致不铺 primary 底。 */
    private fun applyRowState(row: TrackRow, focused: Boolean) {
        val selected = row.id == engine.selectedTrackId
        row.row.background = rowBackground(selected, focused)
        row.name.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.theme_primary else R.color.theme_text_primary
            )
        )
        row.check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun rowBackground(selected: Boolean, focused: Boolean): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.radius_md)
            setColor(
                ContextCompat.getColor(
                    this@F013Activity,
                    if (focused) R.color.surface_inset else R.color.surface_card
                )
            )
            if (selected) {
                setStroke(
                    (SELECTED_STROKE_DP * resources.displayMetrics.density).roundToInt(),
                    ContextCompat.getColor(this@F013Activity, R.color.theme_primary)
                )
            }
        }

    private fun updateTrackHighlight() {
        trackRows.forEach { applyRowState(it, it.row.isFocused) }
        binding.f013CurrentTrack.text = engine.selectedTrack
            ?.let { getString(R.string.f013_track_current, it.label) }
            ?: getString(R.string.f013_track_empty)
    }

    private fun onTrackClicked(track: Track) {
        val changed = engine.selectTrack(track.id)
        updateTrackHighlight()
        // 演示区写着当前轨道名，切轨后立刻跟着变，这就是「切换后立即生效」的可见证据
        renderCue()
        if (changed) {
            CustomToast.show(this, getString(R.string.f013_track_switched, track.label))
        }
    }

    // ------------------------------------------------------------------ 延迟

    private fun stepDelay(deltaMs: Int) {
        engine.adjustDelay(deltaMs)
        renderDelay()
        renderCue()
    }

    private fun resetDelay() {
        val changed = engine.resetDelay()
        renderDelay()
        renderCue()
        CustomToast.show(
            this,
            getString(if (changed) R.string.f013_delay_reset_done else R.string.f013_delay_reset_already)
        )
    }

    private fun renderDelay() {
        val delay = engine.delayMs
        binding.f013DelayValue.text = delayText(delay)

        // 到界就禁用，不然按下去没反应会让人以为卡了
        applyEnabled(binding.f013DelayMinus, engine.canStepDown)
        applyEnabled(binding.f013DelayPlus, engine.canStepUp)

        setProgressSilently(
            binding.f013DelaySeek,
            (delay - F013Engine.MIN_DELAY_MS) / F013Engine.STEP_MS
        )

        val limit = when {
            !engine.canStepDown -> getString(R.string.f013_delay_limit_low)
            !engine.canStepUp -> getString(R.string.f013_delay_limit_high)
            else -> null
        }
        binding.f013DelayLimit.text = limit.orEmpty()
        binding.f013DelayLimit.visibility = if (limit == null) View.GONE else View.VISIBLE
    }

    private fun applyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** 延迟的带符号文案；0 单独一档，避免显示「+0 ms」。 */
    private fun delayText(delayMs: Int): String = when {
        delayMs > 0 -> getString(R.string.f013_delay_plus, delayMs)
        delayMs < 0 -> getString(R.string.f013_delay_minus, -delayMs)
        else -> getString(R.string.f013_delay_zero)
    }

    // ------------------------------------------------------------------ 演示

    /**
     * 演示区：把播放头位置交给引擎判可见性。
     *
     * 生效区间用 [F013Engine.visibleEnd] 算，和 `isVisibleAt` 内部是同一个口径，
     * 所以界面写的「还剩几秒」和「显示中」的判定不会互相打脸。
     */
    private fun renderCue() {
        val now = playheadMs()
        val start = engine.effectiveTime(DEMO_CUE_START_MS)
        val end = engine.visibleEnd(DEMO_CUE_START_MS, DEMO_CUE_END_MS)
        val visible = engine.isVisibleAt(DEMO_CUE_START_MS, DEMO_CUE_END_MS, now)

        binding.f013CueText.alpha = if (visible) 1f else CUE_HIDDEN_ALPHA
        binding.f013CueState.setText(
            if (visible) R.string.f013_cue_visible else R.string.f013_cue_hidden
        )
        binding.f013CueState.setTextColor(
            ContextCompat.getColor(
                this,
                if (visible) R.color.theme_primary else R.color.theme_text_secondary
            )
        )
        binding.f013CueTrack.text = engine.selectedTrack
            ?.let { getString(R.string.f013_track_current, it.label) }
            ?: getString(R.string.f013_track_empty)

        binding.f013PlayheadValue.text = getString(R.string.f013_playhead_value, formatSeconds(now))
        binding.f013CueWindow.text = getString(
            R.string.f013_cue_window,
            formatSeconds(start),
            formatSeconds(end),
            formatSeconds(DEMO_CUE_START_MS),
            formatSeconds(DEMO_CUE_END_MS)
        )
        binding.f013CueVerdict.text = when {
            now < start -> getString(R.string.f013_cue_verdict_before, formatSeconds(start))
            now >= end -> getString(R.string.f013_cue_verdict_after, formatSeconds(end))
            else -> getString(R.string.f013_cue_verdict_inside, formatSeconds(end - now))
        }
    }

    private fun playheadMs(): Long =
        PLAYHEAD_MIN_MS + binding.f013PlayheadSeek.progress * PLAYHEAD_STEP_MS

    private fun progressOf(ms: Long): Int = ((ms - PLAYHEAD_MIN_MS) / PLAYHEAD_STEP_MS).toInt()

    private fun formatSeconds(ms: Long): String = getString(R.string.f013_seconds, ms / 1000.0)

    // ------------------------------------------------------------------ 工具

    private fun setProgressSilently(seekBar: SeekBar, progress: Int) {
        syncing = true
        seekBar.progress = progress
        syncing = false
    }

    private fun onSeekChanged(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (syncing) return
                onProgress(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    /**
     * 演示用轨道列表。
     *
     * 真实接入时换成播放器给的轨：遍历 `player.currentTracks.groups`，取
     * `type == C.TRACK_TYPE_TEXT` 的分组，用 `group.getTrackFormat(i)` 的 id / label / language
     * 组装 [Track]，`embedded` 按是否来自 `MergingMediaSource` 的内嵌流判定。
     * 页面其余部分只依赖 [F013Engine]，接线不需要动任何 UI 代码。
     */
    private fun buildTracks(): List<Track> = listOf(
        Track("sub_zh_hans", "中文（简体）", "zh-Hans", true),
        Track("sub_zh_hant", "中文（繁体）", "zh-Hant", true),
        Track("sub_en", "English", "en", true),
        Track("sub_ja", "日本語", "ja", true),
        Track("sub_external_zh", "外挂字幕（简体）", "zh-Hans", false)
    )

    private class TrackRow(
        val id: String,
        val row: LinearLayout,
        val name: TextView,
        val check: View
    )

    private companion object {
        /** 演示字幕的原始时间轴：12.0s 出现，15.0s 消失。 */
        const val DEMO_CUE_START_MS = 12_000L
        const val DEMO_CUE_END_MS = 15_000L

        /** 播放头量程 0~22s：±5s 延迟后窗口最多落在 7s~20s，两头都留了能看见状态翻转的余量。 */
        const val PLAYHEAD_MIN_MS = 0L
        const val PLAYHEAD_MAX_MS = 22_000L
        const val PLAYHEAD_STEP_MS = 100L

        /** 进页面时的播放头位置：字幕还没出现，按一下加号就能看出延迟把它推后了。 */
        const val PLAYHEAD_START_MS = 10_000L

        const val DISABLED_ALPHA = 0.4f
        const val CUE_HIDDEN_ALPHA = 0.25f
        const val SELECTED_STROKE_DP = 2
    }
}

/**
 * 状态的 SharedPreferences 实现。
 *
 * 用 applicationContext，不持有 Activity；prefs 文件名和 [com.tv.ball.data.local.LocalDataSource]
 * 一致（video_player_prefs），播放页接线时直接读同一份键即可。
 */
private class F013PrefsStore(context: Context) : F013StateStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): F013State? {
        if (!prefs.contains(KEY_TRACK_ID) && !prefs.contains(KEY_DELAY_MS)) return null
        return F013State(
            prefs.getString(KEY_TRACK_ID, null),
            prefs.getInt(KEY_DELAY_MS, 0)
        )
    }

    override fun save(state: F013State) {
        prefs.edit()
            .putString(KEY_TRACK_ID, state.selectedTrackId)
            .putInt(KEY_DELAY_MS, state.delayMs)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "video_player_prefs"
        const val KEY_TRACK_ID = "f013_selected_track"
        const val KEY_DELAY_MS = "f013_subtitle_delay_ms"
    }
}
