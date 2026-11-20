package com.tv.ball.feature.f081

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF081Binding
import com.tv.ball.util.CustomToast

/**
 * 「播放默认值中心」页。
 *
 * 六项集中配置：倍速（下拉）、比例（下拉）、自动连播（开关）、快进步长（下拉）、
 * 长按倍速（下拉）、字幕自动加载（开关）。页面上还有两块实时信息：
 * 「当前生效配置」摘要（面板每改一项就重算一次）和一行状态说明。
 *
 * 数据流（和 [F081Engine] 的约定一致）：
 * 1. 进页面 [F081Engine.load] 读一份默认值，放进 [draft] —— 面板上编辑的永远是这份草稿；
 * 2. 任何一项改动只动 [draft] 并重画摘要，**不碰盘也不碰播放器**：
 *    正在播的那一集不该因为别人在设置页点了两下就突然变速；
 * 3. 点「保存默认值」→ [F081Engine.save] 校验并落盘，返回的合法值再经 [F081Engine.applyTo]
 *    发布成"下一次起播读到的快照"，此时才算"对新播放生效"；
 * 4. 点「恢复默认」→ 回到 [F081Engine.defaults] 的出厂组合，同样落盘 + 发布，
 *    一步完成，不需要用户再点一次保存。
 *
 * 控件回填的坑：Spinner 的 onItemSelected 会在 setSelection 之后异步回调一次，
 * 用"抑制标志"很容易踩到延时回调。这里改成"值相等就忽略"——回填值和 [draft] 一定相等，
 * 所以回填永远不会被当成用户改动；用户真的换了档位，值就必然不同。
 */
class F081Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF081Binding

    /** 面板上正在编辑的草稿；保存前只影响摘要，不影响播放。 */
    private var draft: PlaybackDefaults = PlaybackDefaults()

    /** 倍速下拉当前的档位值（草稿里若是个不在档位表里的值，会补进去再排序，避免下拉显示成第一项）。 */
    private var speedValues: List<Float> = F081Engine.SPEED_OPTIONS

    /** 比例下拉当前的可选值。 */
    private var aspectValues: List<String> = F081Engine.ASPECT_OPTIONS

    /** 快进步长下拉当前的可选值。 */
    private var seekValues: List<Int> = F081Engine.SEEK_STEP_OPTIONS

    /** 长按倍速下拉当前的可选值。 */
    private var longPressValues: List<Float> = F081Engine.LONG_PRESS_SPEED_OPTIONS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF081Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 六个小键的同步读取，不会卡住首帧；读坏了 [F081Engine.load] 内部已经退回到出厂值
        draft = runCatching { F081Engine.load(this) }.getOrDefault(F081Engine.defaults())

        setupListeners()
        binding.f081SaveButton.setOnClickListener { saveDraft() }
        binding.f081ResetButton.setOnClickListener { restoreDefaults() }

        renderControls()
        renderSummary()
        setStatus(getString(R.string.f081_status_loaded), R.color.theme_text_secondary)
    }

    // ===== 控件 =====

    /**
     * 四个下拉 + 两个开关的监听：改的是草稿，改完只重画摘要和状态行。
     *
     * 下拉回调统一走"取到的值和草稿相同就 return"这条短路，
     * 这样程序化回填（renderControls）不会反过来触发一次假的"已修改"。
     */
    private fun setupListeners() {
        binding.f081SpeedSpinner.onItemSelectedListener = onSpinnerPick { position ->
            val value = speedValues.getOrNull(position) ?: return@onSpinnerPick
            if (value == draft.speed) return@onSpinnerPick
            draft = draft.copy(speed = value)
            onDraftChanged()
        }

        binding.f081AspectSpinner.onItemSelectedListener = onSpinnerPick { position ->
            val value = aspectValues.getOrNull(position) ?: return@onSpinnerPick
            if (value == draft.aspectMode) return@onSpinnerPick
            draft = draft.copy(aspectMode = value)
            onDraftChanged()
        }

        binding.f081SeekStepSpinner.onItemSelectedListener = onSpinnerPick { position ->
            val value = seekValues.getOrNull(position) ?: return@onSpinnerPick
            if (value == draft.seekStepSec) return@onSpinnerPick
            draft = draft.copy(seekStepSec = value)
            onDraftChanged()
        }

        binding.f081LongPressSpinner.onItemSelectedListener = onSpinnerPick { position ->
            val value = longPressValues.getOrNull(position) ?: return@onSpinnerPick
            if (value == draft.longPressSpeed) return@onSpinnerPick
            draft = draft.copy(longPressSpeed = value)
            onDraftChanged()
        }

        binding.f081AutoNextSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == draft.autoPlayNext) return@setOnCheckedChangeListener
            draft = draft.copy(autoPlayNext = checked)
            onDraftChanged()
        }

        binding.f081SubtitleSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == draft.subtitleAutoLoad) return@setOnCheckedChangeListener
            draft = draft.copy(subtitleAutoLoad = checked)
            onDraftChanged()
        }
    }

    /** 下拉回调的样板：只要位置，不关心 AdapterView 和 View。 */
    private fun onSpinnerPick(apply: (Int) -> Unit): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                apply(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    /**
     * 把 [draft] 回填到六个控件。
     *
     * 下拉的可选值每次重建：恢复默认后草稿可能回到档位表里的另一项，
     * 而"草稿值不在表里"的情况（旧版自定义步长）要把它补进表里，否则 Spinner 会静默显示第一项，
     * 看起来像用户自己选错了。
     */
    private fun renderControls() {
        speedValues = speedOptionsWith(draft.speed)
        aspectValues = F081Engine.ASPECT_OPTIONS
        seekValues = seekOptionsWith(draft.seekStepSec)
        longPressValues = longPressOptionsWith(draft.longPressSpeed)

        bindSpinner(
            binding.f081SpeedSpinner,
            speedValues.map { F081Engine.speedLabel(it) },
            speedValues.indexOf(draft.speed)
        )
        bindSpinner(
            binding.f081AspectSpinner,
            aspectValues.map { F081Engine.aspectLabel(it) },
            aspectValues.indexOf(draft.aspectMode)
        )
        bindSpinner(
            binding.f081SeekStepSpinner,
            seekValues.map { F081Engine.seekStepLabel(it) },
            seekValues.indexOf(draft.seekStepSec)
        )
        bindSpinner(
            binding.f081LongPressSpinner,
            longPressValues.map { F081Engine.longPressSpeedLabel(it) },
            longPressValues.indexOf(draft.longPressSpeed)
        )

        binding.f081AutoNextSwitch.isChecked = draft.autoPlayNext
        binding.f081SubtitleSwitch.isChecked = draft.subtitleAutoLoad
    }

    /** 重建一个下拉的 adapter 并定位到选中档位（-1 就不动，交给 adapter 自己选第一项）。 */
    private fun bindSpinner(spinner: Spinner, labels: List<String>, selected: Int) {
        spinner.adapter = LabelAdapter(labels)
        if (selected in labels.indices) spinner.setSelection(selected, false)
    }

    /**
     * 下拉项适配器：项视图用代码造 TextView，不引系统 layout。
     *
     * `android.R.layout.simple_spinner_item` 更省事，但它的文字颜色来自系统主题的
     * textColorPrimary，深色 / 浅色切换时和本项目的 theme_text_primary 对不上，
     * 电视上还会出现焦点态文字发灰。自己造一个 TextView 反而能保证与正文同色同字号；
     * 折叠态和下拉列表共用同一个视图，靠 `getDropDownView` 的默认实现即可。
     */
    private inner class LabelAdapter(private val labels: List<String>) : BaseAdapter() {

        override fun getCount(): Int = labels.size

        override fun getItem(position: Int): Any = labels[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView as? TextView ?: TextView(this@F081Activity).apply {
                setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                val side = resources.getDimensionPixelSize(R.dimen.tab_padding)
                setPadding(side, 0, side, 0)
            }
            view.text = labels.getOrElse(position) { "" }
            return view
        }
    }

    // ===== 摘要 =====

    /**
     * 「当前生效配置」：面板一改就重算，不用等保存。
     *
     * 旁边那行小字说明这份组合是"默认"还是"自定义"，用户一眼能看出自己是不是已经偏离出厂值。
     */
    private fun renderSummary() {
        val isDefault = F081Engine.isDefault(draft)
        binding.f081Summary.text = F081Engine.summary(draft)
        binding.f081SummaryState.text = getString(
            if (isDefault) R.string.f081_summary_default else R.string.f081_summary_custom
        )
        binding.f081SummaryState.setTextColor(
            ContextCompat.getColor(
                this,
                if (isDefault) R.color.theme_text_secondary else R.color.theme_warning
            )
        )
    }

    /** 草稿变了：只重画摘要 + 状态行，不落盘。 */
    private fun onDraftChanged() {
        renderSummary()
        setStatus(getString(R.string.f081_status_dirty), R.color.theme_warning)
    }

    // ===== 保存 / 恢复 =====

    /**
     * 保存草稿。
     *
     * [F081Engine.save] 返回的是"真正写下去的那一份"（越界值会被吸附到最近档位），
     * 所以拿返回值回填控件——否则界面还显示用户选的 9.9x，实际生效却是 3.0x。
     * 紧接着 [F081Engine.applyTo] 把这份值发布成快照，播放页下一次起播读到的就是它。
     */
    private fun saveDraft() {
        val saved = try {
            F081Engine.save(this, draft)
        } catch (e: Exception) {
            reportFailure(e)
            return
        }
        draft = F081Engine.applyTo(saved)
        renderControls()
        renderSummary()
        setStatus(getString(R.string.f081_status_saved), R.color.theme_success)
        CustomToast.show(this, getString(R.string.f081_toast_saved))
    }

    /**
     * 一键恢复默认：回到 [F081Engine.defaults] 的出厂组合，并直接保存。
     *
     * 这里不留给用户"再点一次保存"：恢复默认本身就是一个明确动作，
     * 中间多一步只会让人以为没生效。写盘失败时草稿仍然回到默认值（面板上看得见），
     * 只是状态行会说明没存进去。
     */
    private fun restoreDefaults() {
        val preset = F081Engine.defaults()
        val saved = try {
            F081Engine.save(this, preset)
        } catch (e: Exception) {
            draft = preset
            renderControls()
            renderSummary()
            reportFailure(e)
            return
        }
        draft = F081Engine.applyTo(saved)
        renderControls()
        renderSummary()
        setStatus(getString(R.string.f081_status_reset), R.color.theme_success)
        CustomToast.show(this, getString(R.string.f081_toast_reset))
    }

    /** 写盘失败的统一出口：状态行 + 提示，不弹对话框打断用户在电视上的操作。 */
    private fun reportFailure(e: Exception) {
        val message = getString(R.string.f081_status_error, e.javaClass.simpleName)
        setStatus(message, R.color.theme_error)
        CustomToast.show(this, message)
    }

    // ===== 小工具 =====

    /** 倍速档位表 + 当前值：当前值不在表里就补进去（旧版自定义值），保持升序。 */
    private fun speedOptionsWith(current: Float): List<Float> =
        if (F081Engine.SPEED_OPTIONS.contains(current)) {
            F081Engine.SPEED_OPTIONS
        } else {
            (F081Engine.SPEED_OPTIONS + current).distinct().sorted()
        }

    /** 快进步长档位表 + 当前值（同上）。 */
    private fun seekOptionsWith(current: Int): List<Int> =
        if (F081Engine.SEEK_STEP_OPTIONS.contains(current)) {
            F081Engine.SEEK_STEP_OPTIONS
        } else {
            (F081Engine.SEEK_STEP_OPTIONS + current).distinct().sorted()
        }

    /** 长按倍速档位表 + 当前值（同上）。 */
    private fun longPressOptionsWith(current: Float): List<Float> =
        if (F081Engine.LONG_PRESS_SPEED_OPTIONS.contains(current)) {
            F081Engine.LONG_PRESS_SPEED_OPTIONS
        } else {
            (F081Engine.LONG_PRESS_SPEED_OPTIONS + current).distinct().sorted()
        }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f081Status.text = message
        binding.f081Status.setTextColor(ContextCompat.getColor(this, colorRes))
    }
}
