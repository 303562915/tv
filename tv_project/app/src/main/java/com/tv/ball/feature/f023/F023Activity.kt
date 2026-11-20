package com.tv.ball.feature.f023

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF023Binding

/**
 * F023 忽略目录与扩展名规则页面。
 *
 * 三块内容：
 * 1. 目录规则列表：输入前缀 → 添加；点某条末尾的「删除」移除。命中的子目录整棵跳过。
 * 2. 扩展名列表：同上，比较时只看最后一段文件名。
 * 3. 测试区：粘一个路径进来，当场告诉你是否被忽略、命中哪条规则。
 *
 * 所有增删都立即落盘（[F023Engine.save]），列表与测试结果同步刷新。
 */
class F023Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF023Binding
    private var rules: RuleSet = RuleSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF023Binding.inflate(layoutInflater)
        setContentView(binding.root)

        rules = F023Engine.load(this)

        binding.btnAddDir.setOnClickListener { addDir() }
        binding.btnDirDefault.setOnClickListener { restoreDefaultDirs() }
        binding.inlineAddDir.setOnEditorActionHandler { addDir() }
        binding.btnAddExt.setOnClickListener { addExt() }

        binding.btnTest.setOnClickListener { testPath() }
        binding.inlineTestPath.setOnEditorActionHandler { testPath() }

        renderRules()
        clearTestResult()
    }

    // region 目录规则

    private fun addDir() {
        val input = binding.inlineAddDir.text.toString()
        val normalized = RuleSet.normalizeDir(input)
        if (normalized == null) {
            toast(getString(R.string.f023_toast_bad_dir))
            return
        }
        if (rules.dirPrefixes.any { RuleSet.normalizeDir(it)?.equals(normalized, ignoreCase = true) == true }) {
            toast(getString(R.string.f023_toast_duplicate))
            return
        }
        rules = F023Engine.addDir(rules, normalized)
        persist()
        binding.inlineAddDir.setText("")
        toast(getString(R.string.f023_toast_dir_added, normalized))
    }

    private fun removeDir(prefix: String) {
        rules = F023Engine.removeDir(rules, prefix)
        persist()
        toast(getString(R.string.f023_toast_dir_removed, prefix))
    }

    private fun restoreDefaultDirs() {
        rules = rules.withDefaultDirs()
        persist()
        toast(getString(R.string.f023_toast_default_restored))
    }

    // endregion

    // region 扩展名规则

    private fun addExt() {
        val input = binding.inlineAddExt.text.toString()
        val normalized = RuleSet.normalizeExt(input)
        if (normalized == null) {
            toast(getString(R.string.f023_toast_bad_ext))
            return
        }
        if (rules.extBlacklist.any { RuleSet.normalizeExt(it) == normalized }) {
            toast(getString(R.string.f023_toast_duplicate))
            return
        }
        rules = F023Engine.addExt(rules, normalized)
        persist()
        binding.inlineAddExt.setText("")
        toast(getString(R.string.f023_toast_ext_added, normalized))
    }

    private fun removeExt(ext: String) {
        rules = F023Engine.removeExt(rules, ext)
        persist()
        toast(getString(R.string.f023_toast_ext_removed, ext))
    }

    // endregion

    // region 渲染

    private fun renderRules() {
        renderDirRules()
        renderExtRules()
        if (currentQuery().isNotEmpty()) testPath(quiet = true)
    }

    private fun renderDirRules() {
        binding.dirRows.removeAllViews()
        if (rules.dirPrefixes.isEmpty()) {
            binding.dirRows.addView(makeEmptyRow(getString(R.string.f023_empty_dir)))
        } else {
            rules.dirPrefixes.forEach { prefix ->
                binding.dirRows.addView(
                    makeRuleRow(
                        text = prefix,
                        badge = getString(R.string.f023_badge_dir),
                        onRemove = { removeDir(prefix) }
                    )
                )
            }
        }
        binding.dirCount.text = getString(R.string.f023_dir_count, rules.dirPrefixes.size)
    }

    private fun renderExtRules() {
        binding.extRows.removeAllViews()
        if (rules.extBlacklist.isEmpty()) {
            binding.extRows.addView(makeEmptyRow(getString(R.string.f023_empty_ext)))
        } else {
            rules.extBlacklist
                .sortedBy { it.lowercase() }
                .forEach { ext ->
                    binding.extRows.addView(
                        makeRuleRow(
                            text = ext,
                            badge = getString(R.string.f023_badge_ext),
                            onRemove = { removeExt(ext) }
                        )
                    )
                }
        }
        binding.extCount.text = getString(R.string.f023_ext_count, rules.extBlacklist.size)
    }

    private fun makeRuleRow(text: String, badge: String, onRemove: () -> Unit): View {
        val density = resources.displayMetrics.density
        val padH = (density * 12).toInt()
        val padV = (density * 8).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Color.parseColor("#14FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (density * 6).toInt() }
        }

        val badgeView = TextView(this).apply {
            this.text = badge
            textSize = 11f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding((density * 6).toInt(), 0, (density * 6).toInt(), 0)
        }

        val valueView = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setSingleLine(true)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (density * 6).toInt() }
        }

        val removeView = TextView(this).apply {
            this.text = getString(R.string.f023_action_remove)
            textSize = 13f
            setTextColor(Color.parseColor("#FF8A80"))
            setPadding((density * 10).toInt(), (density * 2).toInt(), 0, (density * 2).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onRemove() }
        }

        row.addView(badgeView)
        row.addView(valueView)
        row.addView(removeView)
        return row
    }

    private fun makeEmptyRow(text: String): View {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, (density * 6).toInt(), 0, (density * 6).toInt())
        }
    }

    // endregion

    // region 测试区

    private fun currentQuery(): String = binding.inlineTestPath.text.toString().trim()

    private fun testPath(quiet: Boolean = false) {
        val path = currentQuery()
        if (path.isEmpty()) {
            if (!quiet) toast(getString(R.string.f023_toast_need_path))
            clearTestResult()
            return
        }

        val decision = F023Engine.explain(rules, path)
        binding.testVerdict.text = getString(
            if (decision.skipped) R.string.f023_verdict_skip else R.string.f023_verdict_keep
        )
        binding.testVerdict.setTextColor(
            Color.parseColor(if (decision.skipped) "#FFFFB74D" else "#FF81C784")
        )

        val ruleText = when {
            decision.dirRule != null -> getString(R.string.f023_rule_dir_hit, decision.dirRule)
            decision.extRule != null -> getString(R.string.f023_rule_ext_hit, decision.extRule)
            else -> getString(R.string.f023_rule_none)
        }
        binding.testRule.text = ruleText
        binding.testPathEcho.text = getString(R.string.f023_test_path_echo, path)
        binding.testAdvice.text = getString(
            if (decision.skipped) R.string.f023_advice_skip else R.string.f023_advice_keep
        )
    }

    private fun clearTestResult() {
        binding.testVerdict.text = getString(R.string.f023_verdict_idle)
        binding.testVerdict.setTextColor(Color.parseColor("#B3FFFFFF"))
        binding.testRule.text = getString(R.string.f023_rule_none)
        binding.testPathEcho.text = getString(R.string.f023_test_path_hint)
        binding.testAdvice.text = getString(R.string.f023_advice_idle)
    }

    // endregion

    // region 工具

    private fun persist() {
        F023Engine.save(this, rules)
        renderRules()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 输入框回车直接提交，省得每次都去够按钮。 */
    private inline fun EditText.setOnEditorActionHandler(crossinline action: () -> Unit) {
        inputType = InputType.TYPE_CLASS_TEXT
        setOnEditorActionListener { _, _, _ ->
            action()
            true
        }
    }

    // endregion
}