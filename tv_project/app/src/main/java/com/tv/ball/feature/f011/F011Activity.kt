package com.tv.ball.feature.f011

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.ball.databinding.ActivityF011Binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外挂字幕加载：选文件 → 读文本 → 解析 → 显示结果或失败原因。
 *
 * 解析本身在 [F011SubtitleParser]（纯函数，可单测）；这里只负责 IO 与渲染，
 * 读文件走 IO 线程，主线程只做 UI，避免大字幕把电视卡住。
 */
class F011Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF011Binding

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        loadSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityF011Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPick.setOnClickListener {
            pickFile.launch(arrayOf("text/*", "application/octet-stream"))
        }
        binding.btnPick.requestFocus()
        binding.tvSummary.text = getString(f011SummaryEmpty())
    }

    private fun f011SummaryEmpty(): Int = com.tv.ball.R.string.f011_summary_empty

    private fun loadSubtitle(uri: Uri) {
        binding.tvFile.text = getString(com.tv.ball.R.string.f011_file_selected, uri.lastPathSegment ?: uri.toString())
        binding.tvError.visibility = View.GONE
        binding.tvPreviewEmpty.visibility = View.GONE
        binding.previewBox.removeAllViews()
        showStatus(getString(com.tv.ball.R.string.f011_reading))

        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { runCatching { readText(uri) } }
            binding.tvSummary.text = getString(f011SummaryEmpty())
            val text = raw.getOrNull()
            if (text == null) {
                showError(getString(com.tv.ball.R.string.f011_error_io, raw.exceptionOrNull()?.message ?: "未知"))
                return@launch
            }
            val result = withContext(Dispatchers.Default) { F011SubtitleParser.parse(text) }
            render(result)
        }
    }

    private fun readText(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: error("打不开这个文件")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun render(result: F011SubtitleParser.Result) {
        val err = result.error
        if (err != null) {
            val msg = when (err) {
                F011SubtitleParser.Error.EMPTY -> getString(com.tv.ball.R.string.f011_error_empty)
                F011SubtitleParser.Error.BAD_TIMECODE ->
                    getString(com.tv.ball.R.string.f011_error_timecode, err.detailLine)
                F011SubtitleParser.Error.NO_CUES -> getString(com.tv.ball.R.string.f011_error_no_cues)
            }
            showError(msg)
            showStatus(getString(f011SummaryEmpty()))
            return
        }
        binding.tvError.visibility = View.GONE
        binding.tvPreviewEmpty.visibility = View.GONE
        binding.previewBox.removeAllViews()
        val first = result.cues.first()
        val last = result.cues.last()
        binding.tvSummary.text = getString(
            com.tv.ball.R.string.f011_summary,
            result.format.name,
            result.cues.size,
            F011SubtitleParser.format(first.startMs),
            F011SubtitleParser.format(last.endMs)
        )
        result.cues.take(8).forEach { cue ->
            val row = TextView(this).apply {
                text = getString(
                    com.tv.ball.R.string.f011_preview_line,
                    F011SubtitleParser.format(cue.startMs),
                    F011SubtitleParser.format(cue.endMs),
                    cue.text.replace('\n', ' ')
                )
                setTextColor(getColor(com.tv.ball.R.color.theme_text_primary))
                textSize = 13f
                setPadding(0, dp(3), 0, dp(3))
            }
            binding.previewBox.addView(row)
        }
    }

    private fun showStatus(text: String) {
        binding.tvSummary.text = text
    }

    private fun showError(text: String) {
        binding.tvError.text = text
        binding.tvError.visibility = View.VISIBLE
        extractErrorTail(text)
    }

    /** 预留：把失败原因同时塞进无障碍播报，遥控器用户也能听到。 */
    private fun extractErrorTail(text: String) {
        binding.tvError.contentDescription = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
