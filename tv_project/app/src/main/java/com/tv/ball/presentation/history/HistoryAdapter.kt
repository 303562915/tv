package com.tv.ball.presentation.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tv.ball.R
import com.tv.ball.util.ImageLoader
import com.tv.ball.databinding.ItemHistoryBinding

class HistoryAdapter(
    private val onItemClick: (HistoryItem) -> Unit,
    private val onItemDelete: (HistoryItem) -> Unit
) : ListAdapter<HistoryItem, HistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick, onItemDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemHistoryBinding,
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemDelete: (HistoryItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(historyItem: HistoryItem) {
            val video = historyItem.videoItem
            binding.videoTitle.text = video.title
            
            android.util.Log.d("HistoryAdapter", "绑定历史记录: title=${video.title}, coverUrl=${video.coverUrl}")
            
            // 显示当前集数信息
            if (!historyItem.currentEpisode.isNullOrEmpty()) {
                binding.videoEpisode.text = historyItem.currentEpisode
            } else {
                binding.videoEpisode.text = "HD"
            }

            // 显示播放进度
            val watchedPosition = historyItem.watchedPosition
            val duration = historyItem.duration
            
            if (duration > 0) {
                val progress = ((watchedPosition.toFloat() / duration) * 100).toInt()
                binding.progressBar.progress = progress
                
                val watchedTime = formatTime(watchedPosition)
                val totalTime = formatTime(duration)
                binding.videoProgress.text = "播放至 $watchedTime / $totalTime"
            } else {
                binding.progressBar.progress = 0
                binding.videoProgress.text = "未开始播放"
            }

            ImageLoader.load(
                context = binding.videoCover.context,
                url = video.coverUrl,
                imageView = binding.videoCover,
                placeholder = R.drawable.ic_launcher_background
            )

            binding.root.setOnClickListener {
                onItemClick(historyItem)
            }
        }
        
        private fun formatTime(milliseconds: Long): String {
            val seconds = milliseconds / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            } else {
                String.format("%d:%02d", minutes, seconds % 60)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryItem>() {
        override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return oldItem.videoItem.title == newItem.videoItem.title
        }

        override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}
