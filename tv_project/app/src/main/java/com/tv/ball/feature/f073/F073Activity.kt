package com.tv.ball.feature.f073

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF073Binding
import com.tv.ball.util.CustomToast

/**
 * 「历史导入导出」页。
 *
 * 三段：导出（一条主动作，落盘后回显完整路径和字节数）、导入（选文件 / 粘贴两条入口，
 * 出新增 · 跳过 · 非法三个数字，并刷新导入后的条目数）、历史一览（把当前这份历史列出来，
 * 导进来的东西立刻看得见）。
 *
 * 数据只有一份：[items]。导出把它序列化，导入把它合并 —— 页面不维护第二套状态，
 * 所以「导出 6 条 → 删掉本地记录 → 导回来」看到的就是同一批 id。
 *
 * 页面首屏先塞 [F073Engine.sampleItems] 的 6 条示例：不需要真的播放过视频，
 * 装上就能把两条路径跑通；旋转 / 进程重建靠 [onSaveInstanceState] 把当前列表
 * 用导出 JSON 存回来（不引 Parcelable），恢复时再走一遍 [F073Engine.import]。
 *
 * 电视没有触摸屏：返回、导出、选文件、粘贴、文本框、导入文本都可聚焦，D-Pad 竖直往下一条线。
 */
class F073Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF073Binding

    /** 当前页面上这份历史。导入成功后追加，导出就是把它序列化。 */
    private val items = ArrayList<F073Engine.HistoryItem>()

    /**
     * 选 JSON 文件。
     *
     * 只筛 json / 纯文本 / 未知二进制三种 MIME：部分电视文件选择器不认 `application/json`，
     * 不给 `application/octet-stream` 的话从 U 盘里选出来的 .json 会是灰的。
     * 授权只在读的那一下用，不申请持久授权 —— 历史文件读过就完，不需要长期握着 Uri。
     */
    private val pickJsonFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF073Binding.inflate(layoutInflater)
        setContentView(binding.root)

        restore(savedInstanceState)
        if (items.isEmpty()) {
            items.addAll(F073Engine.sampleItems())
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.f073ExportButton.setOnClickListener { exportHistory() }
        binding.f073ImportPickButton.setOnClickListener { openPicker() }
        binding.f073ImportPasteButton.setOnClickListener { pasteFromClipboard() }
        binding.f073ImportTextButton.setOnClickListener { importInputText() }

        renderAll()
        // 进页面焦点先落主动作：遥控器按一下就能导出
        binding.f073ExportButton.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 列表本身就是一份合法 JSON，直接存它，恢复时再 import 回来，省一个 Parcelable
        outState.putString(STATE_ITEMS, F073Engine.export(items))
    }

    // ===== 导出 =====

    /**
     * 导出当前列表。
     *
     * 文件写在 `getExternalFilesDir("export")/tvplayer-history-<时间>.json`，
     * 成功后把绝对路径和真实字节数（`File.length()`，不是字符串长度）回显在按钮下面，
     * 用户能照着路径去文件管理器里找。
     *
     * 只有几条历史、单次写不到 2KB，这里同步写：开线程反而要多处理一层生命周期。
     */
    private fun exportHistory() {
        val json = F073Engine.export(items)
        try {
            val file = F073Engine.writeTo(this, F073Engine.defaultFileName(), json)
            binding.f073ExportResult.text = getString(
                R.string.f073_export_done,
                file.absolutePath,
                F073Engine.formatBytes(file.length()),
                items.size
            )
            binding.f073ExportResult.setTextColor(ContextCompat.getColor(this, R.color.theme_success))
            CustomToast.show(this, getString(R.string.f073_toast_exported))
        } catch (e: Exception) {
            binding.f073ExportResult.text =
                getString(R.string.f073_export_failed, e.message ?: e.javaClass.simpleName)
            binding.f073ExportResult.setTextColor(ContextCompat.getColor(this, R.color.theme_error))
        }
    }

    // ===== 导入 =====

    /** 打开系统文件选择器；个别机型没有可用的选择器，捕获后给一句提示，不让页面崩。 */
    private fun openPicker() {
        runCatching { pickJsonFile.launch(PICK_MIME_TYPES) }.onFailure { e ->
            CustomToast.show(
                this,
                getString(R.string.f073_import_file_read_failed, e.message ?: e.javaClass.simpleName)
            )
        }
    }

    /**
     * 从 Uri 读文件并直接导入。
     *
     * 读出来的文本顺手回填到文本框：一是用户能看见自己到底导了哪一份，
     * 二是读错了还能手动改几个字符再点一次「导入下面的文本」。
     * 文本超过 [INLINE_ECHO_LIMIT] 就只导入不回填，免得几万字符把输入框撑爆。
     */
    private fun importFrom(uri: Uri) {
        val text = try {
            F073Engine.readFrom(this, uri)
        } catch (e: Exception) {
            CustomToast.show(
                this,
                getString(R.string.f073_import_file_read_failed, e.message ?: e.javaClass.simpleName)
            )
            return
        }
        if (text.length <= INLINE_ECHO_LIMIT) {
            binding.f073PasteInput.setText(text)
        }
        applyImport(text)
    }

    /** 导入文本框里的内容（粘贴进来的、或者手动写的一段）。 */
    private fun importInputText() {
        applyImport(binding.f073PasteInput.text?.toString().orEmpty())
    }

    /**
     * 真正执行一次导入。
     *
     * 判定全交给 [F073Engine.import]：按现有 id 去重、坏条目计数、不抛异常。
     * 有新条目才合并（合并后按最后播放时间倒序），然后把三个数字和导入后的总条目数刷出来。
     */
    private fun applyImport(text: String) {
        if (text.isBlank()) {
            CustomToast.show(this, getString(R.string.f073_import_empty_text))
            return
        }

        val result = F073Engine.import(text, F073Engine.idsOf(items))
        if (result.ok && result.items.isNotEmpty()) {
            val merged = F073Engine.merge(items, result.items)
            items.clear()
            items.addAll(merged)
        }

        renderImportStats(result)
        renderList()

        if (result.ok) {
            CustomToast.show(this, getString(R.string.f073_toast_imported))
        }
    }

    /** 从剪贴板取文本填进文本框；剪贴板是空的就直说，别静默什么都不做。 */
    private fun pasteFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }

        if (text.isBlank()) {
            CustomToast.show(this, getString(R.string.f073_clipboard_empty))
            return
        }

        binding.f073PasteInput.setText(text)
        binding.f073PasteInput.requestFocus()
        CustomToast.show(this, getString(R.string.f073_clipboard_pasted))
    }

    // ===== 渲染 =====

    private fun renderAll() {
        renderList()
        renderCounts()
    }

    /** 统计行：有新条目是成功色，有非法条目是警告色，整份读不成是错误色。 */
    private fun renderImportStats(result: F073Engine.ImportResult) {
        val error = result.error
        binding.f073ImportStats.text = if (error != null) {
            getString(
                R.string.f073_import_stats_with_error,
                result.added,
                result.skipped,
                result.invalid,
                error
            )
        } else {
            getString(R.string.f073_import_stats, result.added, result.skipped, result.invalid)
        }
        binding.f073ImportStats.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    error != null -> R.color.theme_error
                    result.invalid > 0 -> R.color.theme_warning
                    result.added > 0 -> R.color.theme_success
                    else -> R.color.theme_text_secondary
                }
            )
        )
        renderCounts()
    }

    /** 导入后条目数 + 列表小节那行「本地共 N 条」。 */
    private fun renderCounts() {
        binding.f073ImportTotal.text = getString(R.string.f073_import_total, items.size)
        binding.f073ListCount.text = getString(R.string.f073_list_count, items.size)
    }

    /** 列表按 [items] 重建：一行标题 + 一行「最后播放时间 · 进度」。 */
    private fun renderList() {
        val container = binding.f073ListContainer
        container.removeAllViews()
        binding.f073ListEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item -> container.addView(rowView(item)) }
    }

    private fun rowView(item: F073Engine.HistoryItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val title = TextView(this).apply {
            text = item.title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@F073Activity, R.color.theme_text_primary))
        }

        val meta = TextView(this).apply {
            text = getString(
                R.string.f073_row_meta,
                F073Engine.formatMoment(item.playedAtMs),
                item.progressText()
            )
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@F073Activity, R.color.theme_text_secondary))
        }

        row.addView(title)
        row.addView(meta)
        return row
    }

    /** 旋转 / 进程重建后把列表拿回来：存的是导出 JSON，恢复就再 import 一遍。 */
    private fun restore(state: Bundle?) {
        val saved = state?.getString(STATE_ITEMS)?.takeIf { it.isNotBlank() } ?: return
        val restored = F073Engine.import(saved, emptySet()).items
        if (restored.isNotEmpty()) {
            items.clear()
            items.addAll(restored)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_ITEMS = "f073_items"

        /** 文件选择器的 MIME 过滤：json 优先，兜底给纯文本与未知二进制。 */
        val PICK_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

        /** 读回来的文本超过这个长度就不回填文本框，只导入。 */
        const val INLINE_ECHO_LIMIT = 8_000
    }
}
