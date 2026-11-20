package com.tv.ball.feature.f021

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF021Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 缩略图磁盘缓存的管理页。
 *
 * 逻辑都在 [F021Engine]（真实落盘 + mtime 做 LRU）；这里只做触发与渲染，
 * 所有文件操作走 [Dispatchers.IO]，避免大目录统计把遥控器卡住。
 */
class F021Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF021Binding
    private lateinit var engine: F021Engine

    /** 写示例条目时用的源标识；真实播放器里换成视频 path / URL 即可。 */
    private val sampleSources = listOf(
        "/storage/emulated/0/Movies/流浪地球.mkv",
        "/storage/emulated/0/Movies/星际穿越.mkv",
        "/storage/emulated/0/TV/三体/S01E01.mp4"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF021Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F021Engine.open(cacheDir)
        binding.cacheDirPath.text = engine.dir.absolutePath

        binding.btnSeed.setOnClickListener { seed() }
        binding.btnEvict.setOnClickListener { evict() }
        binding.btnClear.setOnClickListener { clear() }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnSeed.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    // region 动作

    /** 写 3 个示例条目，并顺带查一次缓存：命中率立刻有数可看。 */
    private fun seed() {
        io {
            sampleSources.forEachIndexed { index, source ->
                engine.put(source, ByteArray(1024 * (index + 1)) { i -> (i + index).toByte() })
            }
            // 命中演示：同一个 source 再查，直接拿到文件，不触发任何解码
            sampleSources.forEach { engine.get(it) }
            main {
                toast(getString(R.string.f021_toast_seeded, sampleSources.size))
                refresh()
            }
        }
    }

    /** 按输入框的上限做 LRU 淘汰。 */
    private fun evict() {
        val limit = binding.inputLimit.text.toString().trim().toLongOrNull()
        if (limit == null || limit <= 0L) {
            toast(getString(R.string.f021_toast_bad_limit))
            return
        }
        io {
            val result = engine.evictToLimit(limit)
            main {
                toast(
                    getString(
                        R.string.f021_toast_evicted,
                        result.removedCount,
                        fmt(result.removedBytes),
                        fmt(result.remainingBytes)
                    )
                )
                refresh()
            }
        }
    }

    private fun clear() {
        io {
            val result = engine.clear()
            main {
                toast(getString(R.string.f021_toast_cleared, result.removedCount, fmt(result.removedBytes)))
                refresh()
            }
        }
    }

    private fun refresh() {
        io {
            val stats = engine.stats()
            val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val rows = engine.entries().take(12).map { file ->
                getString(
                    R.string.f021_entry_line,
                    file.name,
                    file.length(),
                    timeFormat.format(Date(file.lastModified()))
                )
            }
            main {
                binding.valueBytes.text = fmt(stats.totalBytes)
                binding.valueEntries.text = stats.entryCount.toString()
                binding.valueHitRate.text = String.format(Locale.US, "%.0f%%", stats.hitRate * 100)
                binding.valueHitCount.text =
                    getString(R.string.f021_hit_count, stats.hitCount, stats.missCount)
                binding.entryList.text = if (rows.isEmpty()) {
                    getString(R.string.f021_empty_item)
                } else {
                    rows.joinToString("\n")
                }
            }
        }
    }

    // endregion

    private fun io(block: () -> Unit) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { showError(it) }
        }
    }

    private fun main(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun showError(e: Throwable) {
        val msg = getString(R.string.f021_error_io, e.message ?: e.javaClass.simpleName)
        runOnUiThread { toast(msg) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}