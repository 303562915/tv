package com.tv.ball.presentation.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.tv.ball.R
import com.tv.ball.databinding.ItemVideoBinding
import com.tv.ball.util.ImageLoader

class VideoListAdapter(
    private val onVideoClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoListAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(
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
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(video: VideoItem) {
            binding.videoTitle.text = video.title
            binding.videoQuality.text = video.quality
            
            // 处理空的封面 URL
            val coverUrl = if (video.coverUrl.isNullOrEmpty()) {
                android.util.Log.d("VideoListAdapter", "封面 URL 为空，使用占位图: ${video.title}")
                // 使用一个占位图URL或者设置背景色
                binding.videoCover.setBackgroundColor(0xFFE0E0E0.toInt()) // 浅灰色背景
                null
            } else {
                binding.videoCover.setBackgroundColor(0x00000000) // 透明背景
                video.coverUrl
            }
            
            // 使用优化的ImageLoader，根据位置设置优先级
            // 不使用占位图，空图片时显示空白
            val priority = if (position < 9) Priority.HIGH else Priority.NORMAL
            ImageLoader.load(
                context = binding.root.context,
                url = coverUrl,
                imageView = binding.videoCover,
                placeholder = 0, // 不使用占位图
                priority = priority
            )
            
            binding.root.setOnClickListener {
                onVideoClick(video)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.detailUrl == newItem.detailUrl
        }
        
        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem == newItem
        }
    }
}
