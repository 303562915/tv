package com.tv.ball.feature.f085

import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF085Binding
import com.tv.ball.util.CustomToast
import java.io.File
import kotlin.math.roundToInt

/**
 * 「崩溃捕获与本地报告」页。
 *
 * 三段：捕获开关 / 最近一次 / 历史报告。
 *
 * 三个数据流：
 *   1. 开关 → [F085Engine.install] / [F085Engine.uninstall]。install 拿到的旧 handler 记在
 *      [previousHandler] 里，关掉时原样交回去；只改默认 handler，不动任何崩溃行为本身。
 *   2. 历史列表 → [F085Engine.listReports]（最新在前），每行「查看全文」用 [F085Engine.readReport]
 *      读入一个可滚动的对话框；「删除」只删这一条（[F085Engine.deleteReport]）。
 *   3. 「制造一次测试异常」→ try/catch 里手动抛、手动接住，再调 [F085Engine.saveReport]。
 *      **这是和真崩溃的唯一区别**：真崩溃走 [F085Engine.CrashHandler.uncaughtException]，
 *      写完盘要把异常交回系统（进程结束、弹「应用已停止」）；这里接住了就不交，进程继续活着。
 *
 * 开关状态不持久化：handler 挂在进程上，进程重来时系统已经恢复过默认值，
 * 页面每次 [onCreate] 直接读 [F085Engine.isInstalled] 这个真实状态，不另存一份可能与实际不一致的偏好。
 */
class F085Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF085Binding

    /** 被本页接管的那个旧 handler；关开关时原样交回去，链不断。 */
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF085Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f085CaptureToggle.setOnClickListener { toggleCapture() }
        binding.f085TestButton.setOnClickListener { makeTestCrash() }
        binding.f085ClearAllButton.setOnClickListener { confirmClearAll() }

        // 进页面先对齐真实状态：可能是上一次在这个页面里开的，也可能进程重启后被系统复位了
        if (F085Engine.isInstalled()) {
            previousHandler = F085Engine.install(context = applicationContext)
        }
        renderSwitch()

        setStatus(getString(R.string.f085_status_idle), R.color.theme_text_secondary)
        renderReports()
    }

    // ===== 开关 =====

    /**
     * 切换捕获。
     *
     * 关的时候把 [previousHandler] 交回去——若这个页面从来没装过（比如别处装的），
     * 就拿当前值兜底，保证不会把默认 handler 设成 null 而让系统退回到"直接杀进程、不打印"。
     */
    private fun toggleCapture() {
        if (F085Engine.isInstalled()) {
            F085Engine.uninstall(previousHandler ?: Thread.getDefaultUncaughtExceptionHandler())
            previousHandler = null
            setStatus(getString(R.string.f085_status_uninstalled), R.color.theme_text_secondary)
        } else {
            previousHandler = F085Engine.install(context = applicationContext)
            setStatus(getString(R.string.f085_status_installed), R.color.theme_success)
        }
        renderSwitch()
    }

    /** 开关行的右侧状态文字与颜色。 */
    private fun renderSwitch() {
        val on = F085Engine.isInstalled()
        binding.f085CaptureState.text =
            getString(if (on) R.string.f085_state_on else R.string.f085_state_off)
        binding.f085CaptureState.setTextColor(
            color(if (on) R.color.theme_success else R.color.theme_text_secondary)
        )
    }

    // ===== 测试异常 =====

    /**
     * 制造一次测试异常。
     *
     * 用 try/catch **手动**接住：抛出点就在下面，异常栈里能看到的调用链是
     * makeTestCrash → saveReport，和真崩溃那种"从系统 dispatch 进来"的栈不同。
     * 接住之后进程继续跑，页面不会退，用户能立刻在下面的列表里看到新报告。
     * 真崩溃路径见 [F085Engine.CrashHandler]：写盘完成后一定要把异常交回系统。
     */
    private fun makeTestCrash() {
        val saved = try {
            throw IllegalStateException(getString(R.string.f085_test_exception_message))
        } catch (e: IllegalStateException) {
            F085Engine.saveReport(this, e, F085Engine.TAG_MANUAL)
        }

        if (saved == null) {
            setStatus(getString(R.string.f085_toast_test_failed), R.color.theme_error)
            CustomToast.show(this, getString(R.string.f085_toast_test_failed))
            return
        }
        setStatus(getString(R.string.f085_status_test_saved, saved.name), R.color.theme_tertiary)
        CustomToast.show(this, getString(R.string.f085_toast_test_saved, saved.name))
        renderReports()
    }

    // ===== 列表 =====

    /** 重新读一遍报告目录：条数 / 总占用 / 最近一次摘要 / 逐行列表。 */
    private fun renderReports() {
        val reports = F085Engine.listReports(this)

        binding.f085SummaryValue.text = F085Engine.lastCrashSummary(this)
            ?: getString(R.string.f085_summary_empty)
        binding.f085CountValue.text = if (reports.isEmpty()) {
            getString(R.string.f085_count_empty)
        } else {
            getString(
                R.string.f085_count,
                reports.size,
                F085Engine.formatSize(F085Engine.totalSize(this))
            )
        }

        binding.f085List.removeAllViews()
        if (reports.isEmpty()) {
            binding.f085List.addView(emptyHint())
            setClearAllEnabled(false)
            return
        }
        setClearAllEnabled(true)
        reports.forEach { addReportRow(it) }
    }

    /** 空列表时的一行说明，避免用户以为页面没渲染出来。 */
    private fun emptyHint(): TextView = TextView(this).apply {
        text = getString(R.string.f085_history_empty)
        setTextColor(color(R.color.theme_text_secondary))
        textSize = 13f
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    /**
     * 一行报告：左边（时间 · 大小）与摘要，右边「删除」。
     *
     * 整行可聚焦、确认键就是「查看全文」；「删除」是行内另一个可聚焦元素，
     * 遥控器在同一行上再按一次右键落到它上面，避免"误按一下就把报告删了"。
     */
    private fun addReportRow(report: CrashReport) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@F085Activity, R.drawable.bg_card_surface)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { showReport(report.file) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        column.addView(
            TextView(this).apply {
                text = getString(R.string.f085_report_meta, report.timeText, report.sizeText)
                setTextColor(color(R.color.theme_text_primary))
                typeface = Typeface.MONOSPACE
                textSize = 14f
            }
        )
        column.addView(
            TextView(this).apply {
                text = report.summary
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
        )
        row.addView(column)
        row.addView(deleteButton(report))

        binding.f085List.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }

    /** 单条删除：只删这一个文件，删完重读列表（条数与摘要跟着变）。 */
    private fun deleteButton(report: CrashReport): TextView = TextView(this).apply {
        text = getString(R.string.f085_action_delete)
        setTextColor(color(R.color.theme_error))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener {
            if (F085Engine.deleteReport(report.file)) {
                CustomToast.show(this@F085Activity, getString(R.string.f085_toast_deleted, report.file.name))
                renderReports()
            } else {
                setStatus(getString(R.string.f085_toast_delete_failed), R.color.theme_error)
                CustomToast.show(this@F085Activity, getString(R.string.f085_toast_delete_failed))
            }
        }
    }

    // ===== 查看全文 =====

    /**
     * 全文对话框。
     *
     * 用一个铺满屏幕的 TextView + [ScrollingMovementMethod]：报告里单行可能很长（堆栈帧），
     * 不横向滚动的话后半截会被截掉。等宽字体让缩进层级还能看出来。
     */
    private fun showReport(file: File) {
        val content = TextView(this).apply {
            text = F085Engine.readReport(file)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(color(R.color.theme_text_primary))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.surface_card))
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.f085_report_detail_title, file.name))
            .setMessage(getString(R.string.f085_report_detail_note))
            .setView(scroll)
            .setPositiveButton(getString(R.string.f085_action_close), null)
            .show()
    }

    // ===== 清空 =====

    /**
     * 全部清空前先问一次。
     *
     * 删除不可恢复，而这一页恰好是"最后一次崩溃的唯一副本"，
     * 所以确认框里写明要删几个文件，让用户按之前就知道代价。
     */
    private fun confirmClearAll() {
        val count = F085Engine.listReports(this).size
        if (count == 0) {
            setStatus(getString(R.string.f085_status_nothing_to_clear), R.color.theme_text_secondary)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.f085_clear_confirm_title))
            .setMessage(getString(R.string.f085_clear_confirm_message, count))
            .setNegativeButton(getString(R.string.f085_clear_confirm_cancel), null)
            .setPositiveButton(getString(R.string.f085_clear_confirm_ok)) { _, _ ->
                val deleted = F085Engine.deleteAll(this)
                setStatus(getString(R.string.f085_status_cleared, deleted), R.color.theme_warning)
                if (!isFinishing && !isDestroyed) renderReports()
            }
            .show()
    }

    /** 没有报告时把「全部清空」变灰且不可聚焦，遥控器不会停在一个空动作上。 */
    private fun setClearAllEnabled(enabled: Boolean) {
        binding.f085ClearAllButton.isEnabled = enabled
        binding.f085ClearAllButton.isFocusable = enabled
        binding.f085ClearAllButton.alpha = if (enabled) 1f else 0.4f
    }

    // ===== 状态 =====

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f085Status.text = message
        binding.f085Status.setTextColor(color(colorRes))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}