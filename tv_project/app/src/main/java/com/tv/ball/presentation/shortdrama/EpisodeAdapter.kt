package com.tv.ball.presentation.shortdrama

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemEpisodeTencentBinding

class EpisodeAdapter(
    private val onItemClick: (EpisodeItem) -> Unit
) : ListAdapter<EpisodeItem, EpisodeAdapter.ViewHolder>(DiffCallback()) {

    private var selectedPosition = -1  // 当前选中的位置

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeTencentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position, position == selectedPosition)
    }

    /**
     * 设置选中的剧集
     */
    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        
        // 刷新旧的和新的选中项
        if (oldPosition != -1) {
            notifyItemChanged(oldPosition)
        }
        if (selectedPosition != -1) {
            notifyItemChanged(selectedPosition)
        }
    }

    class ViewHolder(
        private val binding: ItemEpisodeTencentBinding,
        private val onItemClick: (EpisodeItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: EpisodeItem, position: Int, isSelected: Boolean) {
            // 显示集数
            binding.episodeNumber.text = item.episodeNum.toString()
            binding.episodeNameText.text = "第${item.episodeNum}集"
            
            // 判断是否为VIP集数（假设第5集及以后为VIP）
            val isVip = position >= 4
            
            // 显示/隐藏VIP标签
            binding.vipBadge.visibility = if (isVip) android.view.View.VISIBLE else android.view.View.GONE
            
            // 高亮当前播放的集数（使用腾讯风格）
            if (isSelected) {
                // 选中状态：使用主题色背景 + 白色文字
                binding.episodeCard.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.primary)
                )
                binding.episodeNumber.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.white)
                )
                binding.episodeNameText.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.white)
                )
            } else {
                // 未选中状态：白色背景 + 深色文字
                binding.episodeCard.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.white)
                )
                binding.episodeNumber.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.black)
                )
                binding.episodeNameText.setTextColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.darker_gray)
                )
            }
            
            // 添加点击动画效果
            binding.episodeCard.setOnClickListener {
                // 添加点击缩放动画
                it.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(100)
                    .withEndAction {
                        it.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
                
                onItemClick(item)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<EpisodeItem>() {
        override fun areItemsTheSame(oldItem: EpisodeItem, newItem: EpisodeItem): Boolean {
            return oldItem.videoId == newItem.videoId
        }

        override fun areContentsTheSame(oldItem: EpisodeItem, newItem: EpisodeItem): Boolean {
            return oldItem == newItem
        }
    }
}
