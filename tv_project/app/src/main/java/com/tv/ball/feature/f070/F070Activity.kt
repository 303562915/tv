package com.tv.ball.feature.f070

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF070Binding
import com.tv.ball.util.CustomToast

/**
 * 「Range 请求与流式传输」页。
 *
 * 三段：请求参数（文件总大小 / Range 头 / Content-Type）、三类示例按钮、响应（状态行 + 区间 +
 * Content-Length + 分块数 + 真实响应头）。
 *
 * 这个页面不真的开 socket：它把服务端要做的判断摊开给人看——同一个请求头，
 * [F070Engine.parseRange] 判成 Partial / Full / Unsatisfiable，
 * [F070Engine.statusFor] 给出 206 / 200 / 416，[F070Engine.headersFor] 给出真正会写回去的头。
 * 三个示例按钮就是三类典型请求，点一下把输入框填好再算一遍，输入框里能直接改。
 *
 * 解析是纯算术（不读文件、不碰网络），所以同步跑在主线程即可，不需要协程。
 */
class F070Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF070Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF070Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f070ParseButton.setOnClickListener { render() }
        binding.f070SamplePartialButton.setOnClickListener {
            fillAndRender(DEFAULT_SIZE, "bytes=100-200", DEFAULT_MIME)
        }
        binding.f070SampleFullButton.setOnClickListener {
            fillAndRender(DEFAULT_SIZE, "", DEFAULT_MIME)
        }
        binding.f070SampleInvalidButton.setOnClickListener {
            // 起点正好等于总大小：文件只有 0..total-1，这一起点不可满足
            fillAndRender(DEFAULT_SIZE, "bytes=" + DEFAULT_SIZE + "-", DEFAULT_MIME)
        }

        render()
    }

    // ===== 示例 =====

    /** 把示例值写进三个输入框再解析一遍；用户想改就直接改。 */
    private fun fillAndRender(size: String, range: String, mime: String) {
        binding.f070SizeInput.setText(size)
        binding.f070RangeInput.setText(range)
        binding.f070MimeInput.setText(mime)
        render()
    }

    // ===== 解析与渲染 =====

    /**
     * 读三个输入框 → 解析 → 刷新区间 / 长度 / 分块数 / 响应头。
     *
     * 总大小非法时只改状态行，不清掉上一次的结果：用户改错一个字符就整块清空很难受。
     */
    private fun render() {
        val total = binding.f070SizeInput.text.toString().trim().toLongOrNull()
        if (total == null || total <= 0L) {
            setStatus(getString(R.string.f070_parse_error), R.color.theme_error)
            binding.f070Decision.text = getString(R.string.f070_decision_idle)
            binding.f070Decision.setTextColor(ContextCompat.getColor(this, R.color.theme_text_secondary))
            return
        }

        val header = binding.f070RangeInput.text.toString()
        val mime = binding.f070MimeInput.text.toString().trim()
        val range = F070Engine.parseRange(header, total)
        val headers = F070Engine.headersFor(range, total, mime)

        // 状态行
        val colorRes = when (range) {
            is RangeResult.Partial -> R.color.theme_success
            is RangeResult.Full -> R.color.theme_primary
            is RangeResult.Unsatisfiable -> R.color.theme_error
        }
        setStatus(F070Engine.statusLine(range), colorRes)

        // 一句话判定
        binding.f070Decision.text = when (range) {
            is RangeResult.Partial -> getString(R.string.f070_decision_partial)
            is RangeResult.Full -> getString(R.string.f070_decision_full)
            is RangeResult.Unsatisfiable -> getString(R.string.f070_decision_unsatisfiable)
        }
        binding.f070Decision.setTextColor(ContextCompat.getColor(this, colorRes))

        // 区间 / 长度 / 分块
        when (range) {
            is RangeResult.Partial -> {
                binding.f070RangeValue.text =
                    getString(R.string.f070_range_value, range.start, range.end)
                binding.f070LengthValue.text = getString(R.string.f070_length_value, range.length)
                val chunks = F070Engine.chunkCount(range.length)
                binding.f070ChunkValue.text = if (chunks <= 0L) {
                    getString(R.string.f070_chunk_none)
                } else {
                    getString(
                        R.string.f070_chunk_value,
                        chunks,
                        F070Engine.DEFAULT_CHUNK_SIZE / 1024
                    )
                }
            }

            is RangeResult.Full -> {
                // 整份发送：区间就是 0..total-1，仍然按块发，只是不带 Content-Range
                binding.f070RangeValue.text =
                    getString(R.string.f070_range_value, 0L, total - 1L)
                binding.f070LengthValue.text = getString(R.string.f070_length_value, total)
                binding.f070ChunkValue.text = getString(
                    R.string.f070_chunk_value,
                    F070Engine.chunkCount(total),
                    F070Engine.DEFAULT_CHUNK_SIZE / 1024
                )
            }

            is RangeResult.Unsatisfiable -> {
                // 416 没有响应体，区间与分块都不存在
                binding.f070RangeValue.text = getString(R.string.f070_value_empty)
                binding.f070LengthValue.text = getString(R.string.f070_length_value, 0L)
                binding.f070ChunkValue.text = getString(R.string.f070_chunk_none)
            }
        }

        // 响应头
        binding.f070HeaderText.text = F070Engine.headerText(headers)
        binding.f070HeaderText.setTextColor(
            ContextCompat.getColor(this, R.color.theme_text_primary)
        )
    }

    // ===== 状态 =====

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f070StatusLine.text = message
        binding.f070StatusLine.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** 示例用的总大小：10 MB，够看出 206 与 416 的区别，又不用真的造一个大文件。 */
    private companion object {
        const val DEFAULT_SIZE: String = "10485760"
        const val DEFAULT_MIME: String = "video/mp4"
    }
}
