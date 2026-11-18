package com.tv.ball.presentation.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.databinding.ItemDownloadBinding
import com.tv.ball.domain.downloader.DownloadStatus
import com.tv.ball.domain.downloader.DownloadTask
import java.io.File

class DownloadAdapter(
    private val onPauseClick: (DownloadTask) -> Unit,
    private val onResumeClick: (DownloadTask) -> Unit,
    private val onDeleteClick: (DownloadTask) -> Unit,
    private val onPlayClick: (DownloadTask) -> Unit,
    private val onCopyLinkClick: (DownloadTask) -> Unit,
    private val onLongClick: (DownloadTask) -> Unit,
    private val onMergeClick: ((DownloadTask) -> Unit)? = null
) : ListAdapter<DownloadTask, DownloadAdapter.ViewHolder>(DiffCallback()) {

    private val selectedItems = mutableSetOf<Long>()
    var isSelectionMode = false
        private set
    
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun toggleSelection(taskId: Long) {
        if (selectedItems.contains(taskId)) {
            selectedItems.remove(taskId)
        } else {
            selectedItems.add(taskId)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun selectAll() {
        selectedItems.clear()
        currentList.forEach { selectedItems.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun clearSelection() {
        selectedItems.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedTasks(): List<DownloadTask> {
        return currentList.filter { selectedItems.contains(it.id) }
    }

    fun enterSelectionMode() {
        isSelectionMode = true
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(task: DownloadTask) {
            val isSelected = selectedItems.contains(task.id)
            
            // 电影胶片图标始终显示，使用灰色图标
            binding.ivIcon.visibility = View.VISIBLE
            binding.ivIcon.setImageResource(com.tv.ball.R.drawable.ic_film_gray)
            
            // 选择模式下且选中时，用绿色圆形对勾覆盖电影胶片图标
            if (isSelectionMode && isSelected) {
                binding.ivCheckmark.visibility = View.VISIBLE
                binding.ivCheckmark.setImageResource(com.tv.ball.R.drawable.ic_mtrl_checked_circle)
                binding.ivCheckmark.setColorFilter(android.graphics.Color.parseColor("#4CAF50")) // 绿色
            } else {
                binding.ivCheckmark.visibility = View.GONE
            }
            
            // 图标点击
            binding.ivIcon.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(task.id)
                }
            }
            binding.ivCheckmark.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(task.id)
                }
            }
            
            // 文件名 - 显示续传信息（"确定"为绿色）
            if (task.downloadedSize > 0 && task.status != DownloadStatus.COMPLETED) {
                val fileName = task.fileName
                val resumeText = " (续传: "
                val confirmText = "确定"
                val endText = ")"
                
                val spannableString = android.text.SpannableString("$fileName$resumeText$confirmText$endText")
                val confirmStart = fileName.length + resumeText.length
                val confirmEnd = confirmStart + confirmText.length
                
                spannableString.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#4CAF50")), // 绿色
                    confirmStart,
                    confirmEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                binding.tvFileName.text = spannableString
            } else {
                binding.tvFileName.text = task.fileName
            }
            
            // 根据进度设置背景颜色
            if (task.threadCount > 1 && task.partProgresses.isNotEmpty() && 
                (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED)) {
                // 多线程下载 - 显示分段背景
                binding.progressBackground.visibility = View.GONE
                binding.llSegmentedProgress.visibility = View.VISIBLE
                binding.llSegmentedProgress.removeAllViews()
                
                android.util.Log.d("DownloadAdapter", "显示分段背景: ${task.title}, 线程数=${task.threadCount}")
                
                // 为每个线程创建一个背景段
                task.partProgresses.forEachIndexed { index, progress ->
                    val segmentView = View(binding.root.context).apply {
                        // 根据进度设置颜色
                        val segmentColor = when {
                            progress >= 100 -> android.graphics.Color.parseColor("#C8E6C9") // 深绿 - 已完成
                            progress >= 75 -> android.graphics.Color.parseColor("#E8F5E9")  // 浅绿 - 75%+
                            progress >= 50 -> android.graphics.Color.parseColor("#FFF9C4")  // 浅黄 - 50%+
                            progress >= 25 -> android.graphics.Color.parseColor("#E3F2FD")  // 浅蓝 - 25%+
                            progress > 0 -> android.graphics.Color.parseColor("#F5F5F5")    // 浅灰 - 开始
                            else -> android.graphics.Color.parseColor("#FFFFFF")            // 白色 - 未开始
                        }
                        
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        ).apply {
                            if (index > 0) marginStart = 2 // 分段之间的间隙
                        }
                        setBackgroundColor(segmentColor)
                    }
                    
                    binding.llSegmentedProgress.addView(segmentView)
                }
            } else {
                // 单线程或M3U8下载 - 显示单一进度背景
                binding.llSegmentedProgress.visibility = View.GONE
                binding.progressBackground.visibility = View.VISIBLE
                
                val progressColor = when {
                    task.status == DownloadStatus.FAILED -> android.graphics.Color.parseColor("#FFEBEE") // 浅红
                    task.progress >= 90 -> android.graphics.Color.parseColor("#E8F5E9") // 浅绿
                    task.progress >= 50 -> android.graphics.Color.parseColor("#FFF9C4") // 浅黄
                    else -> android.graphics.Color.parseColor("#E3F2FD") // 浅蓝
                }
                binding.progressBackground.setBackgroundColor(progressColor)
                
                // 设置进度条宽度
                binding.root.post {
                    val width = binding.root.width
                    if (width > 0) {
                        val progressWidth = (width * task.progress / 100)
                        val params = binding.progressBackground.layoutParams
                        params.width = progressWidth
                        binding.progressBackground.layoutParams = params
                    }
                }
            }

            // 状态行
            if (task.isM3u8 && task.m3u8Segments.isNotEmpty()) {
                val totalSegments = task.m3u8Segments.size
                val downloadedSegments = task.downloadedSize.toInt()
                
                if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED) {
                    // 格式: "10% - 第 20 个，共 3238 个"（不显示估算大小，避免跳动）
                    binding.tvStatusLine.text = "${task.progress}% - 第 $downloadedSegments 个，共 $totalSegments 个"
                    
                    // 如果分片已全部下载但未合并，显示提示
                    if (downloadedSegments == totalSegments && task.status == DownloadStatus.PAUSED) {
                        binding.tvStatusLine.text = "${binding.tvStatusLine.text}\n⚠️ 分片已下载完成，长按合并"
                        binding.tvStatusLine.setTextColor(android.graphics.Color.parseColor("#FF9800")) // 橙色
                    } else {
                        binding.tvStatusLine.setTextColor(android.graphics.Color.parseColor("#666666"))
                    }
                } else if (task.status == DownloadStatus.COMPLETED) {
                    val finalFile = File(task.savePath)
                    val finalSizeMB = finalFile.length() / (1024.0 * 1024.0)
                    val finalSizeGB = finalSizeMB / 1024.0
                    if (finalSizeGB >= 1.0) {
                        binding.tvStatusLine.text = "100% | ${String.format("%.2f", finalSizeGB)}GB - 共 $totalSegments 个分片"
                    } else {
                        binding.tvStatusLine.text = "100% | ${String.format("%.2f", finalSizeMB)}MB - 共 $totalSegments 个分片"
                    }
                    binding.tvStatusLine.setTextColor(android.graphics.Color.parseColor("#666666"))
                } else if (task.status == DownloadStatus.MERGING) {
                    binding.tvStatusLine.text = "合并中 - 共 $totalSegments 个分片"
                    binding.tvStatusLine.setTextColor(android.graphics.Color.parseColor("#2196F3")) // 蓝色
                } else {
                    binding.tvStatusLine.text = "${task.progress}%"
                    binding.tvStatusLine.setTextColor(android.graphics.Color.parseColor("#666666"))
                }
            } else {
                // 普通视频 - 显示实际大小
                if (task.totalSize > 0) {
                    val downloadedMB = task.downloadedSize / (1024.0 * 1024.0)
                    val totalMB = task.totalSize / (1024.0 * 1024.0)
                    val downloadedGB = downloadedMB / 1024.0
                    val totalGB = totalMB / 1024.0
                    
                    if (totalGB >= 1.0) {
                        binding.tvStatusLine.text = "${task.progress}% | ${String.format("%.2f", downloadedGB)}GB / ${String.format("%.2f", totalGB)}GB"
                    } else {
                        binding.tvStatusLine.text = "${task.progress}% | ${String.format("%.0f", downloadedMB)}MB / ${String.format("%.0f", totalMB)}MB"
                    }
                } else {
                    binding.tvStatusLine.text = "${task.progress}%"
                }
            }

            // 显示错误信息
            if (task.status == DownloadStatus.FAILED && !task.errorMessage.isNullOrEmpty()) {
                binding.tvErrorMessage.text = "错误: ${task.errorMessage}"
                binding.tvErrorMessage.visibility = View.VISIBLE
            } else {
                binding.tvErrorMessage.visibility = View.GONE
            }

            // 显示多线程进度条 - 隐藏，只用item背景显示
            binding.llMultiThreadProgress.visibility = View.GONE

            // 右侧不显示续传状态（已在标题显示）
            binding.tvResumeStatus.visibility = View.GONE

            // 状态文字和剩余时间 - 始终显示
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvTime.visibility = View.VISIBLE
            
            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    // 显示实时网速
                    if (task.speed > 0) {
                        val speedKB = task.speed / 1024.0
                        val speedMB = speedKB / 1024.0
                        
                        val speedText = if (speedMB >= 1.0) {
                            String.format("%.2f MB/s", speedMB)
                        } else {
                            String.format("%.0f KB/s", speedKB)
                        }
                        
                        binding.tvStatus.text = speedText
                    } else {
                        binding.tvStatus.text = "0 KB/s"
                    }
                    
                    // 简单计算：大小除以速度
                    if (task.totalSize > 0 && task.downloadedSize > 0) {
                        // 计算已用时间（毫秒转秒）
                        val elapsedTime = (System.currentTimeMillis() - task.createTime) / 1000.0 // 秒
                        if (elapsedTime > 1) {
                            // 计算平均速度（字节/秒）
                            val avgSpeed = if (task.isM3u8) {
                                // M3U8: downloadedSize 是分片数，需要估算字节数
                                val downloadedBytes = task.downloadedSize * 500 * 1024 // 假设每个分片 500KB
                                downloadedBytes / elapsedTime
                            } else {
                                // 普通下载: downloadedSize 就是字节数
                                task.downloadedSize / elapsedTime
                            }
                            
                            val remainingBytes = if (task.isM3u8) {
                                // M3U8: 剩余分片数 * 500KB
                                (task.totalSize - task.downloadedSize) * 500 * 1024
                            } else {
                                // 普通下载: 剩余字节数
                                task.totalSize - task.downloadedSize
                            }
                            
                            if (avgSpeed > 0) {
                                val remainingSeconds = (remainingBytes / avgSpeed).toLong()
                                
                                val hours = remainingSeconds / 3600
                                val minutes = (remainingSeconds % 3600) / 60
                                val seconds = remainingSeconds % 60
                                
                                val timeStr = when {
                                    hours > 0 -> String.format("%dh%dm", hours, minutes)
                                    minutes > 0 -> String.format("%dm%ds", minutes, seconds)
                                    else -> String.format("%ds", seconds)
                                }
                                
                                binding.tvTime.text = " 约$timeStr"
                            } else {
                                binding.tvTime.text = " 计算中..."
                            }
                        } else {
                            binding.tvTime.text = " 计算中..."
                        }
                    } else {
                        binding.tvTime.text = " 计算中..."
                    }
                }
                DownloadStatus.PAUSED -> {
                    binding.tvStatus.text = "已暂停"
                    binding.tvTime.text = " -"
                }
                DownloadStatus.COMPLETED -> {
                    binding.tvStatus.text = "已完成"
                    binding.tvTime.text = " -"
                }
                DownloadStatus.FAILED -> {
                    binding.tvStatus.text = "失败"
                    binding.tvTime.text = " -"
                }
                DownloadStatus.MERGING -> {
                    binding.tvStatus.text = "合并中"
                    binding.tvTime.text = " -"
                }
                DownloadStatus.PENDING -> {
                    binding.tvStatus.text = "等待中"
                    binding.tvTime.text = " -"
                }
            }

            // 右侧按钮图标 - 始终显示
            binding.ivPlayPause.visibility = View.VISIBLE
            when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.MERGING -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    binding.ivPlayPause.setOnClickListener { 
                        android.util.Log.d("DownloadAdapter", "暂停按钮点击: ${task.title}")
                        onPauseClick(task) 
                    }
                }
                DownloadStatus.PAUSED, DownloadStatus.PENDING -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    binding.ivPlayPause.setOnClickListener { 
                        android.util.Log.d("DownloadAdapter", "=== 恢复按钮点击 ===")
                        android.util.Log.d("DownloadAdapter", "任务: ${task.title}")
                        android.util.Log.d("DownloadAdapter", "任务ID: ${task.id}")
                        android.util.Log.d("DownloadAdapter", "状态: ${task.status}")
                        
                        android.widget.Toast.makeText(
                            binding.root.context, 
                            "开始下载: ${task.title}", 
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        
                        try {
                            onResumeClick(task)
                            android.util.Log.d("DownloadAdapter", "onResumeClick 已调用")
                        } catch (e: Exception) {
                            android.util.Log.e("DownloadAdapter", "onResumeClick 失败", e)
                            android.widget.Toast.makeText(
                                binding.root.context,
                                "启动失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                DownloadStatus.FAILED -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_menu_rotate)
                    binding.ivPlayPause.setOnClickListener { 
                        android.util.Log.d("DownloadAdapter", "重试按钮点击: ${task.title}")
                        onResumeClick(task) // 重试使用resume功能
                    }
                }
                DownloadStatus.COMPLETED -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    binding.ivPlayPause.setOnClickListener { 
                        android.util.Log.d("DownloadAdapter", "播放按钮点击: ${task.title}")
                        onPlayClick(task) 
                    }
                }
            }
            
            // 整个项目点击
            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(task.id)
                } else if (task.status == DownloadStatus.COMPLETED) {
                    // 已完成的任务点击播放
                    android.util.Log.d("DownloadAdapter", "点击已完成任务播放: ${task.title}")
                    onPlayClick(task)
                }
            }
            
            // 长按进入选择模式或显示菜单
            binding.root.setOnLongClickListener {
                android.util.Log.d("DownloadAdapter", "=== 长按事件触发 ===")
                android.util.Log.d("DownloadAdapter", "任务: ${task.title}")
                android.util.Log.d("DownloadAdapter", "isM3u8: ${task.isM3u8}")
                android.util.Log.d("DownloadAdapter", "状态: ${task.status}")
                android.util.Log.d("DownloadAdapter", "isSelectionMode: $isSelectionMode")
                android.util.Log.d("DownloadAdapter", "onMergeClick != null: ${onMergeClick != null}")
                
                if (!isSelectionMode) {
                    // 如果是 M3U8，显示合并选项（不限制状态）
                    if (task.isM3u8 && onMergeClick != null) {
                        android.util.Log.d("DownloadAdapter", "显示 M3U8 上下文菜单")
                        showContextMenu(task)
                    } else {
                        android.util.Log.d("DownloadAdapter", "进入选择模式")
                        enterSelectionMode()
                        toggleSelection(task.id)
                        onLongClick(task)
                    }
                } else {
                    android.util.Log.d("DownloadAdapter", "已在选择模式中")
                }
                true
            }
        }
        
        private fun showContextMenu(task: DownloadTask) {
            android.util.Log.d("DownloadAdapter", "=== showContextMenu 被调用 ===")
            android.util.Log.d("DownloadAdapter", "任务: ${task.title}")
            
            val context = binding.root.context
            val popup = android.widget.PopupMenu(context, binding.root)
            
            // 根据任务状态添加不同的菜单项
            if (task.isM3u8) {
                if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                    popup.menu.add("合并 M3U8 分片")
                }
                if (task.status == DownloadStatus.COMPLETED) {
                    popup.menu.add("播放视频")
                }
            }
            
            popup.menu.add("删除任务")
            popup.menu.add("复制链接")
            
            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                popup.menu.add("继续下载")
            }
            
            popup.setOnMenuItemClickListener { item ->
                android.util.Log.d("DownloadAdapter", "菜单项点击: ${item.title}")
                when (item.title) {
                    "合并 M3U8 分片" -> {
                        onMergeClick?.invoke(task)
                        true
                    }
                    "删除任务" -> {
                        onDeleteClick(task)
                        true
                    }
                    "复制链接" -> {
                        onCopyLinkClick(task)
                        true
                    }
                    "继续下载" -> {
                        onResumeClick(task)
                        true
                    }
                    "播放视频" -> {
                        onPlayClick(task)
                        true
                    }
                    else -> false
                }
            }
            
            android.util.Log.d("DownloadAdapter", "显示弹出菜单")
            popup.show()
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DownloadTask>() {
        override fun areItemsTheSame(oldItem: DownloadTask, newItem: DownloadTask): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadTask, newItem: DownloadTask): Boolean {
            // 比较关键字段，避免不必要的更新
            return oldItem.id == newItem.id &&
                    oldItem.progress == newItem.progress &&
                    oldItem.status == newItem.status &&
                    oldItem.downloadedSize == newItem.downloadedSize &&
                    oldItem.totalSize == newItem.totalSize &&
                    oldItem.speed == newItem.speed &&
                    oldItem.errorMessage == newItem.errorMessage &&
                    oldItem.threadCount == newItem.threadCount &&
                    oldItem.partProgresses == newItem.partProgresses  // 比较分段进度
        }
    }
}