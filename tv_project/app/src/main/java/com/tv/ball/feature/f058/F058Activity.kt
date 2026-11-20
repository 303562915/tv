package com.tv.ball.feature.f058

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF058Binding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F058「流量统计」页面。
 *
 * 统计全在 [TrafficMeter]，这里只负责四块：
 * 1. 模拟上报：两个按钮（+1MB / +50MB）代表播放器往引擎里加字节；
 * 2. 分项统计：本次会话 / 今日 / 近 7 天 / 累计；
 * 3. 近 7 天分桶：每天一行，今天用主色标出来，占比给个参考；
 * 4. 清零：本次会话与全部记录都走二次确认，避免误触把历史清空。
 */
class F058Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF058Binding

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF058Binding.inflate(layoutInflater)
        setContentView(binding.root)

        TrafficMeter.attach(this)

        binding.btnAddSmall.setOnClickListener { report(TrafficMeter.DEFAULT_ADD_SMALL) }
        binding.btnAddLarge.setOnClickListener { report(TrafficMeter.DEFAULT_ADD_LARGE) }
        binding.btnResetSession.setOnClickListener {
            confirm(
                titleRes = R.string.f058_reset_session_title,
                messageRes = R.string.f058_reset_session_message,
                onConfirmed = {
                    TrafficMeter.resetSession()
                    binding.statusValue.text = getString(R.string.f058_status_session_reset)
                    refreshStats()
                    renderDays()
                }
            )
        }
        binding.btnResetAll.setOnClickListener {
            confirm(
                titleRes = R.string.f058_reset_all_title,
                messageRes = R.string.f058_reset_all_message,
                onConfirmed = {
                    TrafficMeter.resetAll()
                    binding.statusValue.text = getString(R.string.f058_status_reset)
                    refreshStats()
                    renderDays()
                }
            )
        }

        binding.statusValue.text = getString(R.string.f058_status_idle)
        refreshStats()
        renderDays()
    }

    /** 模拟播放器上报：加字节 -> 立刻回显这一笔与累计，再刷两块视图。 */
    private fun report(bytes: Long) {
        TrafficMeter.add(bytes)
        binding.statusValue.text = getString(
            R.string.f058_status_added,
            TrafficMeter.formatBytes(bytes),
            TrafficMeter.formatBytes(TrafficMeter.sessionBytes)
        )
        refreshStats()
        renderDays()
    }

    /** 四个分项：会话 / 今日 / 近 7 天 / 累计，各带一行说明。 */
    private fun refreshStats() {
        binding.sessionValue.text = TrafficMeter.formatBytes(TrafficMeter.sessionBytes)
        binding.sessionDetail.text = getString(
            R.string.f058_session_detail,
            timeFormat.format(Date(TrafficMeter.sessionStartedAt))
        )
        binding.sessionValue.setTextColor(color(R.color.theme_primary))

        val today = TrafficMeter.todayBytes()
        binding.todayValue.text = TrafficMeter.formatBytes(today)
        binding.todayDetail.text = getString(
            R.string.f058_today_detail,
            dateFormat.format(Date(System.currentTimeMillis()))
        )

        val week = TrafficMeter.weeklyBytes(TrafficMeter.WEEK_DAYS)
        binding.weekValue.text = TrafficMeter.formatBytes(week)
        binding.weekDetail.text = getString(R.string.f058_week_detail, TrafficMeter.WEEK_DAYS)

        val total = TrafficMeter.totalBytes()
        binding.totalValue.text = TrafficMeter.formatBytes(total)
        binding.totalDetail.text = getString(R.string.f058_total_detail, TrafficMeter.recordedDays())
    }

    /**
     * 近 7 天：每天一行，今天用主色并带「今天」后缀。
     *
     * 行是运行时拼的，布局里只留容器；占比用「当天 / 七天最大值」估算，
     * 全为 0 时显示空态文案，不做 0% 的假柱子。
     */
    private fun renderDays() {
        binding.dayContainer.removeAllViews()
        val days = TrafficMeter.recentDays(TrafficMeter.WEEK_DAYS)
        val maxBytes = days.maxOfOrNull { it.bytes } ?: 0L

        binding.dayEmpty.visibility = if (maxBytes == 0L) View.VISIBLE else View.GONE

        days.forEach { day ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@F058Activity, R.drawable.bg_mode_row)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }

            val label = TextView(this).apply {
                text = if (day.isToday) {
                    getString(R.string.f058_day_today, day.label)
                } else {
                    day.label
                }
                textSize = 13f
                isFocusable = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(
                    color(if (day.isToday) R.color.theme_primary else R.color.theme_text_secondary)
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val detail = if (maxBytes <= 0L || day.bytes <= 0L) {
                TrafficMeter.formatBytes(day.bytes)
            } else {
                getString(
                    R.string.f058_day_value_share,
                    TrafficMeter.formatBytes(day.bytes),
                    day.bytes * 100L / maxBytes
                )
            }

            val value = TextView(this).apply {
                text = detail
                textSize = 13f
                isFocusable = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setTextColor(
                    color(
                        when {
                            day.isToday -> R.color.theme_primary
                            day.bytes > 0L -> R.color.theme_text_primary
                            else -> R.color.theme_disabled
                        }
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(label)
            row.addView(value)
            binding.dayContainer.addView(row)
        }
    }

    /** 清零一律二次确认：标题与正文分开取资源，确认后执行 [onConfirmed]。 */
    private fun confirm(titleRes: Int, messageRes: Int, onConfirmed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(R.string.f058_reset_cancel, null)
            .setPositiveButton(R.string.f058_reset_confirm) { _, _ -> onConfirmed() }
            .show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}