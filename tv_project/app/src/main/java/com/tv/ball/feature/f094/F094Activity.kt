package com.tv.ball.feature.f094

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF094Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「缓存分类清理」页。
 *
 * 三段：
 * 1. **合计**：缓存总量 + 文件数 + 一行状态（统计中 / 统计完成 / 缓存已空）；
 * 2. **分类明细**：图片 / 视频 / 日志 / 其他四行，每行给占用大小、文件数和一个「清理」按钮；
 * 3. **哪些不会被删**：把清理边界写在页面上——数据库（收藏、历史、播放记录）、
 *    `filesDir`、外置存储里的成品视频都不在清理范围内，用户不用怕点一下把收藏弄没。
 *
 * 交互上有三条硬规则：
 * - **每一类都单独确认**：点「清理」先弹对话框写明「这一类有多大、多少个文件、删了不能恢复」，
 *   确认之后才真的删。清理全部缓存同样确认一次，不会因为按钮名字更长就跳过二次确认；
 * - **删完立刻重扫**：清理与统计在同一个 IO 块里跑完，页面拿到的是删完之后的真实状态，
 *   不是「拿旧数字减掉已删数」算出来的账。某一类删空了就显示 0，按钮自己灰掉；
 * - **失败要说出来**：删不掉的文件（被占着 / 权限不足）单独计数，回执里写清楚
 *   「删了 8 个、2 个没删掉」，不假装全都清干净了。
 *
 * 所有文件操作都在 [F094Engine] 里、跑在 [Dispatchers.IO]：本页只负责确认、进度和回执。
 */
class F094Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF094Binding

    /** 当前这一轮统计任务；统计 / 清理 / 重扫共用一条，避免连按确认键堆出并发遍历。 */
    private var job: Job? = null

    /** 最近一次统计结果，四类永远齐全（空的给 0）。 */
    private var buckets: List<F094Engine.Bucket> = F094Engine.emptyBuckets()

    /** 正在统计或清理：此时所有按钮都禁掉，避免重入。 */
    private var busy: Boolean = false

    /**
     * 一次清理的回执，用来在页面底部留一行「上次清理」。
     *
     * @param label 这一轮清的是哪一类（单类用分类名，「清理全部」用「全部缓存」）。
     * @param result 引擎回的删除结果，含失败数。
     */
    private data class ClearReport(val label: String, val result: F094Engine.ClearResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF094Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f094ImageClear.setOnClickListener { confirmClear(F094Engine.Category.IMAGE) }
        binding.f094VideoClear.setOnClickListener { confirmClear(F094Engine.Category.VIDEO) }
        binding.f094LogClear.setOnClickListener { confirmClear(F094Engine.Category.LOG) }
        binding.f094OtherClear.setOnClickListener { confirmClear(F094Engine.Category.OTHER) }
        binding.f094ClearAllButton.setOnClickListener { confirmClearAll() }

        // 进页面先扫一遍：用户点进来就是要看数字，不该再点一下才给
        refresh(report = null)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 统计 =====

    /**
     * 跑一轮统计并渲染。
     *
     * 目录递归放在 IO 线程，主线程只接结果；已在跑任务时不重入。
     *
     * @param report 清理回执；非 null 时在页面底部显示「上次清理」那一行，null 表示只是刷新。
     */
    private fun refresh(report: ClearReport?) {
        if (job?.isActive == true) return
        setBusy(true)
        setStatus(getString(R.string.f094_status_scanning), R.color.theme_tertiary)

        job = lifecycleScope.launch {
            try {
                val scanned = withContext(Dispatchers.IO) { F094Engine.scan(this@F094Activity) }
                if (isFinishing || isDestroyed) return@launch
                render(scanned, report)
            } catch (cancel: CancellationException) {
                // 取消是用户动作（返回 / 页面销毁），别吞掉：scope 要靠它收子任务
                throw cancel
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    setStatus(
                        getString(R.string.f094_status_error, e.message ?: e.javaClass.simpleName),
                        R.color.theme_error
                    )
                }
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    // ===== 清理 =====

    /**
     * 单类清理的二次确认。
     *
     * 对话框里的数字取自当前 [buckets]（就是列表上那几行），不是重新扫一遍——
     * 用户看到的和确认的是同一份数据，不会出现「对话框说 12 MB、清完发现多了 3 MB」的错位。
     */
    private fun confirmClear(category: F094Engine.Category) {
        if (busy) return
        val bucket = F094Engine.of(buckets, category)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.f094_clear_confirm_title, category.label))
            .setMessage(
                getString(
                    R.string.f094_clear_confirm_message,
                    category.label,
                    bucket.files,
                    bucket.sizeText()
                )
            )
            .setNegativeButton(R.string.f094_action_cancel, null)
            .setPositiveButton(R.string.f094_action_confirm) { _, _ -> clearCategory(category) }
            .show()
    }

    /** 清理全部的二次确认：把四类合计写进对话框，用户知道这一下会删多少。 */
    private fun confirmClearAll() {
        if (busy) return
        val totalBytes = F094Engine.total(buckets)
        val totalFiles = F094Engine.totalFiles(buckets)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.f094_clear_confirm_all_title)
            .setMessage(
                getString(
                    R.string.f094_clear_confirm_all_message,
                    totalFiles,
                    F094Engine.formatBytes(totalBytes)
                )
            )
            .setNegativeButton(R.string.f094_action_cancel, null)
            .setPositiveButton(R.string.f094_action_confirm) { _, _ -> clearEveryCategory() }
            .show()
    }

    /**
     * 真的清一类：删完立刻重扫，再用引擎回执刷页面。
     *
     * 删除和重扫放在**同一个** `withContext(Dispatchers.IO)` 里：
     * 中间不切线程，就不会出现「删完了但页面显示的还是旧数字」的窗口。
     */
    private fun clearCategory(category: F094Engine.Category) {
        if (busy || job?.isActive == true) return
        setBusy(true)
        setStatus(getString(R.string.f094_status_clearing), R.color.theme_tertiary)

        job = lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val cleared = F094Engine.clear(this@F094Activity, category)
                    cleared to F094Engine.scan(this@F094Activity)
                }
                if (isFinishing || isDestroyed) return@launch
                render(outcome.second, ClearReport(category.label, outcome.first))
                CustomToast.show(this@F094Activity, toastForSingle(category, outcome.first))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    setStatus(
                        getString(R.string.f094_status_error, e.message ?: e.javaClass.simpleName),
                        R.color.theme_error
                    )
                }
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    /** 真的清全部：四类逐类删（口径与四行按钮完全一致），然后重扫。 */
    private fun clearEveryCategory() {
        if (busy || job?.isActive == true) return
        setBusy(true)
        setStatus(getString(R.string.f094_status_clearing), R.color.theme_tertiary)

        job = lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val cleared = F094Engine.clearAll(this@F094Activity)
                    cleared to F094Engine.scan(this@F094Activity)
                }
                if (isFinishing || isDestroyed) return@launch
                render(outcome.second, ClearReport(getString(R.string.f094_label_all), outcome.first))
                CustomToast.show(this@F094Activity, toastForAll(outcome.first))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    setStatus(
                        getString(R.string.f094_status_error, e.message ?: e.javaClass.simpleName),
                        R.color.theme_error
                    )
                }
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    // ===== 回执文案 =====

    /**
     * 单类清理的 Toast：删成功 / 部分失败 / 全失败 / 一个都没得删，四档分开说。
     *
     * 「部分失败」这一档必须存在：把「删了 8 个、2 个被占用」说成「已清理」，
     * 用户下次点进来看到数字没归零会以为功能坏了。
     */
    private fun toastForSingle(
        category: F094Engine.Category,
        result: F094Engine.ClearResult
    ): String = when {
        result.removed && result.hasFailure -> getString(
            R.string.f094_clear_partial,
            category.label,
            result.files,
            F094Engine.formatBytes(result.bytes),
            result.failed
        )

        result.removed -> getString(
            R.string.f094_clear_done,
            category.label,
            result.files,
            F094Engine.formatBytes(result.bytes)
        )

        result.hasFailure -> getString(R.string.f094_clear_failed, category.label, result.failed)

        else -> getString(R.string.f094_clear_none, category.label)
    }

    /** 清理全部的 Toast：口径同上，只是名字换成「全部缓存」。 */
    private fun toastForAll(result: F094Engine.ClearResult): String {
        val label = getString(R.string.f094_label_all)
        return when {
            result.removed && result.hasFailure -> getString(
                R.string.f094_clear_partial,
                label,
                result.files,
                F094Engine.formatBytes(result.bytes),
                result.failed
            )

            result.removed -> getString(
                R.string.f094_clear_done,
                label,
                result.files,
                F094Engine.formatBytes(result.bytes)
            )

            result.hasFailure -> getString(R.string.f094_clear_failed, label, result.failed)

            else -> getString(R.string.f094_clear_all_nothing)
        }
    }

    // ===== 渲染 =====

    /** 把一轮结果整体画出来：合计、四行、状态、上次清理回执、按钮可用性。 */
    private fun render(scanned: List<F094Engine.Bucket>, report: ClearReport?) {
        buckets = scanned

        binding.f094TotalText.text = getString(
            R.string.f094_total_value,
            F094Engine.formatBytes(F094Engine.total(scanned)),
            F094Engine.totalFiles(scanned)
        )

        bindRow(F094Engine.Category.IMAGE, binding.f094ImageSize)
        bindRow(F094Engine.Category.VIDEO, binding.f094VideoSize)
        bindRow(F094Engine.Category.LOG, binding.f094LogSize)
        bindRow(F094Engine.Category.OTHER, binding.f094OtherSize)

        val total = F094Engine.total(scanned)
        if (total <= 0L) {
            setStatus(getString(R.string.f094_status_empty), R.color.theme_warning)
        } else {
            setStatus(getString(R.string.f094_status_ready), R.color.theme_success)
        }

        renderLastClear(report)
        syncActions()
    }

    /**
     * 一行的大小文案：`12.3 MB · 46 个文件`。
     *
     * 空的那一类照样显示 `0 B · 0 个文件`，不留白：用户需要确认「这一类确实没有东西」，
     * 而不是猜「是没统计还是真没有」。按钮的可用性由 [syncActions] 统一管。
     */
    private fun bindRow(category: F094Engine.Category, sizeView: TextView) {
        val bucket = F094Engine.of(buckets, category)
        sizeView.text = getString(R.string.f094_row_value, bucket.sizeText(), bucket.files)
    }

    /** 底部那行「上次清理」；没有清理过就整行藏掉，不占版面。 */
    private fun renderLastClear(report: ClearReport?) {
        if (report == null) {
            binding.f094LastClearText.visibility = View.GONE
            return
        }
        binding.f094LastClearText.visibility = View.VISIBLE
        binding.f094LastClearText.text = getString(
            R.string.f094_last_clear,
            report.label,
            report.result.files,
            F094Engine.formatBytes(report.result.bytes)
        )
    }

    /**
     * 按钮可用性：忙碌时全禁；空闲时只有非空的分类能点，
     * 缓存整体为空时「清理全部缓存」也灰掉——点了也只会得到一句「没有可清理的文件」。
     */
    private fun syncActions() {
        binding.f094ImageClear.isEnabled = clearable(F094Engine.Category.IMAGE)
        binding.f094VideoClear.isEnabled = clearable(F094Engine.Category.VIDEO)
        binding.f094LogClear.isEnabled = clearable(F094Engine.Category.LOG)
        binding.f094OtherClear.isEnabled = clearable(F094Engine.Category.OTHER)
        binding.f094ClearAllButton.isEnabled = !busy && F094Engine.total(buckets) > 0L
    }

    private fun clearable(category: F094Engine.Category): Boolean =
        !busy && !F094Engine.of(buckets, category).isEmpty()

    /** 忙碌开关：一次改完所有按钮状态，避免各处零散地 `isEnabled = ...`。 */
    private fun setBusy(value: Boolean) {
        busy = value
        syncActions()
    }

    /** 状态行：文案 + 语义色（统计中 tertiary / 完成 success / 空 warning / 失败 error）。 */
    private fun setStatus(text: String, colorRes: Int) {
        binding.f094StatusText.text = text
        binding.f094StatusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }
}
