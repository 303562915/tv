package com.tv.ball.presentation.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemHistoryBinding
import com.tv.ball.domain.model.HistoryItem
import com.tv.ball.util.ImageLoader

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
            binding.videoTitle.text = historyItem.title

            binding.videoEpisode.text = historyItem.currentEpisode?.takeIf { it.isNotEmpty() } ?: "HD"

            val watchedPosition = historyItem.watchedPosition
            val duration = historyItem.duration

            if (duration > 0) {
                val progress = ((watchedPosition.toFloat() / duration) * 100).toInt()
                binding.progressBar.progress = progress
                binding.videoProgress.text =
                    "播放至 ${formatTime(watchedPosition)} / ${formatTime(duration)}"
            } else {
                binding.progressBar.progress = 0
                binding.videoProgress.text = "未开始播放"
            }

            ImageLoader.load(
                context = binding.videoCover.context,
                url = historyItem.coverUrl,
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
            return oldItem.title == newItem.title
        }

        override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}
