package com.tv.ball.feature.f088

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF088Binding
import com.tv.ball.util.CustomToast

/**
 * 「遥控器按键自定义」页。
 *
 * 三块：学习模式开关（+ 最近捕获键回显）、映射表（动作 → 键名，可解绑、可恢复默认）、说明。
 *
 * 交互的核心是**两级状态**：
 * 1. 先在映射表里挑一个动作（选中行高亮底 + 主题色文字）；
 * 2. 打开学习模式后，**在页面上按遥控器的键**，这个键就被绑定到选中动作上。
 *
 * 为什么要「学习模式」而不是"点一下然后等按键"：电视遥控器没有鼠标，页面上的按键
 * 在 Activity 之前就已被焦点系统吃掉（方向键移焦点、确认键点按钮）。学习模式必须显式开关，
 * 打开时 [dispatchKeyEvent] 只放行「返回」一条通路，其余一律吞掉当样本收下——
 * 否则用户想绑「下键」时，页面只会把焦点往下移一格。
 *
 * 键名用 [F088Engine.keyName]（`KEYCODE_` 前缀已去掉），旁边并列原始键值：
 * 用户去论坛问"我这台盒子下一集按不出来"时，报键值比报名字有用。
 */
class F088Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF088Binding

    /** 当前在映射表里选中的动作；进来默认选第一个，用户上下键换行。 */
    private var selected: Action = Action.PLAY_PAUSE

    /** 学习模式是否开着。开着时几乎所有按键都被当样本吞掉。 */
    private var learning: Boolean = false

    /** 最近一次捕获到的键的展示文案（含键名与键值）；空串表示这一轮还没按过。 */
    private var capturedKey: String = ""

    /** 每个动作行的视图，渲染时整体重画，不缓存任何一份映射副本。 */
    private val holders = mutableMapOf<Action, RowHolder>()

    private class RowHolder(
        val row: LinearLayout,
        val label: TextView,
        val keyText: TextView,
        val codeText: TextView,
        val unbind: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF088Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f088LearnToggle.setOnClickListener { setLearning(!learning) }
        binding.f088ResetButton.setOnClickListener { resetToDefault() }

        bindRow(
            Action.PLAY_PAUSE,
            binding.f088RowPlayPause, binding.f088LabelPlayPause,
            binding.f088KeyPlayPause, binding.f088CodePlayPause, binding.f088UnbindPlayPause
        )
        bindRow(
            Action.NEXT,
            binding.f088RowNext, binding.f088LabelNext,
            binding.f088KeyNext, binding.f088CodeNext, binding.f088UnbindNext
        )
        bindRow(
            Action.PREV,
            binding.f088RowPrev, binding.f088LabelPrev,
            binding.f088KeyPrev, binding.f088CodePrev, binding.f088UnbindPrev
        )
        bindRow(
            Action.MENU,
            binding.f088RowMenu, binding.f088LabelMenu,
            binding.f088KeyMenu, binding.f088CodeMenu, binding.f088UnbindMenu
        )
        bindRow(
            Action.SEEK_FORWARD,
            binding.f088RowSeekForward, binding.f088LabelSeekForward,
            binding.f088KeySeekForward, binding.f088CodeSeekForward, binding.f088UnbindSeekForward
        )
        bindRow(
            Action.SEEK_BACKWARD,
            binding.f088RowSeekBackward, binding.f088LabelSeekBackward,
            binding.f088KeySeekBackward, binding.f088CodeSeekBackward, binding.f088UnbindSeekBackward
        )

        render()

        binding.f088LearnToggle.requestFocus()
    }

    // ===== 按键捕获 =====

    /**
     * 所有按键都先经过这里。
     *
     * 两段逻辑：
     * - 学习模式：只放行「返回」（用来退出学习模式，不然用户在电视上会被困在这一屏），
     *   其余 ACTION_DOWN 一律当样本交给 [learn] 并 return true，ACTION_UP 一并吞掉，
     *   避免同一个键的上下两个事件把同一次学习执行两遍。
     * - 非学习模式：不干预，交给焦点系统走正常导航（方向键换行、确认键点按钮）。
     *
     * 长按不重复学习：只认 `repeatCount == 0` 的那一次，否则按住不放会一路写盘。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!learning) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 返回是唯一出口：先退学习模式，再按一次才真的返回上一页
            if (event.action == KeyEvent.ACTION_DOWN) setLearning(false)
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) learn(event.keyCode)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) return true

        return super.dispatchKeyEvent(event)
    }

    /**
     * 收下一个样本键：绑定到当前选中动作，落盘，报告是否挤掉了别的动作。
     *
     * 学完**立刻退出学习模式**：用户按一次键就是想绑这一个，留在学习模式里下一次误触
     * 就会把刚绑好的映射改掉。要再绑下一个动作，先选动作、再开学习模式。
     */
    private fun learn(keyCode: Int) {
        val displaced = F088Engine.bind(this, selected, keyCode)
        val actionLabel = getString(selected.labelRes)
        val keyLabel = F088Engine.keyName(keyCode)

        capturedKey = getString(R.string.f088_key_captured, keyLabel, keyCode)
        learning = false
        binding.f088LearnToggle.requestFocus()
        render()

        val message = if (displaced != null) {
            getString(
                R.string.f088_toast_bound_with_conflict,
                actionLabel,
                keyLabel,
                keyCode,
                getString(displaced.labelRes)
            )
        } else {
            getString(R.string.f088_toast_bound, actionLabel, keyLabel, keyCode)
        }
        CustomToast.show(this, message)
    }

    // ===== 映射表 =====

    /**
     * 一行 = 一个动作。行本身可聚焦、可点击：点一下就是选中它（之后学习模式绑的就是它），
     * 行尾的「解绑」是另一个可聚焦控件，用文字而不是图标——电视上远看，两个汉字比 16dp 的小叉清楚。
     */
    private fun bindRow(
        action: Action,
        row: LinearLayout,
        label: TextView,
        keyText: TextView,
        codeText: TextView,
        unbind: TextView
    ) {
        holders[action] = RowHolder(row, label, keyText, codeText, unbind)
        label.text = getString(action.labelRes)
        row.setOnClickListener {
            selected = action
            render()
        }
        unbind.setOnClickListener { unbindAction(action) }
    }

    /**
     * 整表重画。
     *
     * 页面不缓存映射：每次操作后都从 [F088Engine.load] 重新取一遍，
     * 保证"表里显示的键"就是"真正落盘的键"，不会出现解绑后行里还写着旧键名。
     */
    private fun render() {
        val map = F088Engine.load(this)
        val selectedLabel = getString(selected.labelRes)

        binding.f088LearnToggleLabel.text =
            getString(if (learning) R.string.f088_learn_on else R.string.f088_learn_off)
        binding.f088LearnToggleLabel.setTextColor(
            color(if (learning) R.color.theme_primary else R.color.theme_text_primary)
        )
        binding.f088LearnToggle.background = ContextCompat.getDrawable(
            this,
            if (learning) R.drawable.bg_mode_card_selected else R.drawable.bg_card_surface
        )

        binding.f088LearnHint.text = if (learning) {
            getString(R.string.f088_learn_hint_on, selectedLabel)
        } else {
            getString(R.string.f088_learn_hint_off)
        }

        binding.f088CapturedKey.text =
            capturedKey.ifEmpty { getString(R.string.f088_captured_none) }
        binding.f088CapturedKey.setTextColor(
            color(if (capturedKey.isEmpty()) R.color.theme_text_secondary else R.color.theme_primary)
        )

        binding.f088Status.text = when {
            learning && capturedKey.isNotEmpty() -> getString(R.string.f088_status_last_key, capturedKey)
            learning -> getString(R.string.f088_status_learning, selectedLabel)
            else -> getString(
                R.string.f088_status_bound,
                F088Engine.assignedCount(map),
                Action.values().size
            )
        }

        Action.values().forEach { action ->
            val holder = holders[action] ?: return@forEach
            val keyCode = map.keyOf(action)
            val active = action == selected

            holder.row.background = ContextCompat.getDrawable(
                this,
                if (active) R.drawable.bg_mode_card_selected else R.drawable.bg_card_surface
            )
            holder.label.setTextColor(
                color(if (active) R.color.theme_primary else R.color.theme_text_primary)
            )

            if (keyCode == null || keyCode <= 0) {
                holder.keyText.text = getString(R.string.f088_key_unbound)
                holder.keyText.setTextColor(color(R.color.theme_text_secondary))
                holder.codeText.text = getString(R.string.f088_key_unbound_code)
                holder.codeText.setTextColor(color(R.color.theme_text_secondary))
                holder.unbind.visibility = View.INVISIBLE
            } else {
                holder.keyText.text = F088Engine.keyName(keyCode)
                holder.keyText.setTextColor(color(R.color.theme_text_primary))
                holder.codeText.text = getString(R.string.f088_key_code_value, keyCode)
                holder.codeText.setTextColor(color(R.color.theme_text_secondary))
                holder.unbind.visibility = View.VISIBLE
            }
        }

        binding.f088Summary.text = if (F088Engine.isDefault(map)) {
            getString(R.string.f088_summary_default)
        } else {
            getString(R.string.f088_summary_custom)
        }
    }

    /** 解绑一个动作（动作固定，键由用户认领；解绑后这个动作不再响应任何键）。 */
    private fun unbindAction(action: Action) {
        val old = F088Engine.unbind(this, action)
        render()
        CustomToast.show(
            this,
            if (old == null) {
                getString(R.string.f088_toast_unbind_none, getString(action.labelRes))
            } else {
                getString(
                    R.string.f088_toast_unbind,
                    getString(action.labelRes),
                    F088Engine.keyName(old),
                    old
                )
            }
        )
    }

    /** 恢复出厂映射：整表覆盖，页面上马上能看到标准媒体键又回来了。 */
    private fun resetToDefault() {
        F088Engine.reset(this)
        render()
        CustomToast.show(this, getString(R.string.f088_toast_reset))
    }

    // ===== 学习模式 =====

    /**
     * 开关学习模式。
     *
     * 关掉时清空上一轮的键回显，避免"上次按的键"被误当成刚按的那一次。
     * 焦点始终收回到开关行：学习模式下按键都被吞掉、焦点在哪都不影响捕获，
     * 但退出后遥控器能立刻从这一行继续往下走。
     */
    private fun setLearning(on: Boolean) {
        learning = on
        if (!on) capturedKey = ""
        binding.f088LearnToggle.requestFocus()
        render()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
}