package com.tv.ball.feature.f091

import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF091Binding
import com.tv.ball.util.CustomToast
import kotlin.random.Random

/** 每段模拟延迟的下界（毫秒）。 */
private const val MIN_STAGE_DELAY_MS: Int = 20

/** 在 [MIN_STAGE_DELAY_MS] 之上再加的随机量，于是每段落在 20~120 ms。 */
private const val EXTRA_STAGE_DELAY_MS: Int = 100

/**
 * 「启动耗时打点」页。
 *
 * 三块：上次启动的三段耗时（+ 合计、最慢一段）、两个按钮（模拟一次启动 / 导出文本）、最近 10 次的列表。
 *
 * ## 这一页为什么不改 Application
 *
 * 探针（[StartupTrace]）是独立的：Application 想打点就自己调 [StartupTrace.mark]，
 * 不调也照样能跑——这一页自己打一组演示用的点，整条链路（begin → mark → finish → 落盘 → 导出）
 * 跟真实接线完全一样，只是数值来自假延迟。这样这个功能不会跟任何一份 Application 代码绑死，
 * 不会出现"两个功能都想在 Application.onCreate 里插一行"的合并冲突。
 *
 * ## 演示里的 sleep 是有意的
 *
 * 「模拟一次启动」在主线程上连等三段 20~120 ms 的随机时间再打点，最多按住主线程 360 ms。
 * 要演的就是"冷启动被同步初始化占住"这件事，所以这 360 ms 不能扔到子线程去——
 * 那样量出来的只是子线程自己睡了多久，跟启动耗时没关系。
 * 副作用是这三段期间界面不会重绘（主线程被占住，刷了也画不出来），因此只在跑完后刷新一次。
 *
 * ## 页面不缓存历史
 *
 * 每次渲染都重新问一遍 [StartupTrace.snapshot]：记录只可能被探针改，
 * 页面留一份副本就一定会出现"屏上的合计和导出的合计不一致"。
 */
class F091Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF091Binding

    /**
     * 演示用的随机源。
     *
     * 用 [SystemClock.uptimeMillis] 播种而不是无参构造：无参构造每次都以同一时刻播种，
     * 连点两次"模拟一次启动"会得到一模一样的三段延迟，看着像写死的假数据。
     */
    private val random = Random(SystemClock.uptimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF091Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 把 Context 交给探针，并顺手把它上次进程留下的历史读进内存。
        // 只交一次：之后 mark/finish/snapshot 都不用再传 Context，也不需要改 Application。
        StartupTrace.attach(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.f091SimulateButton.setOnClickListener { simulateStartup() }
        binding.f091ExportButton.setOnClickListener { exportTrace() }

        render()

        // 焦点先落主动作：遥控器按一下就是"跑一次演示"
        binding.f091SimulateButton.requestFocus()
    }

    // ===== 演示：跑一组假延迟 =====

    /**
     * 模拟一次冷启动：按 [StartupTrace.STAGES] 的顺序，每段等一个 20~120 ms 的随机时间再打点。
     *
     * 三类杂事都在这里：
     * 1. `begin(fromProcessStart = false)`：原点取"此刻"。默认原点（进程创建）在这里是错的——
     *    进程可能已经活了几小时，第一段会变成"进程活了多久"，看不出这是刚模拟的一次启动。
     * 2. 每段 `Thread.sleep` 之后 [StartupTrace.mark] 打一个点；打点是累计时间，段耗时由引擎事后相减。
     * 3. [StartupTrace.finish] 封存这一轮并落盘，然后 [render] 把三块内容一起刷新。
     *
     * 中断（页面被系统回收、进程正在退）直接放弃这一轮：不 finish 就不会留下半截记录。
     */
    private fun simulateStartup() {
        StartupTrace.begin(fromProcessStart = false)
        for (stage in StartupTrace.STAGES) {
            val delayMs = MIN_STAGE_DELAY_MS + random.nextInt(EXTRA_STAGE_DELAY_MS + 1)
            try {
                Thread.sleep(delayMs.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            StartupTrace.mark(stage)
        }

        val run = StartupTrace.finish()
        render()

        CustomToast.show(
            this,
            if (run == null) {
                getString(R.string.f091_toast_nothing)
            } else {
                getString(R.string.f091_toast_simulated, run.totalMs, run.marks.size)
            }
        )
    }

    // ===== 渲染 =====

    /**
     * 整页重画。
     *
     * 空历史也照常画：三段位置显示「—」、列表显示空态文案，用户第一次进来就能看懂
     * "这里该出现什么"，而不是一屏没对账的空白。
     */
    private fun render() {
        val latest = StartupTrace.latest()

        binding.f091LastHeader.text = if (latest == null) {
            getString(R.string.f091_last_none)
        } else {
            getString(
                R.string.f091_last_header,
                StartupTrace.formatClock(latest.startedAt),
                latest.marks.size
            )
        }

        // 三段按固定名字取：宿主没打某个点（比如还没接首帧回调），那一段就是「—」，
        // 不是 0——0 会被误读成"这段不耗时"。
        bindStage(binding.f091StageApp, latest?.costOf(StartupTrace.STAGE_APPLICATION))
        bindStage(binding.f091StageActivity, latest?.costOf(StartupTrace.STAGE_ACTIVITY))
        bindStage(binding.f091StageFrame, latest?.costOf(StartupTrace.STAGE_FIRST_FRAME))

        binding.f091Total.text = if (latest == null) {
            getString(R.string.f091_value_missing)
        } else {
            getString(R.string.f091_total_value, latest.totalMs, latest.marks.size)
        }

        val slowest = latest?.slowestStage
        binding.f091Slowest.text = if (slowest == null) {
            getString(R.string.f091_value_missing)
        } else {
            getString(R.string.f091_slowest_value, slowest.name, slowest.ms)
        }

        renderHistory()
        renderStatus()
    }

    /** 一段耗时的显示：有数据显示 `312 ms`（主色），没有数据显示「—」（次要色）。 */
    private fun bindStage(view: TextView, ms: Long?) {
        if (ms == null) {
            view.text = getString(R.string.f091_value_missing)
            view.setTextColor(color(R.color.theme_text_secondary))
        } else {
            view.text = getString(R.string.f091_stage_value, ms)
            view.setTextColor(color(R.color.theme_text_primary))
        }
    }

    /**
     * 最近 10 次，新 → 旧，一行一次。
     *
     * 用等宽字体（布局里定死 monospace）而不是十条独立 View：
     * 电视上这行只是给人扫一眼"哪次特别慢"，不需要逐行可聚焦——
     * 十行可聚焦反而让遥控器要按十下才能走到导出按钮。
     */
    private fun renderHistory() {
        val runs = StartupTrace.snapshot()
        if (runs.isEmpty()) {
            binding.f091HistoryText.text = getString(R.string.f091_history_empty)
            return
        }
        binding.f091HistoryText.text = runs.mapIndexed { index, run ->
            val slowest = run.slowestStage
            if (slowest == null) {
                getString(
                    R.string.f091_history_row_flat,
                    index + 1,
                    StartupTrace.formatClock(run.startedAt),
                    run.totalMs,
                    run.marks.size
                )
            } else {
                getString(
                    R.string.f091_history_row,
                    index + 1,
                    StartupTrace.formatClock(run.startedAt),
                    run.totalMs,
                    slowest.name,
                    slowest.ms,
                    run.marks.size
                )
            }
        }.joinToString("\n")
    }

    /** 记录数量提示：让人知道再有几次就会开始挤掉最旧的一条。 */
    private fun renderStatus() {
        val size = StartupTrace.snapshot().size
        binding.f091Status.text = if (size == 0) {
            getString(R.string.f091_status_empty)
        } else {
            getString(R.string.f091_status_records, size, StartupTrace.MAX_RUNS)
        }
    }

    // ===== 导出 =====

    /**
     * 导出一份纯文本到应用专属外部目录。
     *
     * 电视盒子没有分享面板，导出的唯一现实用法是"用 adb pull 拉走"或者从文件管理器翻出来，
     * 所以结果行直接把**绝对路径 + 字节数 + 记录条数**写出来，不用用户再去猜文件在哪。
     *
     * 写盘失败（目录建不出来、磁盘满）只影响这一行提示，页面其它部分照旧可用。
     */
    private fun exportTrace() {
        val text = StartupTrace.exportText()
        try {
            val file = StartupTrace.writeExport(this, text)
            binding.f091ExportResult.text = getString(
                R.string.f091_export_done,
                file.absolutePath,
                file.length(),
                StartupTrace.snapshot().size
            )
            binding.f091ExportResult.setTextColor(color(R.color.theme_success))
            CustomToast.show(this, getString(R.string.f091_toast_exported))
        } catch (e: Exception) {
            binding.f091ExportResult.text =
                getString(R.string.f091_export_failed, e.message ?: e.javaClass.simpleName)
            binding.f091ExportResult.setTextColor(color(R.color.theme_error))
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
}
