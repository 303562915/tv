package com.tv.ball.feature.f025

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF025Binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * F025 媒体信息面板。
 *
 * 选文件走 [ActivityResultContracts.OpenDocument]（只筛视频类型），拿到 Uri 后整段
 * 走 [F025Engine.load]（内部 Dispatchers.IO）解析，再用 [render] 铺成「标签-值」行。
 *
 * 两个入口：
 * - 功能中心通过 [F025Module.open] 打开，只带 id；
 * - 详情页 / 本地播放页直接 `new Intent(...).putExtra(EXTRA_URI, ...)`，
 *   面板会把这条 Uri 自动读一遍，不用用户再挑一次。
 */
class F025Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF025Binding

    /** 当前展示的文件；重进页面时从 savedInstanceState 恢复，避免旋转后回退到空态。 */
    private var currentUri: Uri? = null

    /** 解析任务句柄：重新选文件时先取消上一轮，防止旧结果覆盖新结果。 */
    private var loadJob: Job? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                toast(getString(R.string.f025_toast_cancelled))
                return@registerForActivityResult
            }
            // OpenDocument 的授权带 persistable 标记：拿下来之后 URI 在进程重建、设备重启后仍可读
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            show(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF025Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("video/"))
        }
        binding.btnReload.setOnClickListener {
            currentUri?.let { show(it) } ?: toast(getString(R.string.f025_no_file))
        }
        // 遥控器场景：进了页面先把焦点落在主操作上，否则方向键无处可去。
        // post 到布局完成之后再请求，避免 view 还没挂到窗口时请求失败。
        binding.root.post { binding.btnPickFile.requestFocus() }

        val restored = savedInstanceState?.getString(STATE_URI)?.let(Uri::parse)
            ?: intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (restored != null) {
            show(restored)
        } else {
            render(null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString(STATE_URI, it.toString()) }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    // region 解析流程

    private fun show(uri: Uri) {
        currentUri = uri
        loadJob?.cancel()
        binding.statusText.text = getString(R.string.f025_parsing)
        binding.emptyHint.visibility = View.GONE
        binding.infoContainer.visibility = View.VISIBLE
        binding.infoContainer.removeAllViews()

        // lifecycleScope 在 Activity 销毁时自动取消，不用自己持有 Activity 引用
        loadJob = lifecycleScope.launch {
            val result = F025Engine.load(applicationContext, uri)
            when (result) {
                is F025Engine.LoadResult.Success -> render(result.info)
                is F025Engine.LoadResult.Failure -> {
                    // 解析失败也要把文件名留在页面上，其余字段一律「未知」，不伪装成空态
                    render(F025Engine.MediaInfo(displayName = uri.lastPathSegment))
                    toast(getString(R.string.f025_toast_read_fail, result.reason))
                }
            }
        }
    }

    // endregion

    // region 渲染

    /**
     * @param info null 表示空态或解析失败，此时只显示提示行。
     */
    private fun render(info: F025Engine.MediaInfo?) {
        binding.infoContainer.removeAllViews()

        if (info == null) {
            binding.statusText.text = ""
            binding.statusText.visibility = View.GONE
            binding.emptyHint.visibility = View.VISIBLE
            binding.infoContainer.visibility = View.GONE
            return
        }

        binding.emptyHint.visibility = View.GONE
        binding.infoContainer.visibility = View.VISIBLE

        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = info.displayName
            ?: currentUri?.lastPathSegment
            ?: getString(R.string.f025_unknown)

        val unknown = getString(R.string.f025_unknown)
        val density = resources.displayMetrics.density
        val gap = (density * 2).toInt()
        var first = true

        fun addRow(labelRes: Int, value: String?) {
            binding.infoContainer.addView(
                buildRow(getString(labelRes), value ?: unknown, first, gap)
            )
            first = false
        }

        // 视频轨
        val width = info.width
        val height = info.height
        addRow(R.string.f025_label_container, info.container)
        addRow(R.string.f025_label_video_codec, info.videoCodec)
        addRow(
            R.string.f025_label_resolution,
            if (width != null && height != null) {
                getString(R.string.f025_value_resolution, width, height)
            } else {
                null
            }
        )
        addRow(R.string.f025_label_total_bitrate, F025Engine.formatBitrate(info.totalBitrate))
        addRow(R.string.f025_label_duration, F025Engine.formatDuration(info.durationMs))
        addRow(
            R.string.f025_label_frame_rate,
            F025Engine.formatFrameRate(info.frameRate)?.let {
                getString(R.string.f025_value_fps, it)
            }
        )

        // 音频轨
        if (info.audioCodec == null && info.audioTrackCount == 0) {
            addRow(R.string.f025_label_audio_codec, null)
            addRow(R.string.f025_label_audio_channels, null)
            addRow(R.string.f025_label_audio_sample_rate, null)
        } else {
            addRow(R.string.f025_label_audio_codec, info.audioCodec)
            addRow(
                R.string.f025_label_audio_channels,
                info.audioChannels?.let { channels ->
                    val layout = F025Engine.channelLayout(channels)
                    if (layout != null) {
                        getString(R.string.f025_value_channels, channels, layout)
                    } else {
                        getString(R.string.f025_value_channels_only, channels)
                    }
                }
            )
            addRow(
                R.string.f025_label_audio_sample_rate,
                info.audioSampleRate?.let { getString(R.string.f025_value_sample_rate, it.toString()) }
            )
        }
        addRow(R.string.f025_label_audio_bitrate, F025Engine.formatBitrate(info.audioBitrate))

        // HDR 与色彩
        addRow(R.string.f025_label_hdr, describeHdr(info.hdr))
        addRow(R.string.f025_label_color_transfer, info.hdr.transfer)
        addRow(R.string.f025_label_color_primaries, info.hdr.primaries)
        addRow(R.string.f025_label_bit_depth, info.hdr.bitDepth?.let { "$it bit" })

        // 其它
        addRow(
            R.string.f025_label_track_count,
            getString(R.string.f025_value_track_summary, info.trackCount, info.videoTrackCount, info.audioTrackCount)
        )
        addRow(R.string.f025_label_mime, info.mimeType)
        addRow(
            R.string.f025_label_rotation,
            info.rotationDegrees?.let { getString(R.string.f025_value_rotation, it) }
        )
        addRow(R.string.f025_label_size, F025Engine.formatSize(info.fileSizeBytes))

        binding.btnReload.isEnabled = currentUri != null
    }

    private fun describeHdr(hdr: F025Engine.HdrInfo): String = when {
        !hdr.colorInfoAvailable && hdr.bitDepth == null ->
            getString(R.string.f025_hdr_unsupported)
        hdr.isHdr -> getString(R.string.f025_hdr_yes, hdr.transfer ?: "HDR")
        else -> getString(R.string.f025_hdr_no)
    }

    /**
     * 一行「标签 …… 值」。行本身不 focusable：信息只读，焦点应该留给上面的按钮，
     * 否则 D-Pad 会在十几行文本里空转。
     */
    private fun buildRow(label: String, value: String, first: Boolean, gap: Int): View {
        val density = resources.displayMetrics.density
        val padding = (density * 10).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isClickable = false
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (first) 0 else gap }
        }

        val labelView = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@F025Activity, R.color.theme_text_secondary))
            textSize = 14f
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueView = TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@F025Activity, R.color.theme_text_primary))
            textSize = 14f
            gravity = Gravity.END
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
        }

        row.addView(labelView)
        row.addView(valueView)
        return row
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 详情页 / 播放页可以直接带这条 extra 打开，面板会立即解析。 */
        const val EXTRA_URI = "com.tv.ball.feature.f025.EXTRA_URI"
        private const val STATE_URI = "f025_state_uri"
    }
}