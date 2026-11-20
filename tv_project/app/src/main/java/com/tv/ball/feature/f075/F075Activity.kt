package com.tv.ball.feature.f075

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF075Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「CSV 数据导出」页。
 *
 * 三段：
 * 1. **导出**：两个按钮（导出历史 / 导出收藏），点了就现拼 CSV 落盘；
 * 2. **预览**：等宽 TextView 显示将写出的 CSV 前 [F075Engine.PREVIEW_LINES] 条记录，
 *    标题里带逗号和双引号的那几条会原样带着引号出现——这就是"转义有没有生效"的现场证据；
 * 3. **结果**：写入的绝对路径 + 字节数 + 是否回落到了应用目录；
 * 4. **转义示例**：原始标题与写进 CSV 之后的字段上下对照，不必打开文件也能看懂规则。
 *
 * 数据来源：这一版用 [F075Engine.sampleHistory] / [F075Engine.sampleFavorites] 的样例数据，
 * 引擎本身不读任何存储，接上真实历史 / 收藏时只换调用参数，页面与引擎都不用改。
 *
 * 线程：拼 CSV 与写文件都在 IO 上做（低端盒子上写几十 KB 也可能被慢速外置卡拖住几百毫秒），
 * 结果回到主线程只做 setText；连点两次不会起第二个任务。
 */
class F075Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF075Binding

    /** 当前这一轮导出；再次点击前先看它在不在跑。 */
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF075Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f075HistoryButton.setOnClickListener { export(F075Engine.KIND_HISTORY) }
        binding.f075FavoritesButton.setOnClickListener { export(F075Engine.KIND_FAVORITES) }

        binding.f075SampleText.text = sampleText()
        renderIdle()
        setBusy(false)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 初始状态 =====

    /** 还没导出过：预览区给提示，结果区给占位。 */
    private fun renderIdle() {
        setStatus(getString(R.string.f075_status_idle), R.color.theme_text_secondary)
        binding.f075PreviewText.text = getString(R.string.f075_preview_hint)
        binding.f075PreviewNote.text = getString(R.string.f075_preview_note_idle)
        binding.f075ResultPath.text = getString(R.string.f075_result_path_empty)
        binding.f075ResultBytes.text = getString(R.string.f075_result_bytes_empty)
        binding.f075ResultFallback.text = ""
    }

    /**
     * 转义示例卡片：同一份标题「原始 → 写进 CSV」。
     *
     * 用引擎里同一个 [F075Engine.escapeField]，所以这张卡永远和真实导出行为一致，
     * 不会出现"示例说会转义、实际没转义"的尴尬。
     */
    private fun sampleText(): String {
        val raw = getString(R.string.f075_sample_raw)
        val escaped = F075Engine.escapeField(raw)
        return getString(R.string.f075_sample_body, raw, escaped)
    }

    // ===== 导出 =====

    /**
     * 导出一种数据。
     *
     * [kind] 只认 [F075Engine.KIND_HISTORY] / [F075Engine.KIND_FAVORITES]，
     * 给别的值时按历史处理——页面只有两个入口，不值得为参数校验再抛一次错。
     */
    private fun export(kind: String) {
        if (job?.isActive == true) {
            CustomToast.show(this@F075Activity, getString(R.string.f075_toast_busy))
            return
        }
        job = lifecycleScope.launch {
            setBusy(true)
            setStatus(getString(R.string.f075_status_busy, kindLabel(kind)), R.color.theme_text_secondary)
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val csv = when (kind) {
                        F075Engine.KIND_FAVORITES -> F075Engine.favoritesCsv(F075Engine.sampleFavorites())
                        else -> F075Engine.historyCsv(F075Engine.sampleHistory())
                    }
                    csv to F075Engine.writeCsv(this@F075Activity, F075Engine.fileName(kind), csv)
                }
                if (isFinishing || isDestroyed) return@launch

                val csv = outcome.first
                val result = outcome.second

                // 预览：只显示前 10 条记录，标题里带逗号 / 引号 / 换行的那几条会带着引号出现。
                binding.f075PreviewText.text = F075Engine.previewText(csv)
                binding.f075PreviewNote.text = getString(
                    R.string.f075_preview_note,
                    F075Engine.recordCount(csv),
                    F075Engine.PREVIEW_LINES
                )

                binding.f075ResultPath.text = result.path
                binding.f075ResultBytes.text = getString(
                    R.string.f075_result_bytes,
                    result.bytes,
                    result.bytesText()
                )
                binding.f075ResultFallback.text =
                    if (result.fallback) getString(R.string.f075_result_fallback) else ""

                setStatus(
                    getString(R.string.f075_status_written, kindLabel(kind), result.bytesText()),
                    R.color.theme_success
                )
                CustomToast.show(this@F075Activity, getString(R.string.f075_toast_written))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                // 失败时把原因留在状态行，预览区保持上一次的内容，不误导用户"文件已经写好了"。
                setStatus(
                    getString(R.string.f075_status_failed, e.message ?: e.javaClass.simpleName),
                    R.color.theme_error
                )
                binding.f075ResultFallback.text = ""
                CustomToast.show(this@F075Activity, getString(R.string.f075_toast_failed))
            } finally {
                setBusy(false)
            }
        }
    }

    // ===== 界面小工具 =====

    /** 导出中把两个按钮变灰：低端盒子上写文件要等一会儿，避免连点起一堆任务。 */
    private fun setBusy(busy: Boolean) {
        val alpha = if (busy) 0.5f else 1f
        for (button in listOf(binding.f075HistoryButton, binding.f075FavoritesButton)) {
            button.isEnabled = !busy
            button.alpha = alpha
        }
    }

    /** 状态行文案 + 颜色（进行中次要色 / 成功成功色 / 失败错误色）。 */
    private fun setStatus(text: String, colorRes: Int) {
        binding.f075StatusText.text = text
        binding.f075StatusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** 导出种类的人读名字，用在状态行里说明"刚导出的是哪一张"。 */
    private fun kindLabel(kind: String): String = getString(
        if (kind == F075Engine.KIND_FAVORITES) R.string.f075_kind_favorites else R.string.f075_kind_history
    )
}