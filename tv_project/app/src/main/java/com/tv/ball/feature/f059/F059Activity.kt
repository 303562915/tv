package com.tv.ball.feature.f059

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF059Binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * F059「请求头配置」页面。
 *
 * 三块内容：
 * 1. 规则列表：每条一行，显示域名模式 + 实际会带出去的头，右侧开关直接启停；
 *    点行编辑，长按删除。
 * 2. 命中预览：贴一条播放地址，立刻告诉你命中哪条规则、实际会发哪些头 ——
 *    改规则前先在这儿验证，省得播不出来再回来猜。
 * 3. 底部说明：匹配按最长域名优先，同长度按列表顺序。
 *
 * 匹配与持久化都在 [F059Engine]，这里只做渲染和对话框。
 */
class F059Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF059Binding

    private val engine: F059Engine by lazy { F059Engine(this) }

    /** 当前规则表；改动后整体回写引擎并落盘。 */
    private val rules = mutableListOf<HeaderRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF059Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAdd.setOnClickListener { showEditDialog(null) }

        binding.inputUrl.nextFocusDownId = binding.btnAdd.id
        binding.inputUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderPreview()
        })

        loadRules()
    }

    /** 读盘 -> 渲染。读失败当空表，页面照常可用。 */
    private fun loadRules() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { engine.load() }.getOrDefault(emptyList())
            }
            rules.clear()
            rules.addAll(loaded)
            renderRules()
            renderPreview()
            binding.statusValue.text = getString(R.string.f059_status_loaded, rules.size)
        }
    }

    /** 整表落盘；写失败只影响下次启动，不打断当前操作。 */
    private fun persist() {
        val snapshot = rules.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { engine.save(snapshot) }
        }
    }

    // ---------------------------------------------------------------- 列表

    private fun renderRules() {
        binding.listContainer.removeAllViews()
        val empty = rules.isEmpty()
        binding.listEmpty.visibility = if (empty) View.VISIBLE else View.GONE

        rules.forEach { rule ->
            binding.listContainer.addView(buildRow(rule))
        }
    }

    /** 一行规则：域名 + 头摘要 + 启用开关。 */
    private fun buildRow(rule: HeaderRule): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            background = ContextCompat.getDrawable(this@F059Activity, R.drawable.bg_mode_row)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        val headLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val domain = TextView(this).apply {
            text = rule.domainPattern
            textSize = 15f
            setTextColor(color(R.color.theme_text_primary))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggle = MaterialSwitch(this).apply {
            isChecked = rule.enabled
            contentDescription = getString(R.string.f059_switch_desc, rule.domainPattern)
            setOnCheckedChangeListener { _, checked ->
                if (checked == rule.enabled) return@setOnCheckedChangeListener
                val next = engine.setEnabled(rule.pattern, checked, rules.toList())
                rules.clear()
                rules.addAll(next)
                persist()
                renderRules()
                renderPreview()
            }
        }

        headLine.addView(domain)
        headLine.addView(toggle)

        val detail = TextView(this).apply {
            text = summarize(rule)
            textSize = 12f
            setTextColor(color(if (rule.enabled) R.color.theme_text_secondary else R.color.theme_disabled))
            setPadding(0, dp(4), 0, 0)
            maxLines = 3
        }

        row.addView(headLine)
        row.addView(detail)

        row.setOnClickListener { showEditDialog(rule) }
        row.setOnLongClickListener {
            confirmDelete(rule)
            true
        }
        return row
    }

    /** 头摘要：UA / Referer 各一段，停用追加提示。 */
    private fun summarize(rule: HeaderRule): String {
        val parts = mutableListOf<String>()
        if (rule.hasUserAgent) parts.add(getString(R.string.f059_row_ua, rule.userAgent))
        if (rule.hasReferer) parts.add(getString(R.string.f059_row_referer, rule.referer))
        if (parts.isEmpty()) parts.add(getString(R.string.f059_row_headers_none))
        if (!rule.enabled) parts.add(getString(R.string.f059_row_disabled))
        return parts.joinToString(" · ")
    }

    private fun confirmDelete(rule: HeaderRule) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.f059_delete_title)
            .setMessage(getString(R.string.f059_delete_message, rule.domainPattern))
            .setNegativeButton(R.string.f059_cancel, null)
            .setPositiveButton(R.string.f059_delete_confirm) { _, _ ->
                val next = engine.remove(rule.pattern, rules.toList())
                rules.clear()
                rules.addAll(next)
                persist()
                renderRules()
                renderPreview()
                binding.statusValue.text = getString(R.string.f059_status_loaded, rules.size)
            }
            .show()
    }

    // ---------------------------------------------------------------- 预览

    /** 输入地址 -> 命中规则 + 实际请求头。 */
    private fun renderPreview() {
        val raw = binding.inputUrl.text?.toString().orEmpty().trim()
        val preview = engine.preview(raw, rules.toList())

        when {
            raw.isEmpty() -> {
                binding.previewRule.text = getString(R.string.f059_preview_idle)
                binding.previewRule.setTextColor(color(R.color.theme_text_secondary))
                binding.previewHeaders.text = getString(R.string.f059_preview_no_headers)
            }

            !preview.valid -> {
                binding.previewRule.text = getString(R.string.f059_preview_no_host)
                binding.previewRule.setTextColor(color(R.color.theme_warning))
                binding.previewHeaders.text = getString(R.string.f059_preview_no_headers)
            }

            preview.rule == null -> {
                binding.previewRule.text = getString(R.string.f059_preview_miss, preview.host)
                binding.previewRule.setTextColor(color(R.color.theme_warning))
                binding.previewHeaders.text = getString(R.string.f059_preview_no_headers)
            }

            else -> {
                binding.previewRule.text = getString(
                    R.string.f059_preview_hit,
                    preview.host,
                    preview.rule.pattern
                )
                binding.previewRule.setTextColor(color(R.color.theme_success))
                binding.previewHeaders.text = if (preview.headers.isEmpty()) {
                    getString(R.string.f059_preview_no_headers)
                } else {
                    preview.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 对话框

    /** 新增（[existing] 为 null）或编辑一条规则。 */
    private fun showEditDialog(existing: HeaderRule?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }

        val inputDomain = dialogInput(
            getString(R.string.f059_dialog_domain_hint),
            existing?.domainPattern.orEmpty()
        )
        val inputUa = dialogInput(
            getString(R.string.f059_dialog_ua_hint),
            existing?.userAgent.orEmpty()
        )
        val inputReferer = dialogInput(
            getString(R.string.f059_dialog_referer_hint),
            existing?.referer.orEmpty()
        )
        box.addView(inputDomain)
        box.addView(inputUa)
        box.addView(inputReferer)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) R.string.f059_dialog_new_title else R.string.f059_dialog_edit_title
            )
            .setView(box)
            .setNegativeButton(R.string.f059_cancel, null)
            .setPositiveButton(R.string.f059_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val domain = inputDomain.text?.toString().orEmpty().trim()
                if (domain.isEmpty()) {
                    inputDomain.error = getString(R.string.f059_dialog_domain_required)
                    return@setOnClickListener
                }
                val pattern = HeaderRule.normalize(domain)
                if (pattern.isEmpty()) {
                    inputDomain.error = getString(R.string.f059_dialog_domain_invalid)
                    return@setOnClickListener
                }
                val draft = HeaderRule(
                    domainPattern = domain,
                    userAgent = inputUa.text?.toString().orEmpty().trim(),
                    referer = inputReferer.text?.toString().orEmpty().trim(),
                    enabled = existing?.enabled ?: true
                )
                if (draft.isEmpty) {
                    inputReferer.error = getString(R.string.f059_dialog_headers_required)
                    return@setOnClickListener
                }
                val previous = existing?.pattern.orEmpty()
                if (rules.any { it.pattern == pattern && it.pattern != previous }) {
                    inputDomain.error = getString(R.string.f059_dialog_duplicate)
                    return@setOnClickListener
                }

                val next = if (existing == null) {
                    engine.add(draft, rules.toList())
                } else {
                    engine.update(previous, draft, rules.toList())
                }
                rules.clear()
                rules.addAll(next)
                persist()
                renderRules()
                renderPreview()
                binding.statusValue.text = getString(R.string.f059_status_loaded, rules.size)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun dialogInput(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
        setSingleLine(true)
        setTextColor(color(R.color.theme_text_primary))
        setHintTextColor(color(R.color.theme_text_secondary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}