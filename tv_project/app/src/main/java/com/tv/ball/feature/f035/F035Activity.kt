package com.tv.ball.feature.f035

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF035Binding

/**
 * F035 M3U 播放列表导入导出页面。
 *
 * 两块内容：
 * 1. 导出 —— 拿内置的 4 条示例队列走一遍 [F035Engine.export]，把生成的 M3U 文本原样贴出来，
 *    用户能看到每一行长什么样，再一键复制到剪贴板；不写文件、不碰存储权限。
 * 2. 导入 —— 输入框里粘贴外来 M3U 文本（电视上遥控器敲字麻烦，主要靠「读取剪贴板」按钮），
 *    解析后按「成功 N 条 / 跳过 M 行」汇报，并列出前几条结果，好让人一眼判断解析对不对。
 *
 * 格式细节全在 [F035Engine]（纯 Kotlin），这里只做取值、建行和刷文案。
 */
class F035Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF035Binding
    private lateinit var engine: F035Engine

    /** 内置示例队列：凑齐「有确定时长」与「时长未知（直播）」两种导出写法。 */
    private lateinit var sample: List<F035Engine.Entry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF035Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F035Engine()
        sample = buildSample()

        // 输入框限高，内容超出靠自身滚动；遥控器上下键在输入框里默认只会挪光标，
        // 所以显式把上下键让给焦点切换，不然焦点会被输入框吞掉、走不到下面那排按钮。
        binding.parseInput.movementMethod = ScrollingMovementMethod.getInstance()
        binding.parseInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    binding.btnPaste.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    binding.btnCopy.requestFocus()
                    true
                }
                else -> false
            }
        }

        binding.btnExport.setOnClickListener { renderExport() }
        binding.btnCopy.setOnClickListener { copyExport() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnParse.setOnClickListener { parseInput() }
        binding.btnClear.setOnClickListener { clearInput() }

        linkFocus()
        renderExport()
        renderParse(null)
    }

    // region 导出

    /** 示例队列：三条网络源（含一条直播推流）+ 一条本地片源，覆盖导出时的两种时长写法。 */
    private fun buildSample(): List<F035Engine.Entry> = listOf(
        F035Engine.Entry(
            title = getString(R.string.f035_sample_1_title),
            durationSec = 1425,
            location = "https://media.example.tv/live/cctv5.m3u8"
        ),
        F035Engine.Entry(
            title = getString(R.string.f035_sample_2_title),
            durationSec = 2580,
            location = "https://media.example.tv/vod/demo-movie.mp4"
        ),
        F035Engine.Entry(
            title = getString(R.string.f035_sample_3_title),
            durationSec = F035Engine.UNKNOWN_DURATION,
            location = "rtmp://pull.example.tv/live/match"
        ),
        F035Engine.Entry(
            title = getString(R.string.f035_sample_4_title),
            durationSec = 3720,
            location = "/storage/emulated/0/Movies/offline-match.mkv"
        )
    )

    /** 生成导出文本并贴到页面；顺带写统计文案，免得用户自己去数行。 */
    private fun renderExport() {
        val text = engine.export(sample)
        binding.exportText.text = text
        binding.exportStat.text = getString(
            R.string.f035_export_stat,
            sample.size,
            text.lines().count { it.isNotBlank() }
        )
    }

    private fun copyExport() {
        val text = binding.exportText.text?.toString().orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.f035_toast_nothing_to_copy))
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(getString(R.string.f035_toast_clipboard_unavailable))
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        toast(getString(R.string.f035_toast_copied, sample.size))
    }

    // endregion

    // region 导入

    /** 读剪贴板直接灌进输入框并立刻解析：电视上没法手打 M3U，这一步就是主要的导入入口。 */
    private fun pasteFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = manager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.f035_toast_clipboard_empty))
            return
        }
        binding.parseInput.setText(text)
        binding.parseInput.setSelection(text.length)
        parseInput()
    }

    private fun clearInput() {
        binding.parseInput.setText("")
        renderParse(null)
    }

    /**
     * 解析输入框内容。
     *
     * 解析是纯字符串处理（几十 KB 级别），主线程跑完即可；不抛异常、不联网、不落盘。
     * 结果一次汇总成「成功/跳过」，再挑前几条把标题与时长列出来。
     */
    private fun parseInput() {
        val text = binding.parseInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            renderParse(null)
            toast(getString(R.string.f035_toast_input_empty))
            return
        }
        val outcome = engine.parse(text)
        renderParse(outcome)
        if (outcome.entries.isEmpty()) {
            toast(outcome.error ?: getString(R.string.f035_parse_no_address))
        } else {
            toast(getString(R.string.f035_toast_parsed, outcome.entries.size, outcome.skipped))
        }
    }

    /** 刷解析区：统计行 + 前几条明细；outcome 为 null 表示还没解析过。 */
    private fun renderParse(outcome: F035Engine.ParseOutcome?) {
        val container = binding.parseResult
        container.removeAllViews()

        if (outcome == null) {
            binding.parseStat.text = getString(R.string.f035_parse_idle)
            return
        }
        if (outcome.entries.isEmpty()) {
            binding.parseStat.text = outcome.error ?: getString(R.string.f035_parse_no_address)
            return
        }

        binding.parseStat.text = getString(
            R.string.f035_parse_stat,
            outcome.entries.size,
            outcome.skipped
        )

        val primary = ContextCompat.getColor(this, R.color.theme_primary)
        val normal = ContextCompat.getColor(this, R.color.theme_text_primary)
        val secondary = ContextCompat.getColor(this, R.color.theme_text_secondary)
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)

        outcome.entries.take(MAX_PREVIEW).forEachIndexed { index, entry ->
            val row = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                textSize = 13f
                setTextColor(if (index == 0) primary else normal)
                setTypeface(null, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
                text = getString(
                    R.string.f035_parse_row,
                    index + 1,
                    entry.title.ifEmpty { getString(R.string.f035_row_untitled) },
                    durationText(entry.durationSec),
                    entry.location
                )
            }
            container.addView(row)
        }

        if (outcome.entries.size > MAX_PREVIEW) {
            container.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
                textSize = 12f
                setTextColor(secondary)
                text = getString(R.string.f035_parse_more, outcome.entries.size - MAX_PREVIEW)
            })
        }
    }

    /** -1 显示成「未知时长」，其余按 分:秒，超过一小时补小时位。 */
    private fun durationText(seconds: Int): String {
        if (seconds < 0) return getString(R.string.f035_duration_unknown)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    // endregion

    /**
     * 遥控器焦点串联：导出 → 复制 → 输入框 → 读取剪贴板 → 解析 → 清空。
     * 输入框的上下键在 onCreate 里单独接管，这里只管其余按钮，避免下键跨卡片乱跳。
     */
    private fun linkFocus() {
        binding.btnExport.nextFocusDownId = binding.btnCopy.id
        binding.btnCopy.nextFocusUpId = binding.btnExport.id
        binding.btnCopy.nextFocusDownId = binding.parseInput.id
        binding.parseInput.nextFocusUpId = binding.btnCopy.id
        binding.parseInput.nextFocusDownId = binding.btnPaste.id
        binding.btnPaste.nextFocusUpId = binding.parseInput.id
        binding.btnPaste.nextFocusDownId = binding.btnParse.id
        binding.btnParse.nextFocusUpId = binding.btnPaste.id
        binding.btnParse.nextFocusDownId = binding.btnClear.id
        binding.btnClear.nextFocusUpId = binding.btnParse.id
        binding.btnExport.requestFocus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 解析结果最多列这么多条，其余用一句话带过。 */
        const val MAX_PREVIEW = 8

        /** 剪贴板条目标签，方便在系统剪贴板管理里认出来源。 */
        const val CLIP_LABEL = "M3U"
    }
}