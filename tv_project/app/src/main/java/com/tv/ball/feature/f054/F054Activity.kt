package com.tv.ball.feature.f054

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF054Binding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F054「线路测速缓存」页面。
 *
 * 逻辑全在 [F054Engine]（纯 Kotlin），这里只做三件事：
 * 1. TTL 选择：1 / 5 / 30 分钟，写进 SharedPreferences，下次进来沿用；
 * 2. 测速：4 条模拟线路一次测完，固定种子生成稳定速度，按 lineId 覆盖进缓存；
 * 3. 展示：按速度降序排行，每行给「测速值 + 测量时间 + 有效/已过期」，
 *    过期行变灰并在按钮上提示「重新测速」，重测只刷新时间戳。
 *
 * 缓存本体是 JSON（`F054Engine.toJson`），存在 `f054_speed_cache` 这份
 * SharedPreferences 的 `results` 键里，进程重建、旋转屏都能读回同一份。
 */
class F054Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF054Binding
    private lateinit var prefs: SharedPreferences

    /** 全量缓存，含已过期项：过期项也要展示（标灰），所以不在这里过滤。 */
    private val results = mutableListOf<SpeedResult>()

    private var ttlMinutes: Int = F054Engine.DEFAULT_TTL_MINUTES

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF054Binding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        results.addAll(F054Engine.fromJson(prefs.getString(KEY_RESULTS, null)))

        ttlMinutes = prefs.getInt(KEY_TTL, F054Engine.DEFAULT_TTL_MINUTES)
            .takeIf { it in F054Engine.TTL_MINUTES }
            ?: F054Engine.DEFAULT_TTL_MINUTES

        // 先落初始勾选，再挂监听：否则初始化本身就会触发一次「TTL 已改」的提示。
        binding.radioTtl1.isChecked = ttlMinutes == 1
        binding.radioTtl5.isChecked = ttlMinutes == 5
        binding.radioTtl30.isChecked = ttlMinutes == 30
        binding.ttlGroup.setOnCheckedChangeListener { _, checkedId ->
            onTtlChanged(checkedId)
        }

        binding.btnMeasure.setOnClickListener { measure() }

        render(getString(R.string.f054_status_ready))
    }

    /** TTL 一改就按新有效期重判一遍过期，顺便把选择记住。 */
    private fun onTtlChanged(checkedId: Int) {
        val minutes = when (checkedId) {
            R.id.radio_ttl_1 -> 1
            R.id.radio_ttl_5 -> 5
            R.id.radio_ttl_30 -> 30
            else -> return
        }
        ttlMinutes = minutes
        prefs.edit().putInt(KEY_TTL, minutes).apply()
        render(getString(R.string.f054_status_ttl, minutes))
    }

    /**
     * 测速：4 条线路一起测，按 lineId 并进缓存后落盘。
     *
     * 速度是固定种子生成的，同一条线路每次都是同一个数，所以这里不需要线程池：
     * 计算量就是 4 次 nextInt，主线程直接跑完。
     */
    private fun measure() {
        val nowMs = System.currentTimeMillis()
        binding.statusValue.text = getString(R.string.f054_measuring, F054Engine.LINES.size)

        val fresh = F054Engine.measureAll(nowMs)
        val merged = F054Engine.merge(results, fresh)
        results.clear()
        results.addAll(merged)
        persist()

        val valid = F054Engine.cache(results, nowMs, F054Engine.ttlMs(ttlMinutes))
        render(
            getString(
                R.string.f054_status_done,
                fresh.size,
                valid.size,
                results.size,
                timeFormat.format(Date(nowMs))
            )
        )
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_RESULTS, F054Engine.toJson(results))
            .putInt(KEY_TTL, ttlMinutes)
            .apply()
    }

    /** 一次整页刷新：状态条、缓存小结、排行列表、按钮文案。 */
    private fun render(status: String) {
        val nowMs = System.currentTimeMillis()
        val ttl = F054Engine.ttlMs(ttlMinutes)
        val ordered = F054Engine.sorted(results)
        val valid = F054Engine.cache(results, nowMs, ttl)

        binding.statusValue.text = status
        binding.summaryValue.text = if (results.isEmpty()) {
            getString(R.string.f054_summary_empty, ttlMinutes)
        } else {
            getString(R.string.f054_summary, valid.size, results.size, ttlMinutes)
        }

        binding.resultsEmpty.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        binding.resultsContainer.removeAllViews()
        ordered.forEachIndexed { index, result ->
            binding.resultsContainer.addView(
                buildRow(index + 1, result, F054Engine.isStale(result, nowMs, ttl), nowMs, ttl)
            )
        }

        binding.btnMeasure.text = if (results.isNotEmpty() && valid.isEmpty()) {
            getString(R.string.f054_remeasure)
        } else {
            getString(R.string.f054_measure)
        }
    }

    /** 一行结果：第一行名次 + 线路名 + 速度，第二行测量时间与有效期状态。 */
    private fun buildRow(
        rank: Int,
        result: SpeedResult,
        stale: Boolean,
        nowMs: Long,
        ttlMs: Long
    ): View {
        val measuredAt = timeFormat.format(Date(result.measuredAtMs))
        val detail = if (stale) {
            getString(R.string.f054_row_stale, measuredAt)
        } else {
            getString(
                R.string.f054_row_valid,
                measuredAt,
                remainingText(F054Engine.remainingMs(result, nowMs, ttlMs))
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.f054_row_title, rank, result.lineName, F054Engine.formatSpeed(result.kbps))
            textSize = 15f
            isFocusable = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(
                color(
                    when {
                        stale -> R.color.theme_text_secondary
                        rank == 1 -> R.color.theme_success
                        else -> R.color.theme_text_primary
                    }
                )
            )
        }

        val caption = TextView(this).apply {
            text = detail
            textSize = 12f
            isFocusable = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(color(R.color.theme_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@F054Activity, R.drawable.bg_mode_row)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = false
            addView(title)
            addView(caption)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
    }

    /** 剩余有效期的口语写法：`4 分 12 秒` / `37 秒` / `已到 TTL`。 */
    private fun remainingText(leftMs: Long): String {
        if (leftMs <= 0L) return getString(R.string.f054_remaining_none)
        val totalSeconds = leftMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            getString(R.string.f054_remaining_min, minutes, seconds)
        } else {
            getString(R.string.f054_remaining_sec, seconds)
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        /** 需求约定的 SharedPreferences 文件名。 */
        const val PREFS_NAME: String = "f054_speed_cache"

        /** 整份缓存序列化成 JSON 后放这个键。 */
        const val KEY_RESULTS: String = "results"

        /** 用户选的 TTL（分钟）。 */
        const val KEY_TTL: String = "ttl_minutes"
    }
}
