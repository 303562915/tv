package com.tv.ball.feature.f084

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF084Binding
import com.tv.ball.util.CustomToast

/**
 * 「日志级别与导出」页。
 *
 * 四段：级别（五级单选，存在本地）、测试（每按一次写一条，级别轮着来）、
 * 内存日志（最近 500 条，按级别着色）、导出（回显完整路径 + 真实字节数，另有清空）。
 *
 * 页面自己不维护日志副本：列表每次都从 [F084Engine.snapshot] 拿，写一条、清空、导出
 * 读的都是同一个环形缓冲，所以界面上看到的条数和导出文件里的条数永远一致。
 *
 * 电视没有触摸屏：返回、五个单选项、测试、导出、清空都可聚焦，D-Pad 竖直串成一条线；
 * 进页面前先把焦点落在当前级别那一项上，遥控器上下就能切级别。
 */
class F084Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF084Binding

    /** 测试日志序号：从 1 开始累加，清空时归零。 */
    private var testSeq = 0

    /** 测试日志的级别轮转游标，旋转时随实例状态带回来。 */
    private var testCursor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF084Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 绑定存档并恢复上次保存的级别：必须先于下面勾选，否则单选组勾在默认值上
        F084Engine.attach(this)

        testSeq = savedInstanceState?.getInt(STATE_SEQ) ?: 0
        testCursor = savedInstanceState?.getInt(STATE_CURSOR) ?: 0

        binding.f084Back.setOnClickListener { finish() }

        // 先勾选再挂监听：恢复上次级别的这一下不该再触发一次落盘
        binding.f084LevelGroup.check(radioOf(F084Engine.currentLevel).id)
        binding.f084LevelGroup.setOnCheckedChangeListener { _, checkedId ->
            val picked = levelOf(checkedId) ?: return@setOnCheckedChangeListener
            if (picked == F084Engine.currentLevel) return@setOnCheckedChangeListener
            F084Engine.setLevel(picked)
            // 切级别只影响之后写进来的日志，已经留在缓冲里的旧条目不删
            renderLevelHint()
            renderList()
        }

        binding.f084TestButton.setOnClickListener { writeTestLog() }
        binding.f084ExportButton.setOnClickListener { exportLog() }
        binding.f084ClearButton.setOnClickListener { clearLogs() }

        binding.f084DeviceInfo.text = F084Engine.deviceSummary()
        renderLevelHint()
        renderList()
        radioOf(F084Engine.currentLevel).requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 日志本身在进程级缓冲里，旋转不用搬；只有这两个计数是页面私有的
        outState.putInt(STATE_SEQ, testSeq)
        outState.putInt(STATE_CURSOR, testCursor)
    }

    // ===== 写一条测试日志 =====

    /**
     * 写一条测试日志。
     *
     * 级别按 ERROR → WARN → INFO → DEBUG → VERBOSE 轮转（由重到轻）：
     * 第一下一定写得进，越按越往下探，级别设成 INFO 时轮到 DEBUG 那条就会被挡掉，
     * 结果行会明说「被过滤掉了」，而不是让用户以为按钮坏了；五级配色也顺带都看得到。
     */
    private fun writeTestLog() {
        val level = LogLevel.WRITE_ORDER[testCursor % LogLevel.WRITE_ORDER.size]
        testCursor = (testCursor + 1) % LogLevel.WRITE_ORDER.size
        testSeq += 1

        val accepted = F084Engine.log(
            level,
            TAG,
            getString(R.string.f084_test_message, testSeq, level.name)
        )

        binding.f084TestResult.text = if (accepted) {
            getString(R.string.f084_test_accepted, level.name, F084Engine.currentLevel.name)
        } else {
            getString(R.string.f084_test_filtered, level.name, F084Engine.currentLevel.name)
        }
        binding.f084TestResult.setTextColor(
            ContextCompat.getColor(
                this,
                if (accepted) R.color.theme_success else R.color.theme_warning
            )
        )
        renderList()
    }

    // ===== 导出 / 清空 =====

    /**
     * 导出到 `getExternalFilesDir("logs")/tvplayer-log-<时间>.txt`。
     *
     * 成功后把绝对路径和 `File.length()` 的字节数回显出来，用户能照着路径去文件管理器里找；
     * 500 条日志也就几十 KB，同步写足够，开线程反而要多处理一层生命周期。
     */
    private fun exportLog() {
        try {
            val file = F084Engine.exportTo(this)
            binding.f084ExportResult.text = getString(
                R.string.f084_export_done,
                F084Engine.count(),
                file.absolutePath,
                F084Engine.formatBytes(file.length())
            )
            binding.f084ExportResult.setTextColor(ContextCompat.getColor(this, R.color.theme_success))
            CustomToast.show(this, getString(R.string.f084_toast_exported))
        } catch (e: Exception) {
            // 外部存储掉线、目录建不出来、磁盘满都走这里，页面不能崩
            binding.f084ExportResult.text =
                getString(R.string.f084_export_failed, e.message ?: e.javaClass.simpleName)
            binding.f084ExportResult.setTextColor(ContextCompat.getColor(this, R.color.theme_error))
        }
    }

    /** 清空缓冲与测试计数；级别设置不动（级别是用户偏好，不该被清日志顺手改掉）。 */
    private fun clearLogs() {
        F084Engine.clear()
        testSeq = 0
        testCursor = 0

        binding.f084TestResult.setText(R.string.f084_test_idle)
        binding.f084TestResult.setTextColor(
            ContextCompat.getColor(this, R.color.theme_text_secondary)
        )
        renderList()
        CustomToast.show(this, getString(R.string.f084_toast_cleared))
    }

    // ===== 渲染 =====

    private fun renderLevelHint() {
        binding.f084LevelHint.text =
            getString(R.string.f084_level_now, F084Engine.currentLevel.name)
    }

    /** 列表按缓冲快照重建：最新一条在最上面，超出 [MAX_ROWS] 只画最近这些。 */
    private fun renderList() {
        val entries = F084Engine.snapshot()

        binding.f084LogCount.text =
            getString(R.string.f084_log_count, entries.size, F084Engine.MAX_ENTRIES)

        val container = binding.f084LogContainer
        container.removeAllViews()
        binding.f084LogEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        entries.asReversed().take(MAX_ROWS).forEach { entry ->
            container.addView(rowView(entry))
        }
        if (entries.size > MAX_ROWS) {
            container.addView(noteView(getString(R.string.f084_log_more, MAX_ROWS)))
        }
    }

    /** 一行日志：等宽字体 + 按级别取色，时间戳对齐后一列扫下来能看清节奏。 */
    private fun rowView(entry: LogEntry): TextView = TextView(this).apply {
        text = entry.format()
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(this@F084Activity, colorOf(entry.level)))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun noteView(content: String): TextView = TextView(this).apply {
        text = content
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@F084Activity, R.color.theme_text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    /** 级别配色：越严重越刺眼，VERBOSE 压到次级文字色，免得刷屏时眼睛疼。 */
    private fun colorOf(level: LogLevel): Int = when (level) {
        LogLevel.VERBOSE -> R.color.theme_text_secondary
        LogLevel.DEBUG -> R.color.theme_info
        LogLevel.INFO -> R.color.theme_primary
        LogLevel.WARN -> R.color.theme_warning
        LogLevel.ERROR -> R.color.theme_error
    }

    // ===== 单选组映射 =====

    private fun radioOf(level: LogLevel): RadioButton = when (level) {
        LogLevel.VERBOSE -> binding.f084LevelVerbose
        LogLevel.DEBUG -> binding.f084LevelDebug
        LogLevel.INFO -> binding.f084LevelInfo
        LogLevel.WARN -> binding.f084LevelWarn
        LogLevel.ERROR -> binding.f084LevelError
    }

    /** 单选组勾到的 id → 级别；不是五个选项之一（例如 `-1` 清空）就返回 null。 */
    private fun levelOf(checkedId: Int): LogLevel? = when (checkedId) {
        R.id.f084LevelVerbose -> LogLevel.VERBOSE
        R.id.f084LevelDebug -> LogLevel.DEBUG
        R.id.f084LevelInfo -> LogLevel.INFO
        R.id.f084LevelWarn -> LogLevel.WARN
        R.id.f084LevelError -> LogLevel.ERROR
        else -> null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_SEQ = "f084_test_seq"
        const val STATE_CURSOR = "f084_test_cursor"

        /** 列表一次最多画多少行；缓冲仍留 500 条，导出的是全部。 */
        const val MAX_ROWS = 60

        /** 写日志时用的 TAG，导出文本里会带在级别后面。 */
        const val TAG = F084Engine.DEFAULT_TAG
    }
}
