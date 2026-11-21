package com.tv.ball.presentation.localvideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.databinding.ItemVideoFileBinding

class VideoFileAdapter(
    private val onVideoClick: (VideoFile) -> Unit,
    private val onVideoLongClick: (VideoFile) -> Boolean,
    private val isItemSelected: (String) -> Boolean,
    private val isMultiSelectMode: () -> Boolean
) : ListAdapter<VideoFile, VideoFileAdapter.ViewHolder>(DiffCallback()) {
    
    // 占位图底走 inset token，随主题切换重建
    private var placeholderBitmap: Bitmap? = null

    private fun placeholder(context: android.content.Context): Bitmap {
        placeholderBitmap?.let { return it }
        val appContext = context.applicationContext
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint().apply {
                color = androidx.core.content.ContextCompat.getColor(
                    appContext,
                    com.tv.ball.R.color.surface_inset
                )
            }
            canvas.drawRect(0f, 0f, 320f, 180f, paint)
        }
        placeholderBitmap = bitmap
        return bitmap
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(
        private val binding: ItemVideoFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(videoFile: VideoFile) {
            binding.videoName.text = videoFile.name
            binding.videoSize.text = formatFileSize(videoFile.size)
            
            // 使用缩略图或占位图
            val bitmap = videoFile.thumbnail ?: placeholder(binding.root.context)
            binding.thumbnail.setImageBitmap(bitmap)
            
            // 显示/隐藏 CheckBox
            if (isMultiSelectMode()) {
                binding.checkbox.visibility = View.VISIBLE
                binding.checkbox.isChecked = isItemSelected(videoFile.key)
            } else {
                binding.checkbox.visibility = View.GONE
            }
            
            binding.root.setOnClickListener {
                onVideoClick(videoFile)
            }
            
            binding.root.setOnLongClickListener {
                onVideoLongClick(videoFile)
            }
        }
        
        private fun formatFileSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
                else -> "${size / (1024 * 1024 * 1024)} GB"
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<VideoFile>() {
        override fun areItemsTheSame(oldItem: VideoFile, newItem: VideoFile): Boolean {
            return oldItem.key == newItem.key
        }
        
        override fun areContentsTheSame(oldItem: VideoFile, newItem: VideoFile): Boolean {
            return oldItem == newItem
        }
    }
}

data class VideoFile(
    val name: String,
    /** 真实文件路径；MediaStore 没给 _data 时为空串，此时只能靠 videoId 定位。 */
    val path: String,
    val size: Long,
    val thumbnail: Bitmap?,
    /** MediaStore 行 ID：删除与缩略图用；0 表示只来自文件系统扫描。 */
    val videoId: Long = 0L
) {
    /** 选中态与 Diff 用的稳定标识：有路径用路径，没路径用 MediaStore ID。 */
    val key: String
        get() = if (path.isNotBlank()) path else "id:$videoId"
}

