package com.tv.ball.feature.f071

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF071Binding
import java.io.File
import java.util.concurrent.Executors

/**
 * F071「全量数据备份」页面。
 *
 * 打包规则全在 [F071Engine]：导 prefs 成 JSON、拷数据库（连 -wal / -shm）、
 * 带 filesDir 里的可选文件、写 manifest、压成 ZIP。
 * 这里只做四件事：
 * 1. 进页面先列「本次将写入的条目」和目标文件名，让人点之前就知道会打进去什么；
 * 2. 「立即备份」在 IO 线程上跑 [F071Engine.backup]，完成后把路径 / 条目数 / 大小说出来；
 * 3. 「分享备份文件」优先走 FileProvider；本应用没在清单里声明 provider 时，
 *    退化成「复制路径 + 提示用文件管理器取」，说明文案见 f071_share_no_provider；
 * 4. 备份失败时把失败原因原样贴出来，不吞异常。
 *
 * 目标文件默认是 `getExternalFilesDir("backup")/tvplayer-<时间>.zip`，
 * 即换机前把整个 ZIP 拷走即可，不需要申请存储权限。
 */
class F071Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF071Binding

    /** 单线程池：备份是重 IO，串行跑，连点也只会有一次真的在写盘。 */
    private val io = Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())

    /** 最近一次成功的结果；没有结果时「分享」按钮不响应。 */
    private var result: F071Engine.BackupResult? = null

    /** 本次备份要写进去的条目（进页面时算一次，备份后按实际结果刷新）。 */
    private var plan: List<F071Engine.Item> = emptyList()

    /** 备份进行中：按钮置灰，防止重复点。 */
    private var running: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF071Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackup.setOnClickListener { startBackup() }
        binding.btnShare.setOnClickListener { shareBackup() }

        plan = F071Engine.planDefault(this)
        renderPlan()
        renderIdle()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // region 渲染

    /** 目标文件 + 将写入的条目清单。 */
    private fun renderPlan() {
        val target = F071Engine.defaultTarget(this)
        binding.targetStat.text = getString(R.string.f071_target_path, target.absolutePath)
        binding.planStat.text = getString(R.string.f071_plan_stat, plan.size)

        val container = binding.planContainer
        container.removeAllViews()

        if (plan.isEmpty()) {
            container.addView(line(getString(R.string.f071_plan_empty), R.color.theme_warning, true))
            return
        }

        for (item in plan) {
            // 一条一行：包内路径在上、来源在下，和 manifest 里的字段一一对应。
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            block.addView(rowText(item.entry, R.color.theme_text_primary, 14f))
            block.addView(rowText(item.source, R.color.theme_text_secondary, 12f))
            container.addView(block)
        }
    }

    /** 还没备份时的结果区。 */
    private fun renderIdle() {
        binding.resultStat.text = getString(R.string.f071_result_none)
        binding.resultStat.setTextColor(color(R.color.theme_text_secondary))
        binding.resultContainer.removeAllViews()
        binding.btnShare.isEnabled = false
        binding.btnShare.alpha = 0.5f
    }

    /** 备份成功后：路径、条目数、ZIP 大小、时间，以及逐条明细。 */
    private fun renderResult(result: F071Engine.BackupResult) {
        binding.resultStat.setTextColor(color(R.color.theme_success))
        binding.resultStat.text = getString(
            R.string.f071_result_stat,
            result.entryCount,
            result.sizeText(),
            F071Engine.formatTime(result.createdAt)
        )

        val container = binding.resultContainer
        container.removeAllViews()
        container.addView(
            line(
                getString(
                    R.string.f071_result_breakdown,
                    result.prefsCount,
                    result.dbCount,
                    result.fileCount,
                    result.payloadSizeText()
                ),
                R.color.theme_text_secondary,
                true
            )
        )
        container.addView(line(getString(R.string.f071_result_path, result.path), R.color.theme_text_primary, true))
        for (entry in result.entries) {
            container.addView(
                line(
                    getString(R.string.f071_result_entry, entry.entry, entry.sizeText(), entry.shortSha()),
                    R.color.theme_text_secondary,
                    true
                )
            )
        }

        binding.btnShare.isEnabled = true
        binding.btnShare.alpha = 1f
    }

    private fun rowText(text: String, colorRes: Int, size: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(colorRes))
        textSize = size
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun line(text: String, colorRes: Int, focusable: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(colorRes))
        textSize = 13f
        isFocusable = focusable
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundResource(R.drawable.bg_mode_row)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    // endregion

    // region 备份

    /**
     * 点「立即备份」。
     *
     * 目标文件在点的时候重新算一次时间戳：进页面到真点之间可能过了很久，
     * 用进入页面时算的那个名字会让人以为备份的是更早那一份。
     */
    private fun startBackup() {
        if (running) return
        val target = F071Engine.defaultTarget(this)
        running = true
        binding.btnBackup.isEnabled = false
        binding.btnBackup.alpha = 0.5f
        binding.resultStat.setTextColor(color(R.color.theme_text_secondary))
        binding.resultStat.text = getString(R.string.f071_backup_running)

        io.execute {
            val outcome = runCatching { F071Engine.backup(applicationContext, target) }
            main.post {
                running = false
                binding.btnBackup.isEnabled = true
                binding.btnBackup.alpha = 1f
                outcome.onSuccess { done ->
                    result = done
                    plan = F071Engine.planDefault(this)
                    renderPlan()
                    renderResult(done)
                    toast(getString(R.string.f071_toast_done, done.entryCount, done.sizeText()))
                }.onFailure { error ->
                    result = null
                    binding.resultStat.setTextColor(color(R.color.theme_error))
                    binding.resultStat.text = getString(
                        R.string.f071_result_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                    binding.resultContainer.removeAllViews()
                    binding.btnShare.isEnabled = false
                    binding.btnShare.alpha = 0.5f
                }
            }
        }
    }

    // endregion

    // region 分享

    /**
     * 分享备份文件。
     *
     * 正常路径：把 `getExternalFilesDir` 下的 ZIP 通过 FileProvider 换成 `content://`，
     * 再 `ACTION_SEND` 出去——直接塞 `file://` 从 Android 7 起会被系统拦掉，
     * 所以这里只走 provider。
     *
     * 本模块不改 AndroidManifest（那是共享文件），也就是说不保证应用里已经声明了
     * `<provider android:authorities="${applicationId}.fileprovider">`。
     * 拿不到 provider 时退化成「把完整路径复制到剪贴板」并贴出提示，
     * 用户可以从文件管理器里按这个路径把 ZIP 拷走——功能不因此不可用。
     */
    private fun shareBackup() {
        val done = result
        if (done == null) {
            toast(getString(R.string.f071_share_need_backup))
            return
        }
        val file = File(done.path)
        if (!file.exists()) {
            toast(getString(R.string.f071_share_missing))
            return
        }

        val uri = contentUri(file)
        if (uri == null) {
            copyPath(done.path)
            binding.resultContainer.addView(
                line(getString(R.string.f071_share_no_provider, done.path), R.color.theme_warning, true)
            )
            toast(getString(R.string.f071_share_copied))
            return
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.f071_share_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.f071_share_text, done.entryCount, done.sizeText()))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, getString(R.string.f071_share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(chooser) }.onFailure {
            // 没有能接 zip 的应用时别崩：退回复制路径。
            copyPath(done.path)
            toast(getString(R.string.f071_share_copied))
        }
    }

    /**
     * 用 FileProvider 换 `content://` Uri。
     *
     * authority 依次试 `${applicationId}.fileprovider` 和 `${packageName}.fileprovider`
     * （两者在没配 productFlavors 时相同，写上是为了将来改 applicationId 时仍然对得上）。
     * provider 没声明 / 路径不在 `<paths>` 里都会抛，这里一律收敛成 null 交给退路处理。
     */
    private fun contentUri(file: File): Uri? {
        val authorities = listOf("$packageName.fileprovider", "com.tv.ball.fileprovider").distinct()
        for (authority in authorities) {
            val uri = runCatching { FileProvider.getUriForFile(this, authority, file) }.getOrNull()
            if (uri != null) return uri
        }
        return null
    }

    /** 把路径放进剪贴板：没有 provider 时这是唯一能「带出去」的办法。 */
    private fun copyPath(path: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.f071_share_subject), path))
    }

    // endregion

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}