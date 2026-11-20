package com.tv.ball.feature.f014

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF014Binding
import com.tv.ball.util.CustomToast

/**
 * 「同名字幕自动匹配」页。
 *
 * 这页是 [F014Engine] 的可操作台：左边填视频名、右边维护一份模拟目录的文件列表，
 * 点「匹配」就按同目录 + 同名的规则算一遍，结果卡显示选中的那一条，
 * 候选排序表把整条优先级链（完全同名 → zh-Hans → zh-Hant → en → 其它）连同置信度摊开，
 * 一个候选都没有就只换空态文案 —— 和播放页一样静默跳过，不弹错误。
 *
 * 目录列表是纯内存字符串，不碰真实文件系统：真实播放页只要把 `File(video).parentFile.list()`
 * 的结果换进来，匹配逻辑完全一致。
 *
 * 电视没有触摸屏：输入框、按钮、目录里的每一行都能用遥控器走到并确认，
 * 目录行确认 = 移除该文件，焦点会交还给列表第一行，不会掉回页面顶部。
 */
class F014Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF014Binding

    private val engine = F014Engine()

    /** 模拟的目录内容，一行一个文件名（可以带相对路径，例如 Subs/movie.zh.srt）。 */
    private val directoryFiles = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF014Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.f014AddButton.setOnClickListener { addTypedFile() }
        binding.f014MatchButton.setOnClickListener { runMatch() }
        binding.f014ResetButton.setOnClickListener {
            loadDemoDirectory()
            runMatch()
        }
        // 输入框按遥控器确认键 = 收起键盘并立刻算一次 / 加入一行
        binding.f014VideoInput.setOnEditorActionListener { _, _, _ ->
            runMatch()
            true
        }
        binding.f014FileInput.setOnEditorActionListener { _, _, _ ->
            addTypedFile()
            true
        }

        loadDemoDirectory()
        runMatch()

        // 焦点落在「匹配」上：一进页面确认键就能算一遍，不用先进输入框触发键盘
        binding.f014MatchButton.requestFocus()
    }

    // ------------------------------------------------------------------ 目录

    /** 回到示例目录：movie.mp4 旁边摆着四种能被命中的字幕，外加一个无关文件。 */
    private fun loadDemoDirectory() {
        directoryFiles.clear()
        directoryFiles.addAll(DEMO_FILES)
        binding.f014VideoInput.setText(DEMO_VIDEO)
        binding.f014VideoInput.setSelection(binding.f014VideoInput.text.length)
        renderDirectory()
    }

    private fun addTypedFile() {
        val name = binding.f014FileInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast(getString(R.string.f014_input_blank))
            binding.f014FileInput.requestFocus()
            return
        }
        if (directoryFiles.any { it.equals(name, ignoreCase = true) }) {
            toast(getString(R.string.f014_added_duplicate, name))
            return
        }
        directoryFiles.add(name)
        binding.f014FileInput.setText("")
        renderDirectory()
        toast(getString(R.string.f014_added, name))
        binding.f014FileInput.requestFocus()
    }

    private fun removeFile(name: String) {
        if (!directoryFiles.remove(name)) return
        renderDirectory()
        runMatch()
        toast(getString(R.string.f014_removed, name))
        // 被点掉的那一行已经没了，焦点交回列表第一行，遥控器不会掉到页面顶部
        if (binding.f014FileList.childCount > 0) {
            binding.f014FileList.getChildAt(0).requestFocus()
        } else {
            binding.f014FileInput.requestFocus()
        }
    }

    private fun renderDirectory() {
        binding.f014FileList.removeAllViews()
        for (name in directoryFiles) {
            binding.f014FileList.addView(buildFileRow(name))
        }
        binding.f014FileEmpty.visibility = if (directoryFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ 匹配

    private fun runMatch() {
        val video = binding.f014VideoInput.text?.toString()?.trim().orEmpty()
        if (video.isEmpty()) {
            showEmpty(getString(R.string.f014_empty_video_blank))
            renderCandidates(emptyList())
            return
        }

        val candidates = engine.candidates(video, directoryFiles.toList())
        val best = candidates.firstOrNull()
        if (best == null) {
            // 无匹配就静默跳过：只换空态文案，不弹错误提示
            showEmpty(getString(R.string.f014_empty_no_match, video))
            renderCandidates(emptyList())
            return
        }

        showResult(best)
        renderCandidates(candidates)
    }

    private fun showResult(hit: MatchResult) {
        binding.f014EmptyState.visibility = View.GONE
        binding.f014ResultBody.visibility = View.VISIBLE
        binding.f014ResultPath.text = hit.subtitlePath
        binding.f014ResultLanguage.text =
            getString(R.string.f014_result_language_value, languageLabel(hit.language))
        binding.f014ResultConfidence.text =
            getString(R.string.f014_confidence_value, hit.confidence)
    }

    private fun showEmpty(message: String) {
        binding.f014ResultBody.visibility = View.GONE
        binding.f014EmptyState.visibility = View.VISIBLE
        binding.f014EmptyState.text = message
    }

    private fun renderCandidates(candidates: List<MatchResult>) {
        binding.f014CandidateTable.removeAllViews()
        binding.f014CandidateEmpty.visibility =
            if (candidates.isEmpty()) View.VISIBLE else View.GONE
        candidates.forEachIndexed { index, hit ->
            binding.f014CandidateTable.addView(buildCandidateRow(index + 1, hit, index == 0))
        }
    }

    /** 未标注语言说的就是「文件名完全一样」，单独给一条更好读的文案。 */
    private fun languageLabel(language: String): String =
        if (language.isEmpty()) {
            getString(R.string.f014_language_untagged)
        } else {
            getString(R.string.f014_language_tagged, F014Language.labelOf(language), language)
        }

    // ------------------------------------------------------------------ 行

    private fun buildFileRow(name: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            background = ContextCompat.getDrawable(this@F014Activity, R.drawable.bg_card_surface)
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = name
            textSize = 15f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(ContextCompat.getColor(this@F014Activity, R.color.theme_text_primary))
        }

        val action = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = getString(R.string.f014_file_remove)
            textSize = 12f
            setPadding(dp(8), 0, 0, 0)
            setTextColor(ContextCompat.getColor(this@F014Activity, R.color.theme_text_secondary))
        }

        row.addView(label)
        row.addView(action)
        row.contentDescription = getString(R.string.f014_file_row_desc, name)
        row.setOnClickListener { removeFile(name) }
        return row
    }

    private fun buildCandidateRow(rank: Int, hit: MatchResult, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            background = ContextCompat.getDrawable(this@F014Activity, R.drawable.bg_card_surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val rankText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
            text = getString(R.string.f014_candidate_rank, rank)
            textSize = 13f
            setTextColor(
                ContextCompat.getColor(
                    this@F014Activity,
                    if (selected) R.color.theme_primary else R.color.theme_text_secondary
                )
            )
        }

        val nameText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = hit.subtitlePath
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(ContextCompat.getColor(this@F014Activity, R.color.theme_text_primary))
        }

        val detail = getString(
            R.string.f014_candidate_detail,
            languageLabel(hit.language),
            hit.confidence
        )
        val detailText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = if (selected) getString(R.string.f014_candidate_selected, detail) else detail
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(
                    this@F014Activity,
                    if (selected) R.color.theme_primary else R.color.theme_text_secondary
                )
            )
        }

        row.addView(rankText)
        row.addView(nameText)
        row.addView(detailText)
        return row
    }

    // ------------------------------------------------------------------ 工具

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        CustomToast.show(this, message)
    }

    private companion object {
        /** 示例视频名。 */
        const val DEMO_VIDEO = "movie.mp4"

        /**
         * 示例目录：四种命名都能被 movie.mp4 命中（完全同名 / zh / chs / 拷贝序号），
         * poster.jpg 用来证明无关文件不会被当成字幕。
         */
        val DEMO_FILES: List<String> = listOf(
            "movie.mp4",
            "movie.srt",
            "movie.zh.srt",
            "movie.en.srt",
            "movie(1).srt",
            "poster.jpg"
        )
    }
}
