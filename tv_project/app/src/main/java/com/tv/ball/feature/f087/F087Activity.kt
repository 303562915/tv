package com.tv.ball.feature.f087

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF087Binding
import com.tv.ball.util.CustomToast

/**
 * 「无障碍描述审计」页。
 *
 * 三段：
 * 1. **查什么**：把判据写在页面上（可点可聚焦 → 必须有描述或文本），省得用户猜清单是按什么筛的；
 * 2. **演示样本**：四个真实页面的通病——两个纯图标、一个只有背景的空文字按钮、一个没写描述的开关。
 *    它们故意不写 `contentDescription`，就是这个页面的被审计对象；
 * 3. **扫描 / 一键补全**：点「扫描本页」列清单（控件类型 · id · 建议文案 · 严重度），
 *    点「一键补全全部建议」真的把 `contentDescription` 写进控件，然后**立刻重扫**——
 *    清单清空就是补全生效的证据，问题数不降说明还有控件被漏判，届时清单会如实留着。
 *
 * 扫描只针对本页这棵 View 树（[F087Engine.audit] 传的是 `binding.root`），不读别的页面、
 * 不改全局无障碍设置：它是自查工具，验收方式就是补完之后开着 TalkBack 用遥控器走一遍。
 *
 * 补全是运行时写控件属性，不落盘：重新进页面布局重新 inflate，描述会回到初始状态，
 * 可以反复演示。要在真实页面里长期生效，把建议文案抄回布局/代码即可。
 */
class F087Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF087Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF087Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 演示控件点击：借着点击解释一句，用户不会以为这四个控件坏了。
        val demoClick = View.OnClickListener {
            CustomToast.show(this, getString(R.string.f087_demo_toast))
        }
        binding.f087IconSearch.setOnClickListener(demoClick)
        binding.f087IconShare.setOnClickListener(demoClick)
        binding.f087ActionConfirm.setOnClickListener(demoClick)
        binding.f087DemoSwitch.setOnClickListener(demoClick)

        binding.f087ScanButton.setOnClickListener { scanPage() }
        binding.f087FixAllButton.setOnClickListener { fixAll() }

        // 进页面先扫一遍：不用先点按钮就知道这页现在有几处念不出来。
        render(F087Engine.audit(binding.root), fixedCount = null)
    }

    // ===== 动作 =====

    /** 扫描本页：列清单，不碰任何控件。 */
    private fun scanPage() {
        val issues = F087Engine.audit(binding.root)
        render(issues, fixedCount = null)
        CustomToast.show(
            this,
            if (issues.isEmpty()) {
                getString(R.string.f087_toast_scan_empty)
            } else {
                getString(R.string.f087_toast_scan_found, issues.size)
            }
        )
    }

    /**
     * 一键补全：把建议文案写进每个缺描述的控件，然后立刻重扫。
     *
     * 重扫用同一个 [F087Engine.audit]，不是「把 before 的条数减掉已补数」这种自己算的账——
     * 只有重扫结果才是真的：问题数应减少到 0；若还有剩余，说明有控件补完之后仍判不过，
     * 清单会原样留着，不会被"看起来成功了"骗过去。
     */
    private fun fixAll() {
        val before = F087Engine.audit(binding.root)
        if (before.isEmpty()) {
            render(before, fixedCount = null)
            CustomToast.show(this, getString(R.string.f087_fix_none))
            return
        }

        val fixed = F087Engine.applyAll(binding.root)
        val after = F087Engine.audit(binding.root)
        render(after, fixedCount = fixed)
        CustomToast.show(this, getString(R.string.f087_fix_done, fixed, tailFor(after)))
    }

    // ===== 渲染 =====

    /**
     * 渲染清单。
     *
     * @param issues 扫描结果（补全之后传重扫结果，所以这里永远是当下的真实状态）。
     * @param fixedCount 补全处数；非 null 时在底部补一行「上一次补全」回执，null 表示这次只扫描。
     */
    private fun render(issues: List<F087Engine.Issue>, fixedCount: Int?) {
        binding.f087SummaryText.text = F087Engine.summary(issues)
        binding.f087SummaryText.setTextColor(
            ContextCompat.getColor(
                this,
                if (issues.isEmpty()) R.color.theme_success else R.color.theme_error
            )
        )

        binding.f087IssueList.removeAllViews()
        issues.forEachIndexed { index, issue ->
            binding.f087IssueList.addView(buildRow(index + 1, issues.size, issue))
        }

        // 清单空着的时候给一句人话，比留一片空白强。
        binding.f087EmptyHint.visibility = if (issues.isEmpty()) View.VISIBLE else View.GONE

        if (fixedCount != null) {
            binding.f087LastFixText.visibility = View.VISIBLE
            binding.f087LastFixText.text =
                getString(R.string.f087_last_fix, fixedCount, tailFor(issues))
        }
    }

    /**
     * 一行问题：`2/4  ImageButton · f087IconShare` + 建议描述 + 严重度。
     *
     * 行是运行时 new 出来的 `TextView`，不额外占一个布局文件；它自带文本、不可点，
     * 所以不会反过来变成被审计的对象——审计引擎对「有文本的 TextView」本来就不报。
     * 颜色按严重度走 theme_error / theme_warning / theme_text_secondary，
     * 一眼能分出"整页念不出来"和"只是不够规范"。
     */
    private fun buildRow(index: Int, total: Int, issue: F087Engine.Issue): TextView {
        val row = TextView(this)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        row.setBackgroundResource(R.drawable.bg_dialog_input)
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        row.typeface = Typeface.MONOSPACE
        row.textSize = 13f
        row.setTextColor(
            ContextCompat.getColor(
                this,
                when (issue.severity) {
                    F087Engine.Severity.HIGH -> R.color.theme_error
                    F087Engine.Severity.MEDIUM -> R.color.theme_warning
                    F087Engine.Severity.LOW -> R.color.theme_text_secondary
                }
            )
        )
        row.text = buildString {
            append(getString(R.string.f087_issue_index, index, total))
            append("  ")
            append(getString(R.string.f087_issue_head, issue.viewClassName, issue.idName))
            append('\n')
            append(getString(R.string.f087_issue_suggest, issue.suggestedDesc))
            append('\n')
            append(getString(R.string.f087_issue_severity, issue.severity.label))
            if (issue.tag.isNotBlank()) {
                append('\n')
                append(getString(R.string.f087_issue_tag, issue.tag))
            }
        }
        return row
    }

    /** 补全收尾那句话：问题清零还是仍有剩余。 */
    private fun tailFor(issues: List<F087Engine.Issue>): String = getString(
        if (issues.isEmpty()) R.string.f087_fix_done_clean else R.string.f087_fix_done_left
    )

    /** dp → px，运行时 new 出来的控件没有 XML 可写尺寸，只能自己换算。 */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}