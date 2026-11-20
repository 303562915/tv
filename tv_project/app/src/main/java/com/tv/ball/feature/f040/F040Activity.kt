package com.tv.ball.feature.f040

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF040Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils

/**
 * 循环策略中心页。
 *
 * 页面里没有真实播放器：队列是 [F040Engine.TRACKS] 里的 5 首模拟曲目，
 * 「播放结束」按钮就是播放器的 `onCompletion` 回调——按当前下标的策略算出 [F040Engine.NextAction]，
 * 然后把结果写回界面。整条「切策略 → 放完这一首 → 下一首/停止」在这里就能直接验证。
 *
 * 接真实播放器时：策略读 [F040Engine.loadMode]，`onCompletion` 调 [F040Engine.onTrackEnded]，
 * 拿到 [F040Engine.NextAction.Play] 就 `seekTo(0)` 播下一首，[F040Engine.NextAction.Stop] 停在片尾。
 *
 * 策略与下标都落盘，退出重进还是上次那一档、上次那一首。
 */
class F040Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF040Binding

    /** 当前策略。 */
    private var mode: F040Engine.RepeatMode = F040Engine.RepeatMode.OFF

    /** 当前播放到第几首（0 基）。 */
    private var index: Int = F040Engine.DEFAULT_INDEX

    /** 队列长度：页面固定 5 首模拟曲目。 */
    private val queueSize: Int = F040Engine.QUEUE_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF040Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 上次的策略与位置先读回来，再装配控件，避免先画默认值再被覆盖闪一下
        mode = F040Engine.loadMode(this)
        index = F040Engine.loadIndex(this)

        setupToolbar()
        setupModes()
        setupQueue()
        setupDemo()

        renderAll()

        // 遥控器进来焦点不能悬空，落在当前策略那一档上
        modeButton(mode).requestFocus()
    }

    // ------------------------------------------------------------------ 控件装配

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupModes() {
        MODE_BUTTONS.forEach { (target, button) ->
            button.setOnClickListener { switchMode(target) }
        }
    }

    private fun setupQueue() {
        // 每行带自己的下标 tag，渲染时直接按 tag 取曲目，不必再维护一份列表
        binding.queueContainer.children().forEach { row ->
            row.setOnClickListener { jumpTo(indexOfRow(row), showTip = true) }
        }
    }

    private fun setupDemo() {
        binding.btnTrackEnded.setOnClickListener { simulateTrackEnded() }
        binding.btnResetQueue.setOnClickListener { resetQueue() }
    }

    // ------------------------------------------------------------------ 策略

    /** 三档之间直接点选；点当前那一档不再重复写盘。 */
    private fun switchMode(target: F040Engine.RepeatMode) {
        if (mode == target) {
            CustomToast.show(this, getString(R.string.f040_toast_mode_same, modeLabel(target)))
            return
        }
        mode = target
        F040Engine.saveMode(this, mode)
        renderAll()
        CustomToast.show(this, getString(R.string.f040_toast_mode_switched, modeLabel(target)))
    }

    private fun modeButton(target: F040Engine.RepeatMode): TextView = when (target) {
        F040Engine.RepeatMode.OFF -> binding.btnModeOff
        F040Engine.RepeatMode.ONE -> binding.btnModeOne
        F040Engine.RepeatMode.ALL -> binding.btnModeAll
    }

    private fun modeLabel(target: F040Engine.RepeatMode): String = getString(
        when (target) {
            F040Engine.RepeatMode.OFF -> R.string.f040_mode_off
            F040Engine.RepeatMode.ONE -> R.string.f040_mode_one
            F040Engine.RepeatMode.ALL -> R.string.f040_mode_all
        }
    )

    private fun modeDesc(target: F040Engine.RepeatMode): String = getString(
        when (target) {
            F040Engine.RepeatMode.OFF -> R.string.f040_mode_off_desc
            F040Engine.RepeatMode.ONE -> R.string.f040_mode_one_desc
            F040Engine.RepeatMode.ALL -> R.string.f040_mode_all_desc
        }
    )

    // ------------------------------------------------------------------ 队列

    /** 点某一行直接跳过去听那一首；下标立刻落盘，重进还是这一首。 */
    private fun jumpTo(target: Int, showTip: Boolean = false) {
        val next = F040Engine.clampIndex(target, queueSize)
        if (next == index && showTip) {
            CustomToast.show(this, getString(R.string.f040_toast_already_current, index + 1))
            return
        }
        index = next
        F040Engine.saveIndex(this, index)
        renderAll()
        if (showTip) {
            CustomToast.show(this, getString(R.string.f040_toast_switched_track, index + 1))
        }
    }

    private fun resetQueue() {
        index = F040Engine.DEFAULT_INDEX
        F040Engine.saveIndex(this, index)
        renderAll()
        binding.tvLastAction.text = getString(R.string.f040_last_reset)
        CustomToast.show(this, getString(R.string.f040_toast_reset))
    }

    /** 模拟播放器回调：这一首放完了，看策略怎么走。 */
    private fun simulateTrackEnded() {
        when (val action = F040Engine.onTrackEnded(index, queueSize, mode)) {
            is F040Engine.NextAction.Play -> {
                index = action.index
                F040Engine.saveIndex(this, index)
                renderAll()
                binding.tvLastAction.text = getString(
                    R.string.f040_last_play,
                    modeLabel(mode),
                    index + 1,
                    F040Engine.trackTitle(index)
                )
                CustomToast.show(
                    this,
                    getString(R.string.f040_toast_next_playing, index + 1, F040Engine.trackTitle(index))
                )
            }

            F040Engine.NextAction.Stop -> {
                renderAll()
                binding.tvLastAction.text = getString(R.string.f040_last_stop, modeLabel(mode))
                CustomToast.show(this, getString(R.string.f040_toast_stopped))
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderAll() {
        renderMode()
        renderQueue()
    }

    private fun renderMode() {
        binding.tvCurrentMode.text = getString(R.string.f040_current_value, modeLabel(mode))
        binding.tvModeDesc.text = modeDesc(mode)

        val selectedBg = R.drawable.bg_mode_card_selected
        val normalBg = R.drawable.bg_secondary_button
        val selectedColor = ContextCompat.getColor(this, R.color.theme_primary)
        val normalColor = ContextCompat.getColor(this, R.color.theme_text_primary)

        MODE_BUTTONS.forEach { (target, button) ->
            val selected = mode == target
            button.setBackgroundResource(if (selected) selectedBg else normalBg)
            button.setTextColor(if (selected) selectedColor else normalColor)
        }
    }

    private fun renderQueue() {
        binding.tvNowPlaying.text = getString(
            R.string.f040_now_value,
            index + 1,
            queueSize,
            F040Engine.trackTitle(index)
        )

        val playingColor = ContextCompat.getColor(this, R.color.theme_primary)
        val idleColor = ContextCompat.getColor(this, R.color.theme_text_secondary)

        binding.queueContainer.children().forEach { row ->
            val rowIndex = indexOfRow(row)
            val playing = rowIndex == index
            row.text = getString(
                R.string.f040_queue_row,
                rowIndex + 1,
                F040Engine.trackTitle(rowIndex)
            )
            row.setTextColor(if (playing) playingColor else idleColor)
            row.setBackgroundResource(
                if (playing) R.drawable.bg_current_label else R.drawable.bg_tag_inset
            )
        }

        // 顺序播放时提前说清「这首放完就停」，免得用户以为漏了下一首
        binding.tvQueueHint.text =
            if (mode == F040Engine.RepeatMode.OFF && F040Engine.isLastTrack(index, queueSize)) {
                getString(R.string.f040_hint_last_track)
            } else {
                getString(R.string.f040_hint_queue, queueSize)
            }
    }

    private fun indexOfRow(row: TextView): Int =
        F040Engine.clampIndex(row.tag?.toString()?.toIntOrNull() ?: 0, queueSize)

    /** 布局里的三档按钮一次列好，渲染与点击共用同一份，不会漏掉某一档。 */
    private val MODE_BUTTONS: List<Pair<F040Engine.RepeatMode, TextView>>
        get() = listOf(
            F040Engine.RepeatMode.OFF to binding.btnModeOff,
            F040Engine.RepeatMode.ONE to binding.btnModeOne,
            F040Engine.RepeatMode.ALL to binding.btnModeAll
        )
}

/** 布局里队列行都塞在同一个 LinearLayout，取子 View 时统一转成 TextView。 */
private fun android.view.ViewGroup.children(): List<TextView> =
    (0 until childCount).mapNotNull { getChildAt(it) as? TextView }