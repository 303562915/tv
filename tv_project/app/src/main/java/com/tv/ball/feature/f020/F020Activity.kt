package com.tv.ball.feature.f020

import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF020Binding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F020 文件夹多维排序页面。
 *
 * 内置 6 个示例文件夹，四种维度（名称 / 视频数 / 总时长 / 最近修改）随时切换，
 * 升降序一键反转；当前选择写入 SharedPreferences [PREFS_NAME]，下次进入沿用。
 */
class F020Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF020Binding
    private lateinit var prefs: SharedPreferences
    private val folders: List<F020Engine.Folder> = buildFolders()
    private var sortKey: F020Engine.SortKey = F020Engine.SortKey.NAME
    private var ascending: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF020Binding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sortKey = F020Engine.keyOf(prefs.getString(KEY_SORT_KEY, null))
        ascending = prefs.getBoolean(KEY_ASCENDING, true)

        binding.sortName.setOnClickListener { selectKey(F020Engine.SortKey.NAME) }
        binding.sortCount.setOnClickListener { selectKey(F020Engine.SortKey.COUNT) }
        binding.sortDuration.setOnClickListener { selectKey(F020Engine.SortKey.DURATION) }
        binding.sortModified.setOnClickListener { selectKey(F020Engine.SortKey.MODIFIED) }
        binding.btnDirection.setOnClickListener { toggleDirection() }

        render()
    }

    // region 内置示例数据

    private fun buildFolders(): List<F020Engine.Folder> = listOf(
        F020Engine.Folder("4K 电影合集", 128, 276_480_000L, 1_700_000_000_000L),
        F020Engine.Folder("纪录片 第9季", 24, 86_400_000L, 1_700_500_000_000L),
        F020Engine.Folder("纪录片 第10季", 30, 108_000_000L, 1_702_000_000_000L),
        F020Engine.Folder("国产剧 精编", 42, 151_200_000L, 1_698_000_000_000L),
        F020Engine.Folder("动画 剧场版", 18, 129_600_000L, 1_704_000_000_000L),
        F020Engine.Folder("演唱会 Live", 9, 32_400_000L, 1_706_000_000_000L)
    )

    // endregion

    // region 选择与持久化

    private fun selectKey(key: F020Engine.SortKey) {
        if (sortKey == key) {
            render()
            return
        }
        sortKey = key
        persist()
        render()
        toast(getString(R.string.f020_toast_switched, keyLabel(), directionLabel()))
    }

    private fun toggleDirection() {
        ascending = !ascending
        persist()
        render()
        toast(getString(R.string.f020_toast_switched, keyLabel(), directionLabel()))
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_SORT_KEY, sortKey.name)
            .putBoolean(KEY_ASCENDING, ascending)
            .apply()
    }

    // endregion

    // region 渲染

    private fun render() {
        val sorted = F020Engine.sort(folders, sortKey, ascending)

        binding.resultRows.removeAllViews()
        val density = resources.displayMetrics.density
        val padV = (density * 10).toInt()
        val padH = (density * 6).toInt()
        sorted.forEachIndexed { index, folder ->
            val row = TextView(this).apply {
                text = getString(
                    R.string.f020_row_line,
                    index + 1,
                    folder.name,
                    folder.videoCount,
                    formatDuration(folder.totalDurationMs),
                    formatModified(folder.modifiedMs)
                )
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))
                setPadding(padH, padV, padH, padV)
            }
            binding.resultRows.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val keyText = keyLabel()
        val dirText = directionLabel()
        binding.resultSummary.text =
            getString(R.string.f020_result_summary, sorted.size, keyText, dirText)
        binding.sortCurrent.text = getString(R.string.f020_sort_current, keyText, dirText)
        binding.btnDirection.text = getString(
            if (ascending) R.string.f020_btn_to_desc else R.string.f020_btn_to_asc
        )
        updateKeyButtons()
    }

    private fun updateKeyButtons() {
        val primary = ContextCompat.getColor(this, R.color.theme_primary)
        val normal = ContextCompat.getColor(this, R.color.theme_text_primary)
        val buttons = listOf(
            F020Engine.SortKey.NAME to binding.sortName,
            F020Engine.SortKey.COUNT to binding.sortCount,
            F020Engine.SortKey.DURATION to binding.sortDuration,
            F020Engine.SortKey.MODIFIED to binding.sortModified
        )
        for ((key, button) in buttons) {
            val selected = key == sortKey
            button.setTextColor(if (selected) primary else normal)
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        binding.btnDirection.setTextColor(primary)
    }

    private fun keyLabel(): String = getString(
        when (sortKey) {
            F020Engine.SortKey.NAME -> R.string.f020_key_name
            F020Engine.SortKey.COUNT -> R.string.f020_key_count
            F020Engine.SortKey.DURATION -> R.string.f020_key_duration
            F020Engine.SortKey.MODIFIED -> R.string.f020_key_modified
        }
    )

    private fun directionLabel(): String =
        getString(if (ascending) R.string.f020_dir_asc else R.string.f020_dir_desc)

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) getString(R.string.f020_duration_hm, hours, minutes)
        else getString(R.string.f020_duration_m, minutes)
    }

    private fun formatModified(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 持久化文件名，也是本次选择落盘的唯一入口。 */
        const val PREFS_NAME = "f020_sort"
        const val KEY_SORT_KEY = "sort_key"
        const val KEY_ASCENDING = "sort_ascending"
    }
}
