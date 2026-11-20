package com.tv.ball.feature.f003

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF003Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils

/**
 * 精确定位跳转页。
 *
 * 页面里没有真实播放器：位置由本页的 [positionMs] 表示，跳转就是把目标值收进片长后写回去，
 * 「输入 -> 解析 -> 跳转 -> 位置显示更新」整条链路在这里就能直接验证。
 * 接真实播放器时把 [applyJump] 换成 `player.seekTo(target)`，再用播放器的进度回调写回 [positionMs] 即可。
 *
 * TV 无触摸屏：输入框除了遥控器数字键/软键盘，还配了一排可聚焦的屏幕数字键（[binding.keypad]），
 * 保证手里只有 D-Pad 时也能敲出 `hh:mm:ss`。
 */
class F003Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF003Binding

    /** 当前播放位置（毫秒）。 */
    private var positionMs: Long = 0L

    /** 片长（毫秒），默认 45 分钟。 */
    private var durationMs: Long = F003Engine.DEFAULT_DURATION_MS

    companion object {
        /** 屏幕数字键里「删除」键的 tag，其余键的 tag 就是要插入的字符。 */
        private const val KEY_DELETE = "del"

        /** 进度条按千分比走，避免 int 精度不够导致长片显示不出差别。 */
        private const val PROGRESS_MAX = 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF003Binding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTimecodeInput()
        setupKeypad()
        setupQuickActions()
        setupDuration()

        renderAll()

        // 遥控器进来焦点不能悬空，直接落在输入框上
        binding.etTimecode.requestFocus()
    }

    // ------------------------------------------------------------------ 控件装配

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupTimecodeInput() {
        binding.btnJump.setOnClickListener { submitTimecode() }

        // 软键盘/遥控器确认键都能直接触发跳转，不必再挪焦点到按钮
        binding.etTimecode.setOnEditorActionListener { _, actionId, event ->
            val confirmed = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (confirmed) {
                submitTimecode()
                true
            } else {
                false
            }
        }

        // 一改输入就把上次的报错收掉，避免旧提示误导
        binding.etTimecode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = hideError()
        })
    }

    private fun setupKeypad() {
        collectKeypadKeys(binding.keypad).forEach { key ->
            key.setOnClickListener { onKeypadKey(key.tag?.toString().orEmpty()) }
        }
    }

    /** 数字键分了三行，按钮在嵌套 LinearLayout 里，递归收集一遍即可。 */
    private fun collectKeypadKeys(group: ViewGroup): List<TextView> {
        val keys = mutableListOf<TextView>()
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is ViewGroup -> keys += collectKeypadKeys(child)
                is TextView -> keys += child
                else -> Unit
            }
        }
        return keys
    }

    private fun onKeypadKey(tag: String) {
        val editable = binding.etTimecode.text ?: return
        if (tag == KEY_DELETE) {
            val cursor = binding.etTimecode.selectionEnd.coerceAtLeast(0)
            if (cursor > 0) {
                editable.delete(cursor - 1, cursor)
            }
        } else if (tag.isNotEmpty()) {
            val start = binding.etTimecode.selectionStart.coerceAtLeast(0)
            val end = binding.etTimecode.selectionEnd.coerceAtLeast(0)
            // 有选区就替换掉，和系统键盘行为一致
            editable.replace(minOf(start, end), maxOf(start, end), tag)
        }
        // 光标落到末尾，遥控器连续敲数字才顺手
        binding.etTimecode.setSelection(editable.length)
        hideError()
    }

    private fun setupQuickActions() {
        binding.btnPlus30.setOnClickListener {
            val target = F003Engine.shift(positionMs, F003Engine.SHORT_STEP_MS, durationMs)
            applyJump(target, getString(R.string.f003_toast_shifted, F003Engine.format(target)))
        }

        binding.btnPlus5Min.setOnClickListener {
            val target = F003Engine.shift(positionMs, F003Engine.LONG_STEP_MS, durationMs)
            applyJump(target, getString(R.string.f003_toast_shifted, F003Engine.format(target)))
        }

        binding.btnNearEnd.setOnClickListener {
            val target = F003Engine.tailTarget(durationMs)
            applyJump(target, getString(R.string.f003_toast_shifted, F003Engine.format(target)))
        }
    }

    private fun setupDuration() {
        binding.etDuration.setText(F003Engine.format(durationMs))

        binding.btnApplyDuration.setOnClickListener { submitDuration() }
        binding.btnDurationMinus5.setOnClickListener { changeDuration(-F003Engine.LONG_STEP_MS) }
        binding.btnDurationPlus5.setOnClickListener { changeDuration(F003Engine.LONG_STEP_MS) }
        binding.btnDurationReset.setOnClickListener {
            applyDuration(F003Engine.DEFAULT_DURATION_MS)
            binding.etDuration.setText(F003Engine.format(durationMs))
            CustomToast.show(
                this,
                getString(R.string.f003_toast_duration_set, F003Engine.format(durationMs))
            )
        }
    }

    // ------------------------------------------------------------------ 跳转

    /** 解析输入框并跳转；解析失败或超出片长都只提示，位置不动。 */
    private fun submitTimecode() {
        val parsed = F003Engine.parseTimecode(binding.etTimecode.text?.toString().orEmpty())
        val failure = parsed.exceptionOrNull()
        if (failure != null) {
            showError(describeFail((failure as? F003Engine.TimecodeException)?.fail))
            return
        }

        val target = parsed.getOrDefault(0L)
        if (target > durationMs) {
            showError(getString(R.string.f003_err_over_duration, F003Engine.format(durationMs)))
            return
        }

        applyJump(target, getString(R.string.f003_toast_jumped, F003Engine.format(target)))
    }

    /**
     * 落位 + 刷新显示。所有跳转（时间码、快捷按钮）都走这里，
     * 保证「跳转后当前位置同步更新」只有一条路径。
     */
    private fun applyJump(targetMs: Long, message: String) {
        positionMs = F003Engine.clampToDuration(targetMs, durationMs)
        hideError()
        renderAll()
        CustomToast.show(this, message)
    }

    private fun describeFail(fail: F003Engine.Fail?): String = getString(
        when (fail) {
            F003Engine.Fail.EMPTY -> R.string.f003_err_empty
            F003Engine.Fail.NEGATIVE -> R.string.f003_err_negative
            F003Engine.Fail.ILLEGAL_CHAR -> R.string.f003_err_illegal_char
            F003Engine.Fail.OUT_OF_RANGE -> R.string.f003_err_out_of_range
            F003Engine.Fail.BAD_FORMAT, null -> R.string.f003_err_bad_format
        }
    )

    // ------------------------------------------------------------------ 片长

    private fun submitDuration() {
        val parsed = F003Engine.parseTimecode(binding.etDuration.text?.toString().orEmpty())
        if (parsed.isFailure) {
            showError(getString(R.string.f003_err_duration_invalid, F003Engine.format(durationMs)))
            return
        }

        val value = parsed.getOrDefault(0L)
        when {
            value < F003Engine.MIN_DURATION_MS ->
                showError(getString(R.string.f003_err_duration_too_short))

            value > F003Engine.MAX_DURATION_MS ->
                showError(getString(R.string.f003_err_duration_too_long))

            else -> {
                applyDuration(value)
                binding.etDuration.setText(F003Engine.format(durationMs))
                CustomToast.show(
                    this,
                    getString(R.string.f003_toast_duration_set, F003Engine.format(durationMs))
                )
            }
        }
    }

    private fun changeDuration(deltaMs: Long) {
        applyDuration((durationMs + deltaMs).coerceIn(F003Engine.MIN_DURATION_MS, F003Engine.MAX_DURATION_MS))
        binding.etDuration.setText(F003Engine.format(durationMs))
        CustomToast.show(
            this,
            getString(R.string.f003_toast_duration_set, F003Engine.format(durationMs))
        )
    }

    /** 片长变短时当前位置要跟着收到片尾，否则「位置 > 总长」会让进度显示错乱。 */
    private fun applyDuration(ms: Long) {
        durationMs = ms.coerceIn(F003Engine.MIN_DURATION_MS, F003Engine.MAX_DURATION_MS)
        positionMs = F003Engine.clampToDuration(positionMs, durationMs)
        hideError()
        renderAll()
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderAll() {
        binding.tvPosition.text = getString(
            R.string.f003_position_value,
            F003Engine.format(positionMs),
            F003Engine.format(durationMs)
        )
        binding.tvRemaining.text = getString(
            R.string.f003_remaining_value,
            F003Engine.format((durationMs - positionMs).coerceAtLeast(0L))
        )
        binding.pbPosition.progress =
            ((positionMs * PROGRESS_MAX) / durationMs.coerceAtLeast(1L)).toInt()
        binding.tvDuration.text =
            getString(R.string.f003_duration_value, F003Engine.format(durationMs))
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        // 遥控器场景里页面可能已经滚到别处，Toast 兜一层，保证提示一定看得见
        CustomToast.show(this, message)
    }

    private fun hideError() {
        if (binding.tvError.visibility != View.VISIBLE) return
        binding.tvError.text = ""
        binding.tvError.visibility = View.GONE
    }
}
