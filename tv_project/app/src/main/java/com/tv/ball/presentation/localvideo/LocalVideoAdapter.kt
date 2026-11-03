package com.tv.ball.presentation.localvideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.databinding.ItemLocalVideoFolderBinding

class LocalVideoAdapter(
    private val onFolderClick: (VideoFolder) -> Unit
) : ListAdapter<VideoFolder, LocalVideoAdapter.ViewHolder>(DiffCallback()) {
    
    companion object {
        // 创建默认占位图（灰色）
        private val PLACEHOLDER_BITMAP: Bitmap by lazy {
            Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint().apply {
                    color = 0xFFEEEEEE.toInt()
                }
                canvas.drawRect(0f, 0f, 320f, 180f, paint)
            }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocalVideoFolderBinding.inflate(
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
        private val binding: ItemLocalVideoFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(folder: VideoFolder) {
            binding.folderName.text = folder.folderName
            binding.videoCount.text = "${folder.videoCount} 个视频"
            
            // 使用缩略图或占位图
            val bitmap = folder.thumbnail ?: PLACEHOLDER_BITMAP
            binding.thumbnail.setImageBitmap(bitmap)
            
            binding.root.setOnClickListener {
                onFolderClick(folder)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<VideoFolder>() {
        override fun areItemsTheSame(oldItem: VideoFolder, newItem: VideoFolder): Boolean {
            return oldItem.folderPath == newItem.folderPath
        }
        
        override fun areContentsTheSame(oldItem: VideoFolder, newItem: VideoFolder): Boolean {
            return oldItem == newItem
        }
    }
}
