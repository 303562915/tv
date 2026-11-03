package com.tv.ball.presentation.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.ball.databinding.DialogVideoInfoBinding
import java.text.DecimalFormat

/**
 * 视频详细信息对话框
 */
class VideoInfoDialog : DialogFragment() {
    
    private var _binding: DialogVideoInfoBinding? = null
    private val binding get() = _binding!!
    
    private var videoInfo: VideoInfo? = null
    
    data class VideoInfo(
        val title: String,
        val url: String,
        val width: Int,
        val height: Int,
        val duration: Long,
        val currentPosition: Long,
        val bitrate: Long = 0,
        val fps: Float = 0f,
        val codec: String = "未知",
        val audioCodec: String = "未知",
        val audioChannels: Int = 0,
        val audioSampleRate: Int = 0,
        val fileSize: Long = 0
    )
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogVideoInfoBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        displayVideoInfo()
    }
    
    private fun displayVideoInfo() {
        videoInfo?.let { info ->
            binding.apply {
                // 基本信息
                videoTitleText.text = info.title
                videoUrlText.text = info.url
                
                // 视频信息
                val resolution = "${info.width} × ${info.height}"
                videoResolutionText.text = resolution
                
                val quality = getVideoQuality(info.width, info.height)
                videoQualityText.text = quality
                
                videoDurationText.text = formatTime(info.duration)
                videoPositionText.text = formatTime(info.currentPosition)
                
                val progress = if (info.duration > 0) {
                    (info.currentPosition * 100 / info.duration).toInt()
                } else 0
                videoProgressText.text = "$progress%"
                
                // 编码信息
                videoCodecText.text = info.codec
                audioCodecText.text = info.audioCodec
                
                if (info.bitrate > 0) {
                    videoBitrateText.text = formatBitrate(info.bitrate)
                } else {
                    videoBitrateText.text = "未知"
                }
                
                if (info.fps > 0) {
                    videoFpsText.text = String.format("%.2f fps", info.fps)
                } else {
                    videoFpsText.text = "未知"
                }
                
                // 音频信息
                if (info.audioChannels > 0) {
                    audioChannelsText.text = when (info.audioChannels) {
                        1 -> "单声道"
                        2 -> "立体声"
                        6 -> "5.1 环绕声"
                        8 -> "7.1 环绕声"
                        else -> "${info.audioChannels} 声道"
                    }
                } else {
                    audioChannelsText.text = "未知"
                }
                
                if (info.audioSampleRate > 0) {
                    audioSampleRateText.text = "${info.audioSampleRate / 1000} kHz"
                } else {
                    audioSampleRateText.text = "未知"
                }
                
                // 文件信息
                if (info.fileSize > 0) {
                    fileSizeText.text = formatFileSize(info.fileSize)
                } else {
                    fileSizeText.text = "未知"
                }
                
                // 关闭按钮
                closeButton.setOnClickListener {
                    dismiss()
                }
            }
        }
    }
    
    private fun getVideoQuality(width: Int, height: Int): String {
        val minDimension = minOf(width, height)
        return when {
            minDimension >= 2160 -> "4K 超高清"
            minDimension >= 1440 -> "2K 高清"
            minDimension >= 1080 -> "1080p 全高清"
            minDimension >= 720 -> "720p 高清"
            minDimension >= 480 -> "480p 标清"
            minDimension >= 360 -> "360p 流畅"
            else -> "低画质"
        }
    }
    
    private fun formatTime(ms: Long): String {
        if (ms < 0 || ms == Long.MAX_VALUE) {
            return "00:00"
        }
        
        val seconds = (ms / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%02d:%02d", minutes, seconds % 60)
        }
    }
    
    private fun formatBitrate(bitrate: Long): String {
        val kbps = bitrate / 1000
        return if (kbps >= 1000) {
            String.format("%.2f Mbps", kbps / 1000.0)
        } else {
            "$kbps Kbps"
        }
    }
    
    private fun formatFileSize(size: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            size >= 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024 * 1024))} GB"
            size >= 1024 * 1024 -> "${df.format(size / (1024.0 * 1024))} MB"
            size >= 1024 -> "${df.format(size / 1024.0)} KB"
            else -> "$size B"
        }
    }
    
    fun setVideoInfo(info: VideoInfo) {
        this.videoInfo = info
        if (_binding != null) {
            displayVideoInfo()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        const val TAG = "VideoInfoDialog"
        
        fun newInstance(info: VideoInfo): VideoInfoDialog {
            return VideoInfoDialog().apply {
                videoInfo = info
            }
        }
    }
}
