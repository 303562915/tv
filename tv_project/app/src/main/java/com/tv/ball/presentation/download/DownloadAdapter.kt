package com.tv.ball.presentation.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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

    /** 只重画勾选视觉的载荷：选择态变化时不再整表重画。 */
    private object SelectionPayload

    /**
     * 单个任务的选择状态切换。
     * 只刷新这一行的勾选视觉（带 payload），不再 notifyDataSetChanged 整表重画。
     */
    fun toggleSelection(taskId: Long) {
        if (!selectedItems.remove(taskId)) {
            selectedItems.add(taskId)
        }
        val position = positionOfTask(taskId)
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, SelectionPayload)
        }
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun selectAll() {
        selectedItems.clear()
        currentList.forEach { selectedItems.add(it.id) }
        notifySelectionVisualChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun clearSelection() {
        val hadVisualState = isSelectionMode || selectedItems.isNotEmpty()
        selectedItems.clear()
        isSelectionMode = false
        if (hadVisualState) {
            notifySelectionVisualChanged()
        }
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedTasks(): List<DownloadTask> {
        return currentList.filter { selectedItems.contains(it.id) }
    }

    fun enterSelectionMode() {
        if (isSelectionMode) return
        isSelectionMode = true
        notifySelectionVisualChanged()
    }

    /**
     * 进度节流刷新入口：调用方按 500ms 攒一批「进行中」的任务丢进来，
     * 这里只对进度条 / 速度 / 剩余时间做行内更新，不触发整表刷新。
     *
     * 载荷直接带新的 [DownloadTask]：ListAdapter 的 currentList 这一刻还是旧值，
     * 用 payload 传新值才能把最新进度画上去。
     */
    fun updateProgress(tasks: List<DownloadTask>) {
        if (tasks.isEmpty() || itemCount == 0) return

        val positionById = HashMap<Long, Int>(currentList.size)
        currentList.forEachIndexed { index, task -> positionById[task.id] = index }

        tasks.forEach { task ->
            val position = positionById[task.id] ?: return@forEach
            val old = getItem(position)
            if (old.status != task.status) {
                // 状态变了要连右侧按钮和状态文案一起重画
                notifyItemChanged(position)
            } else if (progressVisibleFieldsChanged(old, task)) {
                notifyItemChanged(position, task)
            }
        }
    }

    private fun positionOfTask(taskId: Long): Int {
        currentList.forEachIndexed { index, task ->
            if (task.id == taskId) return index
        }
        return RecyclerView.NO_POSITION
    }

    private fun notifySelectionVisualChanged() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, SelectionPayload)
        }
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

    /**
     * 带 payload 的绑定：进度载荷只画进度条 / 速度 / 剩余时间，
     * 选择载荷只画勾选，避免一次进度跳动就重画整行（文件名、按钮、Spannable 全部重算）。
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position))
            return
        }
        val freshProgress = payloads.filterIsInstance<DownloadTask>().firstOrNull()
        if (freshProgress != null) {
            holder.bindProgressOnly(freshProgress)
            return
        }
        if (payloads.contains(SelectionPayload)) {
            holder.bindSelectionOnly(getItem(position))
            return
        }
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val secondaryText = ContextCompat.getColor(
            binding.root.context,
            com.tv.ball.R.color.theme_text_secondary
        )
        private val primaryText = ContextCompat.getColor(
            binding.root.context,
            com.tv.ball.R.color.theme_primary
        )

        /** 整行绑定：结构 / 点击回调 + 三块可单独刷新的内容。 */
        fun bind(task: DownloadTask) {
            bindSelection(task)
            bindFileName(task)
            bindProgressOnly(task)
            bindErrorMessage(task)
            bindActions(task)
        }

        /** 选择载荷：只更新左侧图标井的勾选视觉。 */
        fun bindSelectionOnly(task: DownloadTask) {
            bindSelection(task)
        }

        /** 进度载荷：只更新进度背景、状态行（百分比 / 分片数）、速度与剩余时间。 */
        fun bindProgressOnly(task: DownloadTask) {
            bindProgressBackground(task)
            bindStatusLine(task)
            bindSpeedAndTime(task)
        }

        private fun bindSelection(task: DownloadTask) {
            val isSelected = selectedItems.contains(task.id)

            // 电影胶片图标始终显示，使用灰色图标
            binding.ivIcon.visibility = View.VISIBLE
            binding.ivIcon.setImageResource(com.tv.ball.R.drawable.ic_film_gray)

            // 选择模式下且选中时，用绿色圆形对勾覆盖电影胶片图标
            if (isSelectionMode && isSelected) {
                binding.ivCheckmark.visibility = View.VISIBLE
                binding.ivCheckmark.setImageResource(com.tv.ball.R.drawable.ic_mtrl_checked_circle)
                binding.ivCheckmark.setColorFilter(
                    ContextCompat.getColor(
                        binding.root.context,
                        com.tv.ball.R.color.theme_primary
                    )
                )
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
        }

        private fun bindFileName(task: DownloadTask) {
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
                    android.text.style.ForegroundColorSpan(primaryText),
                    confirmStart,
                    confirmEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                binding.tvFileName.text = spannableString
            } else {
                binding.tvFileName.text = task.fileName
            }
        }

        private fun bindProgressBackground(task: DownloadTask) {
            if (task.threadCount > 1 && task.partProgresses.isNotEmpty() &&
                (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED)
            ) {
                // 多线程下载 - 显示分段背景
                binding.progressBackground.visibility = View.GONE
                binding.llSegmentedProgress.visibility = View.VISIBLE

                val ctx = binding.root.context
                val segmentColorOf: (Int) -> Int = { progress ->
                    if (progress > 0) {
                        ContextCompat.getColor(ctx, com.tv.ball.R.color.theme_primary)
                    } else {
                        ContextCompat.getColor(ctx, com.tv.ball.R.color.surface_inset)
                    }
                }

                val segments = task.partProgresses
                if (binding.llSegmentedProgress.childCount == segments.size) {
                    // 段数没变就原地改颜色，别每 500ms 拆了重建
                    segments.forEachIndexed { index, progress ->
                        binding.llSegmentedProgress.getChildAt(index)
                            ?.setBackgroundColor(segmentColorOf(progress))
                    }
                } else {
                    binding.llSegmentedProgress.removeAllViews()
                    android.util.Log.d("DownloadAdapter", "显示分段背景: ${task.title}, 线程数=${task.threadCount}")
                    segments.forEachIndexed { index, progress ->
                        val segmentView = View(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                0,
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                1f
                            ).apply {
                                if (index > 0) marginStart = 2 // 分段之间的间隙
                            }
                            setBackgroundColor(segmentColorOf(progress))
                        }
                        binding.llSegmentedProgress.addView(segmentView)
                    }
                }
            } else {
                // 单线程或M3U8下载 - 显示单一进度背景
                binding.llSegmentedProgress.visibility = View.GONE
                binding.progressBackground.visibility = View.VISIBLE

                val progressColor = if (task.status == DownloadStatus.FAILED) {
                    ContextCompat.getColor(binding.root.context, com.tv.ball.R.color.surface_inset)
                } else {
                    // 进度用 theme_primary，轨道用 surface_block
                    ContextCompat.getColor(binding.root.context, com.tv.ball.R.color.theme_primary)
                }
                binding.progressBackground.setBackgroundColor(progressColor)

                // 设置进度条宽度
                val progress = task.progress
                binding.root.post {
                    val width = binding.root.width
                    if (width > 0) {
                        val progressWidth = (width * progress / 100)
                        val params = binding.progressBackground.layoutParams
                        params.width = progressWidth
                        binding.progressBackground.layoutParams = params
                    }
                }
            }
        }

        private fun bindStatusLine(task: DownloadTask) {
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
                    }
                    binding.tvStatusLine.setTextColor(secondaryText)
                } else if (task.status == DownloadStatus.COMPLETED) {
                    val finalFile = File(task.savePath)
                    val finalSizeMB = finalFile.length() / (1024.0 * 1024.0)
                    val finalSizeGB = finalSizeMB / 1024.0
                    if (finalSizeGB >= 1.0) {
                        binding.tvStatusLine.text = "100% | ${String.format("%.2f", finalSizeGB)}GB - 共 $totalSegments 个分片"
                    } else {
                        binding.tvStatusLine.text = "100% | ${String.format("%.2f", finalSizeMB)}MB - 共 $totalSegments 个分片"
                    }
                    binding.tvStatusLine.setTextColor(secondaryText)
                } else if (task.status == DownloadStatus.MERGING) {
                    binding.tvStatusLine.text = "合并中 - 共 $totalSegments 个分片"
                    binding.tvStatusLine.setTextColor(primaryText)
                } else {
                    binding.tvStatusLine.text = "${task.progress}%"
                    binding.tvStatusLine.setTextColor(secondaryText)
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
                binding.tvStatusLine.setTextColor(secondaryText)
            }
        }

        private fun bindErrorMessage(task: DownloadTask) {
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
        }

        /**
         * 速度与剩余时间。
         *
         * 以前剩余时间只看「总耗时 × 平均速度」，开始几秒里一直停在「计算中...」、
         * 没速度时写死 0 KB/s，看起来像卡住。现在优先用当前实时速度估算剩余时间，
         * 拿不到速度时明确写「连接中…」，不再留一直不动的占位文案。
         */
        private fun bindSpeedAndTime(task: DownloadTask) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvTime.visibility = View.VISIBLE

            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    val speedText = formatSpeed(task.speed)
                    binding.tvStatus.text = speedText ?: "连接中…"

                    val remainingText = estimateRemainingText(task)
                    binding.tvTime.text = if (remainingText != null) " 约$remainingText" else ""
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
        }

        /** 网速文案；speed 为 0 时返回 null，由调用方决定「连接中…」这类文案。 */
        private fun formatSpeed(speed: Long): String? {
            if (speed <= 0) return null
            val speedKB = speed / 1024.0
            val speedMB = speedKB / 1024.0
            return if (speedMB >= 1.0) {
                String.format("%.2f MB/s", speedMB)
            } else {
                String.format("%.0f KB/s", speedKB)
            }
        }

        /**
         * 剩余时间估算：M3U8 按分片数 × 500KB 估，普通下载用剩余字节。
         * 先按当前实时速度算（响应快），速度还没测出来再退回平均速度。
         */
        private fun estimateRemainingText(task: DownloadTask): String? {
            val remainingBytes = if (task.isM3u8) {
                val remainSegments = (task.totalSize - task.downloadedSize).coerceAtLeast(0)
                if (remainSegments <= 0) return null
                remainSegments * 500 * 1024
            } else {
                if (task.totalSize <= 0) return null
                val remain = task.totalSize - task.downloadedSize
                if (remain <= 0) return null
                remain
            }

            val elapsedSeconds = (System.currentTimeMillis() - task.createTime) / 1000.0
            val currentSpeed = task.speed.toDouble()
            val avgSpeed = if (elapsedSeconds > 1) {
                if (task.isM3u8) {
                    (task.downloadedSize * 500.0 * 1024.0) / elapsedSeconds
                } else {
                    task.downloadedSize / elapsedSeconds
                }
            } else {
                0.0
            }

            val speed = when {
                currentSpeed > 0 -> currentSpeed
                avgSpeed > 0 -> avgSpeed
                else -> return null
            }

            val remainingSeconds = (remainingBytes / speed).toLong().coerceAtLeast(0)
            val hours = remainingSeconds / 3600
            val minutes = (remainingSeconds % 3600) / 60
            val seconds = remainingSeconds % 60
            return when {
                hours > 0 -> String.format("%dh%dm", hours, minutes)
                minutes > 0 -> String.format("%dm%ds", minutes, seconds)
                else -> String.format("%ds", seconds)
            }
        }

        /**
         * 右侧暂停/继续按钮。
         *
         * - 下载中 / 合并中 → 暂停图标，点击暂停；
         * - 已暂停 / 等待中 → 继续图标，点击继续（走 Range 断点续传）；
         * - 已完成 / 失败 → 按钮禁用（置灰 + 撤掉点击），这两种状态没有可暂停/继续的动作。
         *
         * 图标、contentDescription、可用状态三者始终一起刷，避免复用行时残留上一行的状态。
         */
        private fun bindActions(task: DownloadTask) {
            // 先复位：列表行会被复用，不先复位就会带着上一行的禁用态/监听器
            binding.ivPlayPause.visibility = View.VISIBLE
            binding.ivPlayPause.alpha = 1f
            binding.ivPlayPause.isEnabled = true
            binding.ivPlayPause.isClickable = true
            binding.ivPlayPause.setOnClickListener(null)

            when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.MERGING -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    // 图标 + contentDescription 一起随状态切换，读屏用户也能分辨当前可执行的动作
                    binding.ivPlayPause.contentDescription =
                        if (task.status == DownloadStatus.MERGING) {
                            "合并中：${task.fileName}"
                        } else {
                            "暂停下载：${task.fileName}"
                        }
                    binding.ivPlayPause.setOnClickListener {
                        android.util.Log.d("DownloadAdapter", "暂停按钮点击: ${task.title}")
                        onPauseClick(task)
                    }
                }
                DownloadStatus.PAUSED, DownloadStatus.PENDING -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    binding.ivPlayPause.contentDescription = "继续下载：${task.fileName}"
                    binding.ivPlayPause.setOnClickListener {
                        android.util.Log.d("DownloadAdapter", "恢复按钮点击: ${task.title}, 状态=${task.status}")
                        try {
                            onResumeClick(task)
                        } catch (e: Exception) {
                            android.util.Log.e("DownloadAdapter", "onResumeClick 失败", e)
                            android.widget.Toast.makeText(
                                binding.root.context,
                                "继续下载失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                DownloadStatus.COMPLETED -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    binding.ivPlayPause.contentDescription = "已完成，不可暂停：${task.fileName}"
                    disablePlayPause()
                }
                DownloadStatus.FAILED -> {
                    binding.ivPlayPause.setImageResource(android.R.drawable.ic_menu_rotate)
                    binding.ivPlayPause.contentDescription = "下载失败，不可暂停：${task.fileName}"
                    disablePlayPause()
                }
            }

            // 整个项目点击：已完成 → 播放；失败 → 重试（按钮禁用后保留这条重试入口）
            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(task.id)
                } else if (task.status == DownloadStatus.COMPLETED) {
                    android.util.Log.d("DownloadAdapter", "点击已完成任务播放: ${task.title}")
                    onPlayClick(task)
                } else if (task.status == DownloadStatus.FAILED) {
                    android.util.Log.d("DownloadAdapter", "点击失败任务重试: ${task.title}")
                    onResumeClick(task)
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

        /** 已完成 / 失败：暂停-继续按钮置灰并撤掉点击，避免点了没有任何动作。 */
        private fun disablePlayPause() {
            binding.ivPlayPause.isEnabled = false
            binding.ivPlayPause.isClickable = false
            binding.ivPlayPause.alpha = 0.35f
            binding.ivPlayPause.setOnClickListener(null)
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

        /**
         * 身份键：任务 id；id 还没落库（0）时退回 url，保证同一任务在插入前后不会被当成两条。
         */
        override fun areItemsTheSame(oldItem: DownloadTask, newItem: DownloadTask): Boolean {
            if (oldItem.id != 0L && newItem.id != 0L) {
                return oldItem.id == newItem.id
            }
            return oldItem.url == newItem.url && oldItem.savePath == newItem.savePath
        }

        /** 内容比较覆盖进度、速度、状态、分片与分段进度等会出现在行内的字段。 */
        override fun areContentsTheSame(oldItem: DownloadTask, newItem: DownloadTask): Boolean {
            return oldItem.progress == newItem.progress &&
                    oldItem.status == newItem.status &&
                    oldItem.downloadedSize == newItem.downloadedSize &&
                    oldItem.totalSize == newItem.totalSize &&
                    oldItem.speed == newItem.speed &&
                    oldItem.errorMessage == newItem.errorMessage &&
                    oldItem.threadCount == newItem.threadCount &&
                    oldItem.partProgresses == newItem.partProgresses &&
                    oldItem.fileName == newItem.fileName &&
                    oldItem.savePath == newItem.savePath &&
                    oldItem.m3u8Segments.size == newItem.m3u8Segments.size
        }
    }

    private companion object {
        /** 行内进度载荷会变化的字段：变了才 notifyItemChanged(payload)。 */
        fun progressVisibleFieldsChanged(old: DownloadTask, new: DownloadTask): Boolean =
            old.progress != new.progress ||
                    old.speed != new.speed ||
                    old.downloadedSize != new.downloadedSize ||
                    old.totalSize != new.totalSize ||
                    old.partProgresses != new.partProgresses
    }
}