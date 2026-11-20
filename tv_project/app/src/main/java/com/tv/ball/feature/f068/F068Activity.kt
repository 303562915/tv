package com.tv.ball.feature.f068

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF068Binding
import com.tv.ball.util.CustomToast

/**
 * 「MIME 映射与响应头」页。
 *
 * 这一页是 [F068Engine] 的**活样例**：填一个文件名（或裸扩展名）、按一下"解析"，
 * 就同时给出三件事——该发什么 Content-Type、整组响应头长什么样、浏览器到底能不能直接播。
 *
 * 页面本身不含任何判断逻辑：扩展名归一化、MIME 查表、响应头拼装、可播判定全部走引擎，
 * 页面只做两件事——**把输入喂给引擎**、**把引擎的输出翻成资源**。
 * 之所以坚持这样，是因为这三件事必须永远对得上：曾经出现过"类型表里加了 webm、
 * 但可播白名单没加"的情况，用户看到 MIME 正确却提示"只能下载"，对着两个都对的值无从判断是谁错了。
 *
 * 演示路径覆盖三种典型：默认进来是能直接播的 mp4；快捷按钮里混着能播的（webm/mp3）
 * 和看着像能播其实只能下载的（mkv/ts/flv/mov）；"未知扩展名演示"按钮专演回落
 * `application/octet-stream`——这是本功能最容易被忽略、也最容易上线的那个分支。
 */
class F068Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF068Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF068Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 快捷按钮按引擎里的表生成：表加了扩展名，按钮自动多一个，不会出现两处不同步
        buildShortcuts()

        binding.f068BtnProbe.setOnClickListener { probe(binding.f068Input.text?.toString().orEmpty()) }

        // 遥控器/软键盘上按"完成"等价于点"解析"，省一次焦点移动
        binding.f068Input.setOnEditorActionListener { _, actionId, _ ->
            val submitted = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
            if (submitted) probe(binding.f068Input.text?.toString().orEmpty())
            submitted
        }

        binding.f068BtnUnknown.setOnClickListener { demoUnknown() }

        // 初始样例：能播的 mp4 + 一个具体大小，让第一次进来就能看到完整的五条头
        binding.f068Input.setText(SAMPLE_PLAYABLE)
        binding.f068SizeInput.setText(SAMPLE_SIZE_BYTES.toString())
        probe(SAMPLE_PLAYABLE)

        binding.f068Input.requestFocus()
    }

    // ===== 输入区 =====

    /**
     * 快捷扩展名按钮。
     *
     * 用代码生成而不是在 XML 里写十几个 Button：按钮与 [F068Engine.QUICK_EXTENSIONS] 一一对应，
     * 将来表里调顺序、删一个，这里不用跟着改。容器是横向 ScrollView，
     * 遥控器左右键能把整排走完。
     */
    private fun buildShortcuts() {
        val row = binding.f068Chips
        row.removeAllViews()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val chipHeight = dp(40f)
        val chipPadding = dp(14f)

        F068Engine.QUICK_EXTENSIONS.forEach { ext ->
            val chip = TextView(this).apply {
                text = ext
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(color(R.color.theme_text_primary))
                setBackgroundResource(R.drawable.bg_secondary_button)
                setPadding(chipPadding, 0, chipPadding, 0)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    chipHeight
                ).apply { marginEnd = gap }

                // 焦点态用主题色，跟功能中心其它按钮一致；没这个的话遥控器上完全看不出焦点在哪
                setOnFocusChangeListener { view, hasFocus ->
                    (view as TextView).setTextColor(
                        color(if (hasFocus) R.color.theme_primary else R.color.theme_text_primary)
                    )
                }

                setOnClickListener { pick(ext) }
            }
            row.addView(chip)
        }
    }

    /** 点快捷按钮：填回输入框再走同一条解析路径，保证"按钮"和"手输"结果一定一致。 */
    private fun pick(ext: String) {
        binding.f068Input.setText(ext)
        binding.f068Input.setSelection(binding.f068Input.text?.length ?: 0)
        probe(ext)
    }

    /**
     * 未知扩展名演示。
     *
     * 用 `.rmvb`：它曾经很常见，用户很可能真的把这种文件拖进服务端；
     * 而它恰好不在表里，正好演"回落 octet-stream → 浏览器只下载"这条最容易被忽略的分支。
     */
    private fun demoUnknown() {
        val sample = SAMPLE_UNKNOWN
        binding.f068Input.setText(sample)
        binding.f068Input.setSelection(binding.f068Input.text?.length ?: 0)
        probe(sample)
        CustomToast.show(this, getString(R.string.f068_toast_fallback, F068Engine.FALLBACK_MIME))
    }

    // ===== 解析与渲染 =====

    /**
     * 一次解析：读输入 → 问引擎 → 写控件。
     *
     * 大小输入留空或乱填时传 [F068Engine.SIZE_UNKNOWN]，这时响应头里**没有** Content-Length，
     * 页面也要明确说一句"大小未知"，否则用户会以为头拼少了是 bug。
     */
    private fun probe(raw: String) {
        val ext = F068Engine.normalizeExt(raw)
        val sizeText = binding.f068SizeInput.text?.toString()?.trim().orEmpty()
        val sizeBytes = sizeText.toLongOrNull()?.takeIf { it >= 0L } ?: F068Engine.SIZE_UNKNOWN

        val known = F068Engine.isKnown(ext)
        val mime = F068Engine.mimeOf(ext)
        val playback = F068Engine.playbackOf(ext)

        renderExt(ext, known)
        renderMime(mime, known)
        renderPlayback(playback)
        renderSize(sizeBytes)
        renderHeaders(ext, sizeBytes)
        renderNote(known)
    }

    /** 归一化后的扩展名；空输入单独说一句，避免展示成"扩展名是空串"这种看着像出错的样子。 */
    private fun renderExt(ext: String, known: Boolean) {
        binding.f068Ext.text = if (ext.isEmpty()) {
            getString(R.string.f068_ext_none)
        } else {
            getString(
                R.string.f068_ext_value,
                ext,
                getString(if (known) R.string.f068_ext_known else R.string.f068_ext_fallback)
            )
        }
        binding.f068Ext.setTextColor(
            color(if (known) R.color.theme_text_secondary else R.color.theme_warning)
        )
    }

    /** MIME 本体：未知时染成警示色，因为 octet-stream 是"没判断出来"，不是"判断成二进制"。 */
    private fun renderMime(mime: String, known: Boolean) {
        binding.f068Mime.text = mime
        binding.f068Mime.setTextColor(color(if (known) R.color.theme_primary else R.color.theme_warning))
    }

    /** 浏览器归宿：一句结论 + 一句为什么。 */
    private fun renderPlayback(playback: BrowserPlayback) {
        binding.f068Playable.text = getString(playbackLabelRes(playback))
        binding.f068Playable.setTextColor(
            color(
                when (playback) {
                    BrowserPlayback.INLINE -> R.color.theme_success
                    BrowserPlayback.PLAYLIST -> R.color.theme_info
                    BrowserPlayback.SUBTITLE -> R.color.theme_text_primary
                    BrowserPlayback.DOWNLOAD -> R.color.theme_warning
                }
            )
        )
        binding.f068PlaybackNote.text = getString(playbackNoteRes(playback))
    }

    /** 大小：人能读的值 + 它有没有被写进 Content-Length。 */
    private fun renderSize(sizeBytes: Long) {
        binding.f068SizeInfo.text = getString(
            R.string.f068_size_info,
            F068Engine.formatSize(sizeBytes),
            getString(
                if (sizeBytes >= 0L) R.string.f068_size_counted else R.string.f068_size_missing
            )
        )
    }

    /**
     * 完整响应头。
     *
     * 直接渲染引擎给的有序 Map 文本，一行一条：用户拿它跟真实 HEAD 响应逐行对着看，
     * 比读一段解释更快定位少的是哪条。文本可长按选中，方便贴进工单。
     */
    private fun renderHeaders(ext: String, sizeBytes: Long) {
        binding.f068Headers.text = F068Engine.renderHeaders(ext, sizeBytes)
    }

    /** 底部一句说明：命中还是回落，两句话不一样，颜色也不一样。 */
    private fun renderNote(known: Boolean) {
        binding.f068Note.text = getString(
            if (known) R.string.f068_note_known else R.string.f068_note_fallback
        )
        binding.f068Note.setTextColor(
            color(if (known) R.color.theme_text_secondary else R.color.theme_warning)
        )
    }

    // ===== 资源映射（引擎枚举 → R.string） =====

    private fun playbackLabelRes(playback: BrowserPlayback): Int = when (playback) {
        BrowserPlayback.INLINE -> R.string.f068_playback_inline
        BrowserPlayback.PLAYLIST -> R.string.f068_playback_playlist
        BrowserPlayback.SUBTITLE -> R.string.f068_playback_subtitle
        BrowserPlayback.DOWNLOAD -> R.string.f068_playback_download
    }

    private fun playbackNoteRes(playback: BrowserPlayback): Int = when (playback) {
        BrowserPlayback.INLINE -> R.string.f068_playback_inline_note
        BrowserPlayback.PLAYLIST -> R.string.f068_playback_playlist_note
        BrowserPlayback.SUBTITLE -> R.string.f068_playback_subtitle_note
        BrowserPlayback.DOWNLOAD -> R.string.f068_playback_download_note
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 初始样例：mp4 是三种结果里"全部正常"的那一种，适合当默认视图。 */
        const val SAMPLE_PLAYABLE = "电影.2001.2160p.mp4"

        /** 未知扩展名样例（RealMedia，已淘汰但用户仍会传）。 */
        const val SAMPLE_UNKNOWN = "老片子.rmvb"

        /** 700 MB：一个具体的、有量级感的点播文件大小，让 Content-Length 看着不像占位。 */
        const val SAMPLE_SIZE_BYTES = 734_003_200L
    }
}
