package com.tv.ball.feature.f022

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF022Binding
import com.tv.ball.util.CustomToast

/**
 * 「本地视频筛选」页。
 *
 * 页面上的筛选条件全部落到 [F022Engine.Filter]；结果列表与「生效条件」文案由
 * [F022Engine.apply] / [F022Engine.describe] 现算，界面层不复制一份筛选逻辑。
 *
 * 示例数据固定 8 条，覆盖 mp4 / mkv / avi / mov / ts / webm / flv / m4v 与各档时长、体积，
 * 便于一眼看出哪条规则生效；接入真实媒体库时只替换 [SAMPLE_ITEMS] 的来源。
 */
class F022Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF022Binding

    /** 当前选中的扩展名，空集合表示「不限扩展名」。 */
    private val selectedExts = linkedSetOf<String>()

    /** 时长 / 大小区间选项下标，与 [durationOptions] / [sizeOptions] 一一对应。 */
    private var durationIndex = 0
    private var sizeIndex = 0

    /** 生成的按钮与扩展名一一对应，选中态只改样式不重建视图。 */
    private val extButtons = ArrayList<Button>(8)
    private val durationButtons = ArrayList<Button>(4)
    private val sizeButtons = ArrayList<Button>(4)

    /** 可选的扩展名列表，顺序与 [extButtons] 一致。 */
    private val availableExts: List<String> = SAMPLE_ITEMS
        .map { F022Engine.normalizeExt(it.ext) }
        .distinct()
        .sorted()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF022Binding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.let { state ->
            selectedExts.clear()
            state.getStringArrayList(STATE_KEY_EXTS)?.forEach { ext ->
                if (ext in availableExts) selectedExts += ext
            }
            durationIndex = state.getInt(STATE_KEY_DURATION, 0).coerceIn(0, durationOptions.lastIndex)
            sizeIndex = state.getInt(STATE_KEY_SIZE, 0).coerceIn(0, sizeOptions.lastIndex)
        }

        buildExtChips()
        buildRangeChips()
        binding.btnClear.setOnClickListener { clearConditions() }

        renderSelection()
        renderResult()
        // 电视没有触摸屏：进页面就把焦点落在第一个扩展名按钮上，遥控器立刻能操作
        extButtons.firstOrNull()?.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_KEY_EXTS, ArrayList(selectedExts))
        outState.putInt(STATE_KEY_DURATION, durationIndex)
        outState.putInt(STATE_KEY_SIZE, sizeIndex)
    }

    // ------------------------------------------------------------ 条件控件

    /** 按示例里出现过的扩展名生成一组可多选的按钮。 */
    private fun buildExtChips() {
        binding.extChips.removeAllViews()
        extButtons.clear()
        availableExts.forEachIndexed { index, ext ->
            val chip = chipButton(getString(R.string.f022_ext_tag, ext), index > 0)
            chip.setOnClickListener {
                // 再点一次取消；全部取消 = 不限扩展名
                if (!selectedExts.remove(ext)) selectedExts += ext
                renderSelection()
                renderResult()
            }
            extButtons += chip
            binding.extChips.addView(chip)
        }
    }

    /** 时长与大小各一组互斥按钮，点一下切区间。 */
    private fun buildRangeChips() {
        binding.durationChips.removeAllViews()
        durationButtons.clear()
        durationOptions.forEachIndexed { index, option ->
            val chip = chipButton(getString(option.labelRes), index > 0)
            chip.setOnClickListener {
                durationIndex = index
                renderSelection()
                renderResult()
            }
            durationButtons += chip
            binding.durationChips.addView(chip)
        }

        binding.sizeChips.removeAllViews()
        sizeButtons.clear()
        sizeOptions.forEachIndexed { index, option ->
            val chip = chipButton(getString(option.labelRes), index > 0)
            chip.setOnClickListener {
                sizeIndex = index
                renderSelection()
                renderResult()
            }
            sizeButtons += chip
            binding.sizeChips.addView(chip)
        }
    }

    private fun chipButton(label: String, needsMargin: Boolean): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = true
            if (needsMargin) {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.card_gap)
                }
            }
        }

    /** 刷新按钮选中态、扩展名摘要与「生效条件」文案。 */
    private fun renderSelection() {
        extButtons.forEachIndexed { index, button ->
            applySelectedStyle(button, availableExts[index] in selectedExts)
        }
        durationButtons.forEachIndexed { index, button ->
            applySelectedStyle(button, index == durationIndex)
        }
        sizeButtons.forEachIndexed { index, button ->
            applySelectedStyle(button, index == sizeIndex)
        }

        val exts = selectedExts.sorted()
        binding.extSummary.text = if (exts.isEmpty()) {
            getString(R.string.f022_ext_none)
        } else {
            getString(R.string.f022_ext_selected, exts.joinToString("/"))
        }
        binding.conditionText.text = F022Engine.describe(currentFilter())
    }

    /** 选中态用主题色底 ＋ on_primary 文字；未选中用 surface_inset，全部走语义色 token。 */
    private fun applySelectedStyle(button: Button, selected: Boolean) {
        // 用 tint 而不是换背景：保留按钮自带的焦点涟漪，遥控器聚焦时才有可见反馈
        ViewCompat.setBackgroundTintList(
            button,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.theme_primary else R.color.surface_inset
                )
            )
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.theme_on_primary else R.color.theme_text_primary
            )
        )
    }

    /** 清空按钮：扩展名全撤、两个区间回「不限」。 */
    private fun clearConditions() {
        selectedExts.clear()
        durationIndex = 0
        sizeIndex = 0
        renderSelection()
        renderResult()
        CustomToast.show(this, getString(R.string.f022_toast_cleared))
    }

    // ------------------------------------------------------------ 渲染

    private fun currentFilter(): F022Engine.Filter {
        val duration = durationOptions[durationIndex]
        val size = sizeOptions[sizeIndex]
        return F022Engine.Filter(
            exts = selectedExts.toSet(),
            minDurationMs = duration.minMs,
            maxDurationMs = duration.maxMs,
            minSizeMb = size.minMb,
            maxSizeMb = size.maxMb
        )
    }

    private fun renderResult() {
        val filter = currentFilter()
        val hit = F022Engine.apply(SAMPLE_ITEMS, filter)

        binding.resultSummary.text = getString(R.string.f022_result_summary, hit.size, SAMPLE_ITEMS.size)
        binding.conditionText.text = F022Engine.describe(filter)

        binding.resultRows.removeAllViews()

        // 示例区一起重绘：未命中的行淡一层，命中的正常色，一眼看出差异
        val hitPaths = hit.map { it.path }.toHashSet()
        binding.sampleRows.removeAllViews()
        SAMPLE_ITEMS.forEach { item ->
            binding.sampleRows.addView(rowView(sampleLine(item), item.path in hitPaths))
        }

        if (hit.isEmpty()) {
            binding.resultRows.addView(rowView(getString(R.string.f022_result_empty), false))
            return
        }
        hit.forEach { item ->
            binding.resultRows.addView(rowView(sampleLine(item), true))
        }
    }

    /** 统一行文本：名称 · 体积 · 时长 · 扩展名。 */
    private fun sampleLine(item: F022Engine.VideoItem): String = getString(
        R.string.f022_row_line,
        item.name,
        F022Engine.formatSize(item.sizeBytes),
        F022Engine.formatDuration(item.durationMs),
        "." + F022Engine.normalizeExt(item.ext)
    )

    private fun rowView(text: String, hit: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(6), 0, dp(6))
        setTextColor(
            ContextCompat.getColor(
                this@F022Activity,
                if (hit) R.color.theme_text_primary else R.color.theme_disabled
            )
        )
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 数据

    /** 一条区间选项：时长 / 大小两端都用 [F022Engine.UNLIMITED] / -1 表示不限。 */
    private data class RangeOption(
        val labelRes: Int,
        val minMs: Long = F022Engine.UNLIMITED,
        val maxMs: Long = F022Engine.UNLIMITED,
        val minMb: Int = -1,
        val maxMb: Int = -1
    )

    companion object {
        private const val STATE_KEY_EXTS = "f022_exts"
        private const val STATE_KEY_DURATION = "f022_duration"
        private const val STATE_KEY_SIZE = "f022_size"

        private const val MINUTE = 60_000L
        private const val MB = 1024L * 1024L

        /** 时长区间选项，下标 0 恒为「不限」。 */
        private val durationOptions = listOf(
            RangeOption(R.string.f022_duration_opt_all),
            RangeOption(R.string.f022_duration_opt_short, maxMs = MINUTE),
            RangeOption(R.string.f022_duration_opt_normal, minMs = MINUTE, maxMs = 10 * MINUTE),
            RangeOption(R.string.f022_duration_opt_long, minMs = 10 * MINUTE)
        )

        /** 大小区间选项，下标 0 恒为「不限」。 */
        private val sizeOptions = listOf(
            RangeOption(R.string.f022_size_opt_all),
            RangeOption(R.string.f022_size_opt_small, maxMb = 100),
            RangeOption(R.string.f022_size_opt_normal, minMb = 100, maxMb = 500),
            RangeOption(R.string.f022_size_opt_large, minMb = 500)
        )

        /** 示例视频：8 条，覆盖常见扩展名与各档时长、体积。 */
        private val SAMPLE_ITEMS = listOf(
            F022Engine.VideoItem("开场短片.mp4", "/storage/emulated/0/Movies/intro.mp4", 18L * MB, 25_000L, "mp4"),
            F022Engine.VideoItem("纪录片海岸线.mkv", "/storage/emulated/0/Movies/coast.mkv", 1_260L * MB, 42 * MINUTE, "mkv"),
            F022Engine.VideoItem("老电影修复版.avi", "/storage/emulated/0/Movies/classic.avi", 620L * MB, 96 * MINUTE, "avi"),
            F022Engine.VideoItem("手机录像.mov", "/storage/emulated/0/DCIM/Camera/rec.mov", 95L * MB, 3 * MINUTE + 20_000L, "mov"),
            F022Engine.VideoItem("录制流.ts", "/storage/emulated/0/Record/stream.ts", 480L * MB, 8 * MINUTE, "ts"),
            F022Engine.VideoItem("网页视频.webm", "/storage/emulated/0/Download/web.webm", 42L * MB, 95_000L, "webm"),
            F022Engine.VideoItem("直播切片.flv", "/storage/emulated/0/Download/live.flv", 210L * MB, 14 * MINUTE, "flv"),
            F022Engine.VideoItem("演示片花.m4v", "/storage/emulated/0/Movies/demo.m4v", 8L * MB, 12_000L, "m4v")
        )
    }
}
