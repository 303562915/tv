package com.tv.ball.feature.f009

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF009Binding
import com.tv.ball.util.CustomToast

/**
 * 「快进快退步长策略」页。
 *
 * 三段：选步长（4 个单选项，写 SharedPreferences）→ 智能模式开关 → 模拟播放区。
 * 模拟区的进度、片头片尾标记都走 [F009Engine] 的真判定，和播放页接线用的是同一段逻辑。
 */
class F009Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF009Binding
    private lateinit var engine: F009Engine

    /** 模拟片源总时长：20 分钟，足够看清片头/片尾与步长的关系。 */
    private val durationMs = 20 * 60 * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF009Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F009Engine.get(this)

        binding.f009StepGroup.setOnCheckedChangeListener { _, checkedId -> onStepChanged(checkedId) }
        binding.f009SmartSwitch.setOnClickListener { toggleSmart() }
        binding.f009BtnForward.setOnClickListener { jump(forward = true) }
        binding.f009BtnBackward.setOnClickListener { jump(forward = false) }
        binding.f009BtnSetIntro.setOnClickListener { toggleIntroMark() }
        binding.f009BtnSetCredits.setOnClickListener { toggleCreditsMark() }
        binding.f009BtnClearMarks.setOnClickListener { clearMarks() }
        binding.f009ResetPosition.setOnClickListener { resetPosition() }
        binding.f009NudgeBack.setOnClickListener { nudge(-NUDGE_MS) }
        binding.f009NudgeForward.setOnClickListener { nudge(NUDGE_MS) }
        binding.f009Seekbar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // 拖动是用户主动定位，直接写进引擎，后续快进快退都从这个位置算
                engine.simulatedPositionMs = durationMs * progress / SEEK_MAX
                renderAll()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        renderAll()
        // 电视没有触摸屏：进页面焦点落在第一个步长选项上，遥控器立刻能改设置
        binding.f009StepGroup.check(stepButtonId(engine.policy))
        binding.f009Step5.requestFocus()
    }

    // ===== 步长 =====

    private fun onStepChanged(checkedId: Int) {
        val policy = when (checkedId) {
            R.id.f009_step5 -> StepPolicy.FIVE
            R.id.f009_step10 -> StepPolicy.TEN
            R.id.f009_step30 -> StepPolicy.THIRTY
            R.id.f009_step60 -> StepPolicy.SIXTY
            // 取消选中（例如 RadioGroup 被外部 clearCheck）时不动设置
            else -> return
        }
        val changed = engine.policy != policy
        engine.policy = policy
        renderAll()
        if (changed) {
            CustomToast.show(this, getString(R.string.f009_toast_step, policy.seconds))
        }
    }

    private fun stepButtonId(policy: StepPolicy): Int = when (policy) {
        StepPolicy.FIVE -> R.id.f009_step5
        StepPolicy.TEN -> R.id.f009_step10
        StepPolicy.THIRTY -> R.id.f009_step30
        StepPolicy.SIXTY -> R.id.f009_step60
    }

    // ===== 智能模式 =====

    private fun toggleSmart() {
        val smart = !engine.smartEnabled
        engine.smartEnabled = smart
        renderAll()
        CustomToast.show(
            this,
            getString(if (smart) R.string.f009_toast_smart_on else R.string.f009_toast_smart_off)
        )
    }

    // ===== 快进 / 快退 =====

    private fun jump(forward: Boolean) {
        val pos = engine.simulatedPositionMs
        val result = if (forward) engine.forwardFromSettings(pos, durationMs)
        else engine.backwardFromSettings(pos, durationMs)

        engine.simulatedPositionMs = result.positionMs
        renderAll()
        binding.f009Result.text = getString(
            R.string.f009_result_line,
            format(result.positionMs),
            format(pos),
            kindText(result.kind)
        )
        binding.f009BoundaryTip.text = boundaryText(pos, result)
    }

    /** 没有位移、或被夹到端点时给一句边界提示，正常跳转清空。 */
    private fun boundaryText(from: Long, result: F009Jump): String = when (result.kind) {
        F009JumpKind.EDGE_END -> getString(R.string.f009_boundary_end)
        F009JumpKind.EDGE_START -> getString(R.string.f009_boundary_start)
        F009JumpKind.CLAMPED_END ->
            getString(R.string.f009_boundary_clamp_end, format(durationMs - from), engine.policy.seconds)

        F009JumpKind.CLAMPED_START ->
            getString(R.string.f009_boundary_clamp_start, format(from), engine.policy.seconds)

        else -> ""
    }

    private fun kindText(kind: F009JumpKind): String = when (kind) {
        F009JumpKind.PLAIN -> getString(R.string.f009_kind_plain, engine.policy.seconds)
        F009JumpKind.SKIP_INTRO -> getString(R.string.f009_kind_intro)
        F009JumpKind.SKIP_CREDITS -> getString(R.string.f009_kind_credits)
        F009JumpKind.CLAMPED_END -> getString(R.string.f009_kind_clamped_end)
        F009JumpKind.CLAMPED_START -> getString(R.string.f009_kind_clamped_start)
        F009JumpKind.EDGE_END -> getString(R.string.f009_kind_edge_end)
        F009JumpKind.EDGE_START -> getString(R.string.f009_kind_edge_start)
    }

    // ===== 片头片尾标记 =====

    private fun toggleIntroMark() {
        val pos = engine.simulatedPositionMs
        val credits = engine.creditsStartMs
        if (engine.hasIntroMark() && engine.introEndMs == pos) {
            engine.introEndMs = F009Engine.NO_MARK
            CustomToast.show(this, getString(R.string.f009_toast_intro_cleared))
        } else {
            // 片头必须落在片尾之前，否则两个标记会互相打架
            if (credits != F009Engine.NO_MARK && pos >= credits) {
                CustomToast.show(this, getString(R.string.f009_toast_mark_conflict))
                return
            }
            engine.introEndMs = pos
            CustomToast.show(this, getString(R.string.f009_toast_intro_set, format(pos)))
        }
        renderAll()
    }

    private fun toggleCreditsMark() {
        val pos = engine.simulatedPositionMs
        val intro = engine.introEndMs
        if (engine.hasCreditsMark() && engine.creditsStartMs == pos) {
            engine.creditsStartMs = F009Engine.NO_MARK
            CustomToast.show(this, getString(R.string.f009_toast_credits_cleared))
        } else {
            if (intro != F009Engine.NO_MARK && pos <= intro) {
                CustomToast.show(this, getString(R.string.f009_toast_mark_conflict))
                return
            }
            engine.creditsStartMs = pos
            CustomToast.show(this, getString(R.string.f009_toast_credits_set, format(pos)))
        }
        renderAll()
    }

    private fun clearMarks() {
        engine.introEndMs = F009Engine.NO_MARK
        engine.creditsStartMs = F009Engine.NO_MARK
        renderAll()
        CustomToast.show(this, getString(R.string.f009_toast_marks_cleared))
    }

    private fun resetPosition() {
        engine.simulatedPositionMs = 0L
        renderAll()
    }

    /** 用一个和步长解耦的固定量挪位置，专门用来把播放头送到要标记的地方。 */
    private fun nudge(deltaMs: Long) {
        val target = engine.clamp(engine.simulatedPositionMs + deltaMs, durationMs)
        engine.simulatedPositionMs = target
        renderAll()
    }

    // ===== 渲染 =====

    private fun renderAll() {
        val pos = engine.clamp(engine.simulatedPositionMs, durationMs)

        binding.f009Seekbar.progress = (pos * SEEK_MAX / durationMs).toInt()
        binding.f009PositionValue.text = getString(
            R.string.f009_position_value,
            format(pos),
            format(durationMs)
        )

        binding.f009StepSummary.text = getString(R.string.f009_step_summary, engine.policy.seconds)
        binding.f009SmartState.setText(
            if (engine.smartEnabled) R.string.f009_smart_on_summary
            else R.string.f009_smart_off_summary
        )
        binding.f009SmartState.setTextColor(
            ContextCompat.getColor(
                this,
                if (engine.smartEnabled) R.color.theme_primary else R.color.theme_text_secondary
            )
        )

        renderMark(binding.f009IntroValue, engine.introEndMs, R.string.f009_mark_none)
        renderMark(binding.f009CreditsValue, engine.creditsStartMs, R.string.f009_mark_none)

        // 智能模式关着时片头片尾标记不参与判定，明确告诉用户
        binding.f009MarksHint.text = getString(
            if (engine.smartEnabled) R.string.f009_marks_hint_on else R.string.f009_marks_hint_off
        )
        binding.f009MarksHint.visibility =
            if (engine.hasIntroMark() || engine.hasCreditsMark() || engine.smartEnabled) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun renderMark(view: android.widget.TextView, markMs: Long, noneRes: Int) {
        view.text = if (markMs == F009Engine.NO_MARK) getString(noneRes) else format(markMs)
    }

    /** 毫秒转 `mm:ss`（超过一小时给 `h:mm:ss`），与播放页进度条口径一致。 */
    private fun format(ms: Long): String {
        val totalSeconds = (if (ms < 0L) 0L else ms) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private companion object {
        /** SeekBar 用 0..1000 的整数刻度，映射回毫秒。 */
        const val SEEK_MAX = 1000

        /** 位置微调一次挪多久（与步长无关，定位标记时更好按）。 */
        const val NUDGE_MS = 30 * 1000L
    }
}