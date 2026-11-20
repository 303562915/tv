package com.tv.ball.feature.f015

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF015Binding
import com.tv.ball.util.CustomToast
import java.nio.charset.Charset
import kotlin.math.roundToInt

/**
 * 「字幕编码探测」页。
 *
 * 三段内容：样本文本（输入框 + 三个预设示例）、探测结果（编码 / 来源 / 可信度 / 尝试顺序 /
 * 候选编码切换）、解码预览（前 10 行）。
 *
 * 数据流刻意做得很直白：字节进 [F015Engine.detect] 拿结果，界面上的"探测编码"就是引擎结论；
 * 用户点候选编码则把 [manualCharset] 置上，用同一份字节重新解码——覆盖只影响展示，不改样本本身。
 *
 * 乱码处理按验收要求走两条口：引擎可信度低于 [F015Engine.MIN_CONFIDENCE]，或者解出来的正文
 * 本身 [F015Engine.looksGarbled]，界面只给"疑似乱码"提示，不把乱码正文铺到屏幕上。
 *
 * 电视没有触摸屏：候选编码按钮、示例按钮、回到自动探测都是 focusable 的行/块，
 * 焦点顺序在 XML 里用 nextFocus* 串成 输入框 → 示例 → 候选编码 → 自动探测。
 */
class F015Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF015Binding

    private val engine = F015Engine()

    /** 当前装进来的预设样本下标；[NO_SAMPLE] 表示字节直接从文本框内容推导。 */
    private var sampleIndex: Int = NO_SAMPLE

    private val sample: Sample? get() = SAMPLES.getOrNull(sampleIndex)

    /** 人工覆盖的编码；null 表示用自动探测结果。 */
    private var manualCharset: String? = null

    private val candidateButtons = LinkedHashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF015Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 恢复：样本索引与人工覆盖都要跟着回来，否则文本框里的 GBK 正文会被当 UTF-8 重新探测
        manualCharset = savedInstanceState?.getString(KEY_MANUAL)
        sampleIndex = savedInstanceState?.getInt(KEY_SAMPLE, NO_SAMPLE) ?: NO_SAMPLE

        buildCandidateButtons()

        binding.f015SampleUtf8.setOnClickListener { loadSample(0) }
        binding.f015SampleGbk.setOnClickListener { loadSample(1) }
        binding.f015SampleUtf16.setOnClickListener { loadSample(2) }
        binding.f015AutoButton.setOnClickListener {
            manualCharset = null
            detectAndRender()
            CustomToast.show(this, getString(R.string.f015_back_to_auto))
        }

        binding.f015Input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                // 文本被改成别的才丢弃样本字节；setText 装入样本原文时不丢
                if (sampleIndex != NO_SAMPLE && s?.toString() != sample?.text) {
                    sampleIndex = NO_SAMPLE
                }
                detectAndRender()
            }
        })

        detectAndRender()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SAMPLE, sampleIndex)
        manualCharset?.let { outState.putString(KEY_MANUAL, it) }
    }

    // ===== 样本 =====

    /** 装入第 [index] 个预设样本：示例按钮提供的是"已知编码的真实字节"，用来验探测是否准。 */
    private fun loadSample(index: Int) {
        val target = SAMPLES.getOrNull(index) ?: return
        manualCharset = null
        // 先记下标再 setText：文本框内容与样本原文一致，TextWatcher 不会把它清掉
        sampleIndex = index
        binding.f015Input.setText(target.text)
        detectAndRender()
    }

    /**
     * 当前参与探测的字节。
     *
     * 有样本就直接用样本字节；没有则从文本框内容推导：现场最常见的输入是从别处拷来的乱码字符串
     * （GBK 字幕被当 Latin-1 显示），每个字符都在 0..255 内，按低 8 位取回就是原始字节；
     * 真出现超出范围的字符（用户直接敲中文）才按 UTF-8 取字节——那也是这类文本实际的存储编码。
     */
    private fun resolveBytes(): ByteArray {
        sample?.let { return it.toBytes() }
        val text = binding.f015Input.text?.toString().orEmpty()
        if (text.isEmpty()) return ByteArray(0)
        return if (text.all { it.code <= 0xFF }) {
            ByteArray(text.length) { i -> text[i].code.toByte() }
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
    }

    // ===== 渲染 =====

    private fun detectAndRender() {
        val bytes = resolveBytes()
        binding.f015SampleSize.text = getString(R.string.f015_sample_size, bytes.size)
        if (bytes.isEmpty()) {
            renderEmpty()
            return
        }

        val detection = engine.detect(bytes)
        val active = manualCharset ?: detection.charset
        renderDetection(detection, active, bytes)

        val text = engine.decode(bytes, active)
        val garbled = engine.looksGarbled(text) ||
            (manualCharset == null && !detection.reliable)
        when {
            garbled -> renderHint(
                getString(R.string.f015_garbled_hint, active),
                R.color.theme_error
            )
            text.trim().isEmpty() -> renderHint(
                getString(R.string.f015_empty_hint),
                R.color.theme_text_secondary
            )
            else -> renderPreview(text)
        }
    }

    private fun renderDetection(detection: Detection, active: String, bytes: ByteArray) {
        binding.f015ResultCharset.text = active
        binding.f015ResultSource.text = when {
            manualCharset != null -> getString(R.string.f015_source_manual)
            engine.sniffBom(bytes) != null -> getString(R.string.f015_source_bom)
            else -> getString(R.string.f015_source_auto)
        }
        binding.f015ResultConfidence.text = if (manualCharset != null) {
            // 人工覆盖时不参与打分，继续显示引擎分数会让人误以为是这个编码的可信度
            getString(R.string.f015_confidence_manual)
        } else {
            getString(
                R.string.f015_confidence_value,
                (detection.confidence * 100f).roundToInt()
            )
        }
        binding.f015ResultTried.text = if (detection.triedOrder.isEmpty()) {
            getString(R.string.f015_tried_none)
        } else {
            getString(
                R.string.f015_tried_value,
                detection.triedOrder.joinToString(SEPARATOR)
            )
        }
        updateCandidateSelection(active)
    }

    private fun renderPreview(text: String) {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        binding.f015GarbledHint.visibility = View.GONE
        binding.f015Preview.visibility = View.VISIBLE
        binding.f015Preview.text = lines.take(PREVIEW_LINE_LIMIT).joinToString("\n")
        binding.f015PreviewMore.text =
            if (lines.size > PREVIEW_LINE_LIMIT) {
                getString(R.string.f015_preview_more, lines.size, PREVIEW_LINE_LIMIT)
            } else {
                getString(R.string.f015_preview_lines, lines.size)
            }
    }

    private fun renderHint(message: CharSequence, colorRes: Int) {
        binding.f015Preview.visibility = View.GONE
        binding.f015PreviewMore.text = ""
        binding.f015GarbledHint.visibility = View.VISIBLE
        binding.f015GarbledHint.text = message
        binding.f015GarbledHint.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun renderEmpty() {
        binding.f015ResultCharset.text = getString(R.string.f015_value_none)
        binding.f015ResultSource.text = getString(R.string.f015_value_none)
        binding.f015ResultConfidence.text = getString(R.string.f015_value_none)
        binding.f015ResultTried.text = getString(R.string.f015_tried_none)
        updateCandidateSelection(null)
        renderHint(getString(R.string.f015_empty_hint), R.color.theme_text_secondary)
    }

    // ===== 候选编码 =====

    private fun buildCandidateButtons() {
        binding.f015CandidateRow.removeAllViews()
        F015Engine.CANDIDATES.forEachIndexed { index, name ->
            val button = TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_card_surface)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(dp(8), dp(14), dp(8), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (index > 0) marginStart = dp(8)
                }
                setOnClickListener { overrideTo(name) }
            }
            binding.f015CandidateRow.addView(button)
            candidateButtons[name] = button
        }
    }

    /** 人工覆盖：同一个字节数组换编码重新解码，探测结论保持不变（只是不再被采用）。 */
    private fun overrideTo(charset: String) {
        manualCharset = charset
        detectAndRender()
        CustomToast.show(this, getString(R.string.f015_override_done, charset))
    }

    private fun updateCandidateSelection(active: String?) {
        candidateButtons.forEach { (name, button) ->
            val selected = name == active
            button.text = if (selected) getString(R.string.f015_candidate_active, name) else name
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.theme_primary else R.color.theme_text_primary
                )
            )
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    /** 预设示例：文本 + 它"应该"以哪种编码存盘 + 是否带 BOM。 */
    private data class Sample(val text: String, val charsetName: String, val withBom: Boolean) {
        fun toBytes(): ByteArray {
            val charset = try {
                Charset.forName(charsetName)
            } catch (e: IllegalArgumentException) {
                Charsets.UTF_8
            }
            val body = text.toByteArray(charset)
            return if (withBom) BOM_UTF16LE + body else body
        }
    }

    companion object {
        private const val KEY_SAMPLE = "f015_sample"
        private const val KEY_MANUAL = "f015_manual"
        private const val NO_SAMPLE = -1
        private const val PREVIEW_LINE_LIMIT = 10
        private const val SEPARATOR = " → "

        private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

        private const val TEXT_SAMPLE_UTF8 = "第一行 字幕编码探测\n第二行 中文字幕样例\n第三行 纯 UTF-8 不带 BOM"
        private const val TEXT_SAMPLE_GBK = "第一行 字幕編碼偵測\n第二行 繁體中文樣例\n第三行 這種檔案常常是 GBK 或 Big5"
        private const val TEXT_SAMPLE_UTF16 = "第一行 字幕编码探测\n第二行 UTF-16 小端带 BOM\n第三行 BOM 优先于打分"

        private val SAMPLES = listOf(
            Sample(TEXT_SAMPLE_UTF8, "UTF-8", false),
            Sample(TEXT_SAMPLE_GBK, "GBK", false),
            Sample(TEXT_SAMPLE_UTF16, "UTF-16LE", true)
        )
    }
}