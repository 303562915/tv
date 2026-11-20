package com.tv.ball.feature.f036

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF036Binding

/**
 * F036「随机播放与权重调度」页面。
 *
 * 相关规则都在 [F036Engine]（纯 Kotlin），这里只做三件事：
 * 把 6 条示例曲目的权重读成可调的滑块、用固定种子跑一遍 20 次抽取、
 * 再把序列 / 轮次进度 / 连续重复检测结果贴到界面上。
 */
class F036Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF036Binding

    /**
     * 6 条示例曲目，权重按「常看的给点数」分成 1..5 三档。
     * 只在这里定义一次初始值，之后改权重都写进 [weights]，不再回写这个常量表，
     * 这样「重新演示」拿到的仍然是用户当前调好的权重。
     */
    private val samples: List<F036Engine.Track> = listOf(
        F036Engine.Track(1L, "三体 第一季 01", 5),
        F036Engine.Track(2L, "流浪地球 2", 4),
        F036Engine.Track(3L, "漫长的季节 01", 3),
        F036Engine.Track(4L, "狂飙 第 12 集", 2),
        F036Engine.Track(5L, "变形金刚 5", 2),
        F036Engine.Track(6L, "宇宙时空之旅 01", 1)
    )

    /** 曲目 id -> 当前权重。改滑块时同步更新，抽取都从这份表读。 */
    private val weights = LinkedHashMap<Long, Int>()

    private val weightRows = ArrayList<WeightRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF036Binding.inflate(layoutInflater)
        setContentView(binding.root)

        samples.forEach { weights[it.id] = it.weight.coerceIn(F036Engine.MIN_WEIGHT, F036Engine.MAX_WEIGHT) }

        buildWeightRows()
        binding.btnReplay.setOnClickListener {
            render()
            toast(getString(R.string.f036_toast_replayed, DEMO_DRAWS))
        }
        render()
    }

    // region 权重调节

    /**
     * 每首曲目一行：标题 + 当前权重 + 滑块。
     *
     * 行是动态建的，但只在进页面时建一次：改权重只重算下面的演示区，
     * 不重建滑块，否则 D-Pad 正在调的那根 SeekBar 会连同焦点一起被换掉。
     */
    private fun buildWeightRows() {
        val container = binding.weightContainer
        container.removeAllViews()
        weightRows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        samples.forEachIndexed { index, track ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowMinHeight
                setPadding(pad, 0, pad, 0)
            }

            val title = TextView(this).apply {
                text = track.title
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val value = TextView(this).apply {
                text = getString(R.string.f036_weight_value, currentWeight(track))
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 13f
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val bar = SeekBar(this).apply {
                id = View.generateViewId()
                // SeekBar 的进度是 0..max，权重是 1..9，中间靠 MIN_WEIGHT 平移。
                max = F036Engine.MAX_WEIGHT - F036Engine.MIN_WEIGHT
                progress = currentWeight(track) - F036Engine.MIN_WEIGHT
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = gap }
                // SeekBar 不是 ViewGroup，没有 foreground，焦点可见性只能换背景图。
                background = ContextCompat.getDrawable(this@F036Activity, R.drawable.bg_card_surface)
                setOnFocusChangeListener { view, hasFocus ->
                    view.background = ContextCompat.getDrawable(
                        this@F036Activity,
                        if (hasFocus) R.drawable.bg_mode_card_selected else R.drawable.bg_card_surface
                    )
                }
                // 先写 progress 再挂监听，避免初始值触发一次无意义的重算。
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val weight = progress + F036Engine.MIN_WEIGHT
                        weights[track.id] = weight
                        value.text = getString(R.string.f036_weight_value, weight)
                        // 只有用户真的拖/按出来的变化才重算；程序回写不重算。
                        if (fromUser) render()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }

            row.addView(title)
            row.addView(value)
            row.addView(bar)
            container.addView(row)
            weightRows.add(WeightRow(track.id, value, bar))
        }

        // 滑块之间的上下焦点是动态生成的，默认焦点搜索会跳到卡片外面，这里显式串起来。
        weightRows.forEachIndexed { index, row ->
            if (index > 0) row.bar.nextFocusUpId = weightRows[index - 1].bar.id
            if (index < weightRows.lastIndex) row.bar.nextFocusDownId = weightRows[index + 1].bar.id
        }
        weightRows.firstOrNull()?.bar?.nextFocusUpId = View.NO_ID
        val lastBar = weightRows.lastOrNull()?.bar
        if (lastBar != null) {
            lastBar.nextFocusDownId = binding.btnReplay.id
            binding.btnReplay.nextFocusUpId = lastBar.id
        }
    }

    private fun currentWeight(track: F036Engine.Track): Int =
        (weights[track.id] ?: track.weight).coerceIn(F036Engine.MIN_WEIGHT, F036Engine.MAX_WEIGHT)

    // endregion

    // region 演示

    /**
     * 用固定种子重新跑一遍演示。
     *
     * 每次渲染都新建调度器：种子固定，所以同一组权重下 20 次抽取的序列完全一致；
     * 改权重等于换了一组抽样概率，序列才会变。
     */
    private fun render() {
        val tracks = samples.map { it.copy(weight = currentWeight(it)) }
        val scheduler = F036Engine.ShuffleScheduler(tracks, DEMO_SEED)
        val draws = scheduler.playAllSequence(DEMO_DRAWS)

        binding.seedValue.text = getString(R.string.f036_seed, DEMO_SEED)
        binding.roundStat.text = getString(
            R.string.f036_round_stat,
            scheduler.round,
            scheduler.playedInRound,
            scheduler.roundSize,
            scheduler.remainingCount
        )

        renderDuplicateCheck(draws.map { it.track })
        renderDeterminism(tracks, draws)
        renderDistribution(draws, tracks)
        renderSequence(draws)
    }

    /** 连续重复检测：相邻两次抽取是否是同一首。 */
    private fun renderDuplicateCheck(played: List<F036Engine.Track>) {
        val hits = F036Engine.duplicateIndexes(played)
        if (hits.isEmpty()) {
            binding.duplicateStat.text = getString(R.string.f036_duplicate_none, played.size)
            binding.duplicateStat.setTextColor(color(R.color.theme_success))
        } else {
            binding.duplicateStat.text = getString(
                R.string.f036_duplicate_found,
                hits.size,
                hits.joinToString("、")
            )
            binding.duplicateStat.setTextColor(color(R.color.theme_error))
        }
    }

    /** 同种子重跑一次，把两次序列比一遍，验证随机源确实可复现。 */
    private fun renderDeterminism(tracks: List<F036Engine.Track>, first: List<F036Engine.Draw>) {
        val second = F036Engine.ShuffleScheduler(tracks, DEMO_SEED).playAllSequence(DEMO_DRAWS)
        val same = first.size == second.size &&
            first.indices.all { first[it].track.id == second[it].track.id }
        binding.deterministicStat.text = getString(
            if (same) R.string.f036_deterministic_ok else R.string.f036_deterministic_diff,
            first.size
        )
    }

    /** 每首曲目在 20 次抽取里实际被抽中几次，用来直观对照权重。 */
    private fun renderDistribution(draws: List<F036Engine.Draw>, tracks: List<F036Engine.Track>) {
        val container = binding.distributionContainer
        container.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(12)

        F036Engine.distribution(draws, tracks).forEachIndexed { index, (track, count) ->
            val line = TextView(this).apply {
                text = getString(R.string.f036_distribution_row, track.title, track.effectiveWeight, count)
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 13f
                isFocusable = false
                setPadding(pad, dp(4), pad, dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap / 2 }
            }
            container.addView(line)
        }
    }

    /** 20 行抽取序列，触发轮次重置的那一行用主题色标出来。 */
    private fun renderSequence(draws: List<F036Engine.Draw>) {
        val container = binding.sequenceContainer
        container.removeAllViews()
        val pad = dp(12)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)

        draws.forEachIndexed { index, draw ->
            val line = TextView(this).apply {
                text = if (draw.roundRestarted) {
                    getString(R.string.f036_sequence_row_reset, draw.index, draw.track.title, draw.roundIndex)
                } else {
                    getString(R.string.f036_sequence_row, draw.index, draw.track.title, draw.roundIndex)
                }
                setTextColor(color(if (draw.roundRestarted) R.color.theme_primary else R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                isFocusable = false
                minimumHeight = rowMinHeight
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
                background = ContextCompat.getDrawable(this@F036Activity, R.drawable.bg_mode_row)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else resources.getDimensionPixelSize(R.dimen.card_gap) / 2 }
            }
            container.addView(line)
        }
    }

    // endregion

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private class WeightRow(
        val trackId: Long,
        val valueLabel: TextView,
        val bar: SeekBar
    )

    private companion object {
        /** 固定种子：同权重下序列可复现，演示才有意义。 */
        const val DEMO_SEED = 20260101L

        /** 演示抽取次数：6 首 × 3 轮 = 18，再补 2 次刚好跨进第 4 轮，能同时看到轮次重置。 */
        const val DEMO_DRAWS = 20
    }
}