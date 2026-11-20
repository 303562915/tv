package com.tv.ball.feature.f072

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF072Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「备份恢复与校验」页。
 *
 * 一条单向流程：选 zip → 校验 → （试运行 | 恢复）→ 结果。
 *
 * - 选文件走 [ActivityResultContracts.OpenDocument]，拿到的 Uri 先复制到 cache 里一份
 *   （引擎只认 [File]，zip 的随机读也比逐段读 Uri 稳），之后整段流程都在本地文件上跑；
 * - 校验结论不通过（清单缺失、sha256 对不上、版本超出本机支持区间）时，「试运行」和「恢复」
 *   一起置灰——不给一个注定失败的按钮；
 * - 「恢复」是覆盖写，所以走二次确认；恢复完把回滚目录路径和统计一起贴在页面上，
 *   中途失败会自动回滚，[F072Engine.RestoreResult.rolledBack] 为 true 时卡片换成警示色。
 *
 * 电视没有触摸屏：返回、选备份、试运行、恢复都可聚焦，nextFocus* 在 XML 里串成
 * 返回 → 选择备份 zip → 试运行 → 恢复。
 */
class F072Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF072Binding

    /** 当前选中的备份（cache 里的副本）；换备份就整份覆盖，不留旧包。 */
    private var zipFile: File? = null

    /** 最近一次校验结果，决定按钮能不能点。 */
    private var inspected: F072Engine.InspectResult? = null

    /** 校验 / 恢复任务句柄：重新选包时先取消上一轮，防止旧结果覆盖新结果。 */
    private var job: Job? = null

    /** 恢复目标：应用私有 files 下的 restore 目录，回滚目录落在它的同级。 */
    private val targetRoot by lazy { File(filesDir, TARGET_DIR_NAME) }

    private val pickZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                CustomToast.show(this, getString(R.string.f072_toast_cancelled))
                return@registerForActivityResult
            }
            // OpenDocument 的授权带 persistable 标记，顺手拿下来，进程重建后同一 Uri 还能读
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadZip(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF072Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.f072BackButton.setOnClickListener { finish() }
        binding.f072PickButton.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        binding.f072DryRunButton.setOnClickListener { runRestore(dryRun = true) }
        binding.f072RestoreButton.setOnClickListener { confirmRestore() }

        binding.f072TargetValue.text = targetRoot.absolutePath
        renderInspect(null)
        renderRestore(null, dryRun = true)

        // 遥控器场景：进页面先把焦点落在主操作上，否则方向键无处可去
        binding.root.post { binding.f072PickButton.requestFocus() }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 选包与校验 =====

    /** 把 Uri 复制成 cache 里的本地 zip，再交给引擎校验。 */
    private fun loadZip(uri: Uri) {
        job?.cancel()
        val local = File(cacheDir, CACHE_ZIP_NAME)
        binding.f072FileName.text = uri.lastPathSegment ?: local.name
        binding.f072StatusValue.text = getString(R.string.f072_status_inspecting)
        binding.f072StatusValue.setTextColor(color(R.color.theme_text_secondary))
        renderInspect(null)
        renderRestore(null, dryRun = true)

        job = lifecycleScope.launch {
            val copied = runCatching {
                local.parentFile?.mkdirs()
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("openInputStream 返回 null")
                input.use { source -> local.outputStream().use { sink -> source.copyTo(sink) } }
            }
            if (copied.isFailure) {
                val reason = copied.exceptionOrNull()?.message.orEmpty()
                binding.f072StatusValue.text = getString(R.string.f072_toast_copy_fail, reason)
                binding.f072StatusValue.setTextColor(color(R.color.theme_error))
                CustomToast.show(this@F072Activity, getString(R.string.f072_toast_copy_fail, reason))
                return@launch
            }

            zipFile = local
            val result = F072Engine.inspect(local)
            renderInspect(result)
            binding.f072StatusValue.text = getString(R.string.f072_status_ready)
        }
    }

    // ===== 恢复 =====

    /** 「恢复」是覆盖写：先弹二次确认，确认后才真动手。 */
    private fun confirmRestore() {
        if (zipFile == null || !canRestore()) {
            CustomToast.show(this, getString(R.string.f072_toast_blocked))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.f072_confirm_title)
            .setMessage(getString(R.string.f072_confirm_message, targetRoot.absolutePath))
            .setNegativeButton(R.string.f072_confirm_cancel, null)
            .setPositiveButton(R.string.f072_confirm_ok) { _, _ -> runRestore(dryRun = false) }
            .show()
    }

    private fun runRestore(dryRun: Boolean) {
        val zip = zipFile ?: return
        if (!canRestore()) {
            CustomToast.show(this, getString(R.string.f072_toast_blocked))
            return
        }
        job?.cancel()
        setBusy(true)
        job = lifecycleScope.launch {
            val result = F072Engine.restore(
                zipFile = zip,
                targetDirs = listOf(targetRoot),
                dryRun = dryRun,
                rollbackRoot = targetRoot.parentFile
            )
            setBusy(false)
            renderRestore(result, dryRun)
            if (result.rolledBack) {
                CustomToast.show(this@F072Activity, getString(R.string.f072_toast_rolled_back))
            }
        }
    }

    private fun canRestore(): Boolean = inspected?.restorable == true &&
        F072Engine.isCompatible(inspected?.version)

    /** 跑任务期间两个按钮都不用再点：置灰 + 半透明，跟页面其它禁用态一致。 */
    private fun setBusy(busy: Boolean) {
        val enabled = !busy && canRestore()
        listOf(binding.f072DryRunButton, binding.f072RestoreButton).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    // ===== 渲染 =====

    /** 铺清单卡片：版本 / 时间 / 条目数 / 校验结论 / 兼容性 + 逐条结果 + 问题列表。 */
    private fun renderInspect(result: F072Engine.InspectResult?) {
        inspected = result
        val unknown = getString(R.string.f072_value_unknown)

        binding.f072VersionValue.text = result?.version ?: unknown
        binding.f072CreatedValue.text = result?.createdAt ?: unknown
        binding.f072EntriesValue.text = result?.let {
            getString(R.string.f072_entries_value, it.entries.size)
        } ?: unknown

        binding.f072ChecksumValue.text = when {
            result == null -> unknown
            result.checksumOk -> getString(R.string.f072_checksum_ok)
            else -> getString(R.string.f072_checksum_bad)
        }
        binding.f072ChecksumValue.setTextColor(
            color(
                when {
                    result == null -> R.color.theme_text_secondary
                    result.checksumOk -> R.color.theme_success
                    else -> R.color.theme_error
                }
            )
        )

        val compatible = result != null && F072Engine.isCompatible(result.version)
        binding.f072CompatValue.text = when {
            result == null -> unknown
            compatible -> getString(R.string.f072_compat_ok, F072Engine.supportedRangeLabel())
            else -> getString(R.string.f072_compat_bad, F072Engine.supportedRangeLabel())
        }
        binding.f072CompatValue.setTextColor(
            color(
                when {
                    result == null -> R.color.theme_text_secondary
                    compatible -> R.color.theme_success
                    else -> R.color.theme_warning
                }
            )
        )

        renderEntries(result?.entries.orEmpty())
        renderProblems(result?.problems.orEmpty())
        setBusy(false)
    }

    /** 逐条结果：路径 + 是否通过，最多铺 [MAX_ENTRY_LINES] 行，多的折成一句。 */
    private fun renderEntries(entries: List<F072Engine.EntryInfo>) {
        val container = binding.f072EntriesList
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        entries.take(MAX_ENTRY_LINES).forEach { entry ->
            val label = getString(
                if (entry.verified) R.string.f072_entry_ok else R.string.f072_entry_bad
            )
            container.addView(line(getString(R.string.f072_entry_line, entry.path, label)))
        }
        if (entries.size > MAX_ENTRY_LINES) {
            container.addView(
                line(getString(R.string.f072_entries_more, entries.size - MAX_ENTRY_LINES))
            )
        }
    }

    /** 问题列表：有问题就一行一条，没问题显示「没有发现问题」，不留白板。 */
    private fun renderProblems(problems: List<String>) {
        val container = binding.f072ProblemsContainer
        container.removeAllViews()
        val warn = color(R.color.theme_warning)
        problems.forEach { problem ->
            container.addView(line(problem, warn))
        }
        binding.f072ProblemsEmpty.visibility = if (problems.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 铺结果卡片。
     *
     * @param result null 表示还没跑过，显示占位说明；[dryRun] 决定文案用「将恢复」还是「已恢复」。
     */
    private fun renderRestore(result: F072Engine.RestoreResult?, dryRun: Boolean) {
        if (result == null) {
            binding.f072ResultValue.text = getString(R.string.f072_result_idle)
            binding.f072ResultValue.setTextColor(color(R.color.theme_text_secondary))
            binding.f072RollbackValue.text = getString(R.string.f072_rollback_none)
            binding.f072ResultProblems.removeAllViews()
            return
        }

        binding.f072ResultValue.text = when {
            result.rolledBack -> getString(
                R.string.f072_result_rolled_back, result.restored, result.skipped
            )
            dryRun -> getString(R.string.f072_result_dry_run, result.restored, result.skipped)
            else -> getString(R.string.f072_result_done, result.restored, result.skipped)
        }
        binding.f072ResultValue.setTextColor(
            color(
                when {
                    result.rolledBack -> R.color.theme_error
                    result.problems.isEmpty() -> R.color.theme_success
                    else -> R.color.theme_warning
                }
            )
        )
        binding.f072RollbackValue.text = result.rollbackDir
            ?: getString(R.string.f072_rollback_none)

        val container = binding.f072ResultProblems
        container.removeAllViews()
        result.problems.drop(inspected?.problems?.size ?: 0).forEach { problem ->
            container.addView(line(problem, color(R.color.theme_text_secondary)))
        }
    }

    /** 一行说明文字：统一字号/颜色/行距，问题列表和条目列表都复用它。 */
    private fun line(text: String, textColor: Int = color(R.color.theme_text_secondary)): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(textColor)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 恢复目标子目录名：放在应用私有 files 下，回滚目录落在同一个父级。 */
        private const val TARGET_DIR_NAME = "f072_restore"

        /** 选中的 zip 复制到 cache 时固定用这个名字，重复选包直接覆盖。 */
        private const val CACHE_ZIP_NAME = "f072-incoming.zip"

        /** 条目逐行展示的上限：再多页面会被撑爆，统计数字仍然准确。 */
        private const val MAX_ENTRY_LINES = 30
    }
}
