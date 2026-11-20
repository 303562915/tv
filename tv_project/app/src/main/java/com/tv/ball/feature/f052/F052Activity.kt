package com.tv.ball.feature.f052

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF052Binding

/**
 * F052「搜索范围过滤」页面。
 *
 * 过滤、计数、文案全在 [F052Engine]；这里只做四件事：
 * 1. 准备 10 条示例数据（四类各若干条，时长覆盖四个档位）；
 * 2. 把界面上的操作翻译成 [F052Engine.ScopeFilter]：关键字框、类型多选、时长四档；
 * 3. 每次条件变化都重算结果，并刷新按钮选中态、条数与生效条件文案；
 * 4. 「清空条件」回到不限类型 / 不限时长 / 无关键字的初始态。
 */
class F052Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF052Binding

    /** 当前过滤条件，唯一状态源。 */
    private var filter: F052Engine.ScopeFilter = F052Engine.ScopeFilter()

    /** 当前时长档位，切档时用来点掉旧选中态。 */
    private var band: F052Engine.DurationBand = F052Engine.DurationBand.ANY

    /** 10 条示例：电影 3 / 剧集 3 / 短片 2 / 本地 2，时长横跨 5 分钟到 116 分钟。 */
    private val docs: List<F052Engine.Doc> = listOf(
        F052Engine.Doc("m1", "流浪地球 2", F052Engine.Type.MOVIE, 116 * MINUTE),
        F052Engine.Doc("m2", "星际穿越", F052Engine.Type.MOVIE, 148 * MINUTE),
        F052Engine.Doc("m3", "蓝色海洋", F052Engine.Type.MOVIE, 56 * MINUTE),

        F052Engine.Doc("s1", "漫长的季节 01", F052Engine.Type.SERIES, 45 * MINUTE),
        F052Engine.Doc("s2", "三体 第一季 01", F052Engine.Type.SERIES, 42 * MINUTE),
        F052Engine.Doc("s3", "地球脉动 第二季 03", F052Engine.Type.SERIES, 50 * MINUTE),

        F052Engine.Doc("k1", "深海迷航 · 预告", F052Engine.Type.SHORT, 3 * MINUTE),
        F052Engine.Doc("k2", "宇宙科普小课堂", F052Engine.Type.SHORT, 8 * MINUTE),

        F052Engine.Doc("l1", "旅行到宇宙边缘.mp4", F052Engine.Type.LOCAL, 72 * MINUTE),
        F052Engine.Doc("l2", "地球最后的夜晚.mp4", F052Engine.Type.LOCAL, 9 * MINUTE)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF052Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.keywordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update(filter.withKeyword(s?.toString().orEmpty()))
        })

        typeButtons().forEach { (type, button) ->
            button.setOnClickListener { update(filter.toggle(type)) }
        }

        bandButtons().forEach { (targetBand, button) ->
            button.setOnClickListener { update(filter.withBand(targetBand), targetBand) }
        }

        binding.btnClear.setOnClickListener {
            band = F052Engine.DurationBand.ANY
            update(F052Engine.ScopeFilter())
            binding.keywordInput.setText("")
            binding.keywordInput.requestFocus()
        }

        renderBandChrome()
        update(filter)
    }

    /** 条件变化统一入口：先刷新控件状态，再重算结果与文案。 */
    private fun update(next: F052Engine.ScopeFilter, nextBand: F052Engine.DurationBand? = null) {
        filter = next
        if (nextBand != null) band = nextBand
        renderBandChrome()
        renderTypes()
        renderResults()
    }

    /** 类型按钮：选中换底色 + 主色文字，未选中回到次级按钮样式。 */
    private fun renderTypes() {
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_mode_card_selected)
        val normalBg = ContextCompat.getDrawable(this, R.drawable.bg_secondary_button)
        val selectedColor = ContextCompat.getColor(this, R.color.theme_primary)
        val normalColor = ContextCompat.getColor(this, R.color.theme_text_primary)
        typeButtons().forEach { (type, button) ->
            val selected = type in filter.types
            button.setBackground(if (selected) selectedBg else normalBg)
            button.setTextColor(if (selected) selectedColor else normalColor)
        }
        binding.typeSummary.text = if (filter.hasTypes) {
            getString(
                R.string.f052_type_selected,
                filter.types.size,
                filter.types.joinToString(separator = "、") { typeName(it) }
            )
        } else {
            getString(R.string.f052_type_all)
        }
    }

    /** 时长四档：档位文案带区间，选中态单独换底色。 */
    private fun renderBandChrome() {
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_mode_card_selected)
        val normalBg = ContextCompat.getDrawable(this, R.drawable.bg_secondary_button)
        val selectedColor = ContextCompat.getColor(this, R.color.theme_primary)
        val normalColor = ContextCompat.getColor(this, R.color.theme_text_primary)
        bandButtons().forEach { (targetBand, button) ->
            val selected = targetBand == band
            button.text = getString(bandLabelRes(targetBand))
            button.setBackground(if (selected) selectedBg else normalBg)
            button.setTextColor(if (selected) selectedColor else normalColor)
        }
        binding.durationSummary.text = if (filter.hasDuration) {
            getString(R.string.f052_duration_selected, bandLabel(targetBand()))
        } else {
            getString(R.string.f052_duration_all)
        }
    }

    /** 结果列表 + 汇总行 + 生效条件行。 */
    private fun renderResults() {
        val hits = F052Engine.apply(docs, filter)
        binding.resultCount.text = getString(R.string.f052_result_count, hits.size, docs.size)
        binding.conditionValue.text = getString(
            R.string.f052_condition_value,
            F052Engine.describe(filter, scopeText())
        )
        binding.conditionDetail.text = when {
            filter.isIdle -> getString(R.string.f052_condition_and_idle)
            filter.hasKeyword -> getString(R.string.f052_condition_and, hits.size)
            else -> getString(R.string.f052_condition_and_no_keyword, hits.size)
        }
        binding.resultEmpty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE

        val container = binding.resultContainer
        container.removeAllViews()
        if (hits.isEmpty()) return

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val totalText = getString(R.string.f052_row_meta)
        hits.forEachIndexed { index, doc ->
            val row = TextView(this).apply {
                text = String.format(totalText, typeName(doc.type), doc.durationMinutes, doc.title)
                setTextColor(ContextCompat.getColor(this@F052Activity, R.color.theme_text_primary))
                textSize = 15f
                maxLines = 2
                isFocusable = false
                background = ContextCompat.getDrawable(this@F052Activity, R.drawable.bg_tag_inset)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
            }
            container.addView(row)
        }
    }

    /** 档位的区间文案，直接复用引擎的格式化，保证按钮和条件行说法一致。 */
    private fun bandLabel(target: F052Engine.DurationBand): String =
        F052Engine.describeDuration(target, scopeText())

    private fun bandLabelRes(target: F052Engine.DurationBand): Int = when (target) {
        F052Engine.DurationBand.ANY -> R.string.f052_band_any
        F052Engine.DurationBand.WITHIN_10 -> R.string.f052_band_within_10
        F052Engine.DurationBand.FROM_10_TO_45 -> R.string.f052_band_10_to_45
        F052Engine.DurationBand.OVER_45 -> R.string.f052_band_over_45
    }

    /** 当前选中档位，给汇总行用。 */
    private fun targetBand(): F052Engine.DurationBand = band

    /** 类型 code -> 显示名，引擎与界面共用这一份映射。 */
    private fun typeName(type: F052Engine.Type): String = getString(
        when (type) {
            F052Engine.Type.MOVIE -> R.string.f052_type_movie
            F052Engine.Type.SERIES -> R.string.f052_type_series
            F052Engine.Type.SHORT -> R.string.f052_type_short
            F052Engine.Type.LOCAL -> R.string.f052_type_local
        }
    )

    /** 引擎需要的文案素材，全部来自 string / 资源。 */
    private fun scopeText(): F052Engine.ScopeText = F052Engine.ScopeText(
        typeNames = F052Engine.Type.values().associateWith { typeName(it) },
        anyType = getString(R.string.f052_type_all),
        anyDuration = getString(R.string.f052_duration_all),
        keywordFormat = getString(R.string.f052_condition_keyword),
        noKeyword = getString(R.string.f052_condition_no_keyword),
        withinFormat = getString(R.string.f052_duration_within),
        rangeFormat = getString(R.string.f052_duration_range),
        aboveFormat = getString(R.string.f052_duration_above),
        template = getString(R.string.f052_condition_template)
    )

    private fun typeButtons(): List<Pair<F052Engine.Type, TextView>> = listOf(
        F052Engine.Type.MOVIE to binding.btnTypeMovie,
        F052Engine.Type.SERIES to binding.btnTypeSeries,
        F052Engine.Type.SHORT to binding.btnTypeShort,
        F052Engine.Type.LOCAL to binding.btnTypeLocal
    )

    private fun bandButtons(): List<Pair<F052Engine.DurationBand, TextView>> = listOf(
        F052Engine.DurationBand.ANY to binding.btnBandAny,
        F052Engine.DurationBand.WITHIN_10 to binding.btnBand10,
        F052Engine.DurationBand.FROM_10_TO_45 to binding.btnBand45,
        F052Engine.DurationBand.OVER_45 to binding.btnBandOver
    )

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private companion object {
        const val MINUTE: Long = 60_000L
    }
}