package com.tv.ball.feature.f078

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF078Binding
import kotlin.math.roundToInt

/**
 * 「设置分组重置」页。
 *
 * 一屏摊开 12 个真实设置项，按分组分块，每块右上角一个「重置本组」，页面底部一个「全部重置」。
 * 数据全部来自 [F078Engine]：`snapshot()` 给出每项当前值 / 默认值 / 是否默认，
 * `resetGroup()` / `resetAll()` 负责真的清键并返回被恢复的项数。
 *
 * 重置必须二次确认：清掉的是存储值，`SharedPreferences` 没有「撤销」，误触一次就得去五六个功能页里
 * 一格格点回来。对话框里写清「这一组有几项不是默认值」，用户知道这一下会动多少；已经全是默认值的分组
 * 直接给一句提示、不弹框——弹一个「确定要重置吗」然后什么都不会变，只会让人怀疑按钮坏了。
 *
 * 重置后立刻重新 `snapshot()` 刷整页：值都是同一份盘上数据算出来的，
 * 「重置了但页面还显示旧值」这种自相矛盾的状态在这套写法下不存在。
 */
class F078Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF078Binding

    /** 当前这一份快照；二次确认里的「N 项会被重置」就数它，和列表上看到的是同一份数据。 */
    private var states: List<KeyState> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF078Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f078ResetAll.setOnClickListener { confirmResetAll() }

        refresh(getString(R.string.f078_status_idle, F078Engine.CATALOG_SIZE))
    }

    // ===== 渲染 =====

    /** 重读快照 → 重画汇总与分组 → 写状态行；三个动作永远一起做，不会出现半新半旧的页面。 */
    private fun refresh(message: CharSequence, colorRes: Int = R.color.theme_text_secondary) {
        states = F078Engine.snapshot(this)
        renderSummary()
        renderGroups()
        setStatus(message, colorRes)
    }

    /** 汇总行：已改几项 / 共几项 / 几个分组。 */
    private fun renderSummary() {
        val changed = states.count { !it.isDefault }
        binding.f078Summary.text =
            getString(R.string.f078_summary, changed, states.size, F078Engine.groups().size)
        binding.f078Summary.setTextColor(
            color(if (changed == 0) R.color.theme_success else R.color.theme_primary)
        )
    }

    /** 按引擎给的分组顺序铺块；块里再按声明的键顺序铺行。 */
    private fun renderGroups() {
        binding.f078GroupList.removeAllViews()
        F078Engine.groups().forEach { group ->
            binding.f078GroupList.addView(groupSection(group))
        }
    }

    /** 一个分组：标题行（组名 + 项数 + 重置本组）后面跟着这一组的每一行。 */
    private fun groupSection(group: String): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        section.addView(groupHeader(group))
        states.filter { it.key.group == group }.forEach { section.addView(keyRow(it)) }
        return section
    }

    /** 分组头：组名与「已改 n/m」，右侧是这一组的重置按钮。 */
    private fun groupHeader(group: String): LinearLayout {
        val groupStates = states.filter { it.key.group == group }
        val changed = groupStates.count { !it.isDefault }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.f078_group_header, group, groupStates.size, changed)
                setTextColor(color(R.color.theme_text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(resetButton(getString(R.string.f078_action_reset_group)) { confirmResetGroup(group) })
        return header
    }

    /**
     * 一项设置：第一行「中文标题 + 默认/已改徽标」，第二行「当前值 · 默认值」，第三行是 prefs 键名。
     *
     * 键名不藏起来是有意的：这一页是排障用的，用户报「字幕字号不对」时，能直接对上
     * `f012_subtitle_style / font_size_sp` 比对上「字幕字号」有用得多。
     */
    private fun keyRow(state: KeyState): LinearLayout {
        val badgeText = getString(
            if (state.isDefault) R.string.f078_state_default else R.string.f078_state_changed
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@F078Activity, R.drawable.bg_card_surface)
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = getString(
                R.string.f078_row_a11y,
                F078Engine.title(state.key),
                state.current,
                state.default,
                badgeText
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(this).apply {
                text = F078Engine.title(state.key)
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        head.addView(
            TextView(this).apply {
                text = badgeText
                setTextColor(
                    color(if (state.isDefault) R.color.theme_success else R.color.theme_warning)
                )
                textSize = 12f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = ContextCompat.getDrawable(
                    this@F078Activity,
                    if (state.isDefault) R.drawable.bg_tag_green else R.drawable.bg_tag_orange
                )
            }
        )
        row.addView(head)

        row.addView(
            TextView(this).apply {
                text = getString(R.string.f078_row_value, state.current, state.default)
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
        )

        row.addView(
            TextView(this).apply {
                text = getString(
                    R.string.f078_row_key,
                    state.key.prefsName,
                    state.key.key
                )
                setTextColor(color(R.color.theme_outline))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
        )
        return row
    }

    /** 一个可聚焦的胶囊按钮，焦点态交给 bg_primary_button / bg_secondary_button 的 selector。 */
    private fun resetButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(color(R.color.theme_on_primary))
        textSize = 13f
        gravity = Gravity.CENTER
        minHeight = dp(36)
        minWidth = dp(96)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = ContextCompat.getDrawable(this@F078Activity, R.drawable.bg_primary_button)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ===== 重置 =====

    /**
     * 分组重置的二次确认。
     *
     * 数字取自 [states]（就是列表上那几行），不是重新读一遍盘——用户看到的和确认的是同一份数据。
     */
    private fun confirmResetGroup(group: String) {
        val groupStates = states.filter { it.key.group == group }
        val changed = groupStates.count { !it.isDefault }
        if (changed == 0) {
            setStatus(getString(R.string.f078_status_group_none, group), R.color.theme_success)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.f078_confirm_group_title, group))
            .setMessage(
                getString(
                    R.string.f078_confirm_group_message,
                    group,
                    changed,
                    groupStates.size
                )
            )
            .setNegativeButton(R.string.f078_action_cancel, null)
            .setPositiveButton(R.string.f078_action_confirm) { _, _ -> resetGroup(group) }
            .show()
    }

    /** 全部重置的二次确认：写清总改动项数，避免「以为只动一页」的误判。 */
    private fun confirmResetAll() {
        val changed = states.count { !it.isDefault }
        if (changed == 0) {
            setStatus(getString(R.string.f078_status_all_none), R.color.theme_success)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.f078_confirm_all_title)
            .setMessage(getString(R.string.f078_confirm_all_message, states.size, changed))
            .setNegativeButton(R.string.f078_action_cancel, null)
            .setPositiveButton(R.string.f078_action_confirm) { _, _ -> resetAll() }
            .show()
    }

    /** 真的重置一组：清完立刻重读快照，刷出来的是重置之后的真实状态。 */
    private fun resetGroup(group: String) {
        val cleared = F078Engine.resetGroup(this, group)
        if (cleared == 0) {
            refresh(getString(R.string.f078_status_group_none, group), R.color.theme_success)
        } else {
            refresh(getString(R.string.f078_status_group_done, group, cleared), R.color.theme_success)
        }
    }

    /** 全部重置。 */
    private fun resetAll() {
        val cleared = F078Engine.resetAll(this)
        if (cleared == 0) {
            refresh(getString(R.string.f078_status_all_none), R.color.theme_success)
        } else {
            refresh(getString(R.string.f078_status_all_done, cleared), R.color.theme_success)
        }
    }

    // ===== 小工具 =====

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f078Status.text = message
        binding.f078Status.setTextColor(color(colorRes))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}
