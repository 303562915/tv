package com.tv.ball.feature.f093

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF093Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「数据库迁移校验」页。
 *
 * 三段：库信息（文件路径 + user_version）、差异清单（表 / 列 / 类型，一行一条）、
 * 建议修复 SQL（可整段复制出去照着执行）。「导出报告」把这三段落成一份 txt。
 *
 * 页面只有一份状态：最近一次 [F093Engine.scan] 的结果。列表是按它重建的，
 * 不做局部替换——校验的语义就是「当前库此刻长什么样」，局部增量最容易留下过期行。
 *
 * 打开就自动扫一次：用户进这一页通常是因为刚升级完、应用起不来，
 * 多按一次「校验」只是多一道门槛。之后的重扫一律走按钮。
 *
 * 扫库要开文件、读 sqlite_master，放到 IO 线程；[job] 保证连点按钮时只有最后一次结果落地。
 */
class F093Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF093Binding

    /** 当前在跑的扫描；连点「校验」时先取消上一条，避免旧结果覆盖新结果。 */
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF093Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f093VerifyButton.setOnClickListener { runScan() }
        binding.f093ExportButton.setOnClickListener { exportReport() }

        // 库还没建时也要告诉用户「它本该在哪」，所以路径先行、结果后到
        binding.f093PathValue.text = F093Engine.displayPath(this)
            .ifEmpty { getString(R.string.f093_path_unknown) }
        binding.f093FixSql.text = getString(R.string.f093_sql_placeholder)

        runScan()
        // 焦点先落「校验」：遥控器按一下就能重扫
        binding.f093VerifyButton.requestFocus()
    }

    // ===== 校验 =====

    /**
     * 扫一次库并重画。
     *
     * 取消旧 job 而不是排队：上一轮若卡在开库（比如库被别的进程锁着），
     * 排队会让用户连按几次后看到一串迟到的一样的结果。
     */
    private fun runScan() {
        job?.cancel()
        binding.f093Status.text = getString(R.string.f093_status_checking)
        binding.f093Status.setTextColor(color(R.color.theme_text_secondary))
        binding.f093IssueList.removeAllViews()
        binding.f093FixSql.text = getString(R.string.f093_sql_placeholder)

        job = lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { F093Engine.scan(this@F093Activity) }
            if (isFinishing || isDestroyed) return@launch
            render(report)
        }
    }

    /**
     * 把一次结果铺到页面上。
     *
     * 版本行与状态行分开表达：版本是事实（库自报的 user_version），状态是判断
     * （一致 / 有几处差异 / 库未创建 / 读不出来）。混成一行会在「版本对但缺列」时自相矛盾。
     */
    private fun render(report: Report) {
        binding.f093PathValue.text = report.path.ifEmpty { getString(R.string.f093_path_unknown) }

        binding.f093VersionValue.text = when (report.state) {
            State.OK -> getString(
                R.string.f093_version_value,
                report.dbVersion,
                report.expectedVersion
            )
            State.ABSENT -> getString(R.string.f093_db_missing)
            State.ERROR -> getString(R.string.f093_db_error, report.error)
        }

        binding.f093Status.text = when (report.state) {
            State.ABSENT -> getString(R.string.f093_status_absent)
            State.ERROR -> getString(R.string.f093_status_error, report.error)
            State.OK -> if (report.issues.isEmpty()) {
                getString(R.string.f093_status_ok, report.tableCount, report.columnCount)
            } else {
                getString(
                    R.string.f093_status_issues,
                    report.issues.size,
                    report.tableCount,
                    report.columnCount
                )
            }
        }
        binding.f093Status.setTextColor(
            color(
                when {
                    report.state != State.OK -> R.color.theme_warning
                    report.issues.isEmpty() -> R.color.theme_success
                    else -> R.color.theme_error
                }
            )
        )

        renderIssues(report)
        renderFixSql(report)
    }

    /** 差异清单：一行一条，下面挂一行「期望 / 实际 / 原因」和一行可直接抄走的 SQL。 */
    private fun renderIssues(report: Report) {
        val container = binding.f093IssueList
        container.removeAllViews()

        if (report.issues.isEmpty()) {
            container.addView(
                label(
                    getString(
                        if (report.state == State.OK) {
                            R.string.f093_issue_none
                        } else {
                            R.string.f093_issue_skip
                        }
                    ),
                    R.color.theme_text_secondary,
                    SIZE_CAPTION
                )
            )
            return
        }

        report.issues.forEach { container.addView(issueView(it)) }
    }

    private fun issueView(issue: Issue): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val head = label(
            getString(
                R.string.f093_issue_row,
                F093Engine.tableLabel(this, issue.table),
                F093Engine.columnLabel(this, issue.column),
                F093Engine.kindLabel(this, issue.kind)
            ),
            if (issue.kind == Kind.MISSING) R.color.theme_error else R.color.theme_warning,
            SIZE_BODY
        )
        head.setTypeface(head.typeface, Typeface.BOLD)

        val detail = label(
            getString(
                R.string.f093_issue_detail,
                issue.expected.ifBlank { getString(R.string.f093_report_na) },
                issue.actual.ifBlank { getString(R.string.f093_report_na) },
                F093Engine.detailLabel(this, issue.detail)
            ),
            R.color.theme_text_secondary,
            SIZE_CAPTION
        )

        val sql = label(
            getString(R.string.f093_issue_sql, firstLine(F093Engine.fixSql(issue))),
            R.color.theme_text_secondary,
            SIZE_CODE
        )
        sql.typeface = Typeface.MONOSPACE

        row.addView(head)
        row.addView(detail)
        row.addView(sql)
        return row
    }

    /**
     * 建议 SQL 汇总块。
     *
     * 全部差异都拼进来，只对**条数**设上限：一段整表重建脚本上百行，几十条一起铺会把这块
     * 变成一屏滚不完的墙。超出的部分在导出报告里有，页面这里给一句「还有 N 条」。
     */
    private fun renderFixSql(report: Report) {
        if (report.issues.isEmpty()) {
            binding.f093FixSql.text = getString(R.string.f093_report_empty)
            return
        }

        val shown = report.issues.take(MAX_SQL_ISSUES)
        val sb = StringBuilder()
        shown.forEach { issue ->
            sb.append("-- ").append(
                getString(
                    R.string.f093_issue_row,
                    F093Engine.tableLabel(this, issue.table),
                    F093Engine.columnLabel(this, issue.column),
                    F093Engine.kindLabel(this, issue.kind)
                )
            ).append('\n')
            sb.append(F093Engine.fixSql(issue)).append("\n\n")
        }
        val rest = report.issues.size - shown.size
        if (rest > 0) sb.append(getString(R.string.f093_issue_more, rest)).append('\n')

        binding.f093FixSql.text = sb.toString().trimEnd()
    }

    // ===== 导出 =====

    /**
     * 导出报告：重新扫一遍再落盘，不拿页面上这份旧结果去写文件。
     *
     * 用户按导出时想要的通常是「现在这份库的状态」，而不是「几分钟前那次扫描的状态」。
     * 扫描与写文件都在 IO 线程，路径由 Toast 给出。
     */
    private fun exportReport() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val text = F093Engine.exportText(this@F093Activity)
                F093Engine.writeReport(this@F093Activity, text)
            }
            if (isFinishing || isDestroyed) return@launch
            CustomToast.show(
                this@F093Activity,
                if (file == null) {
                    getString(R.string.f093_export_failed)
                } else {
                    getString(R.string.f093_export_ok, file.absolutePath)
                }
            )
        }
    }

    // ===== 小工具 =====

    /** 动态行里的一段文字；[sizeSp] 直接是 sp 值（TextView.textSize 的单位就是 sp）。 */
    private fun label(text: String, colorRes: Int, sizeSp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(colorRes))
        textSize = sizeSp
    }

    /** 只取一行 SQL 做列表预览：跳过注释行，取第一条语句。 */
    private fun firstLine(sql: String): String {
        val line = sql.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
            ?: return sql.take(PREVIEW_LIMIT)
        return if (line.length > PREVIEW_LIMIT) line.take(PREVIEW_LIMIT - 1) + "…" else line
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 建议 SQL 块最多铺几条差异；其余在导出报告里。 */
        const val MAX_SQL_ISSUES = 6

        /** 单行 SQL 预览的字数上限。 */
        const val PREVIEW_LIMIT = 160

        /** 动态行的字高（sp）：正文与 XML 里的 TextAppearance.TVPlayer.Body 同档。 */
        const val SIZE_BODY = 16f

        /** 说明行，与 Caption 同档。 */
        const val SIZE_CAPTION = 13f

        /** SQL 预览：比说明行再小一点，等宽字体下也读得清。 */
        const val SIZE_CODE = 12f
    }
}