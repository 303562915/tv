package com.tv.ball.presentation.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemEpisodeTencentBinding
import com.tv.ball.domain.model.Episode

class EpisodeSelectorAdapter(
    private var currentIndex: Int,
    private val onEpisodeClick: (Int, Episode) -> Unit
) : ListAdapter<Episode, EpisodeSelectorAdapter.ViewHolder>(EpisodeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeTencentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }
    
    fun updateCurrentIndex(newIndex: Int) {
        val oldIndex = currentIndex
        currentIndex = newIndex
        
        // 只刷新有效的索引位置
        if (oldIndex >= 0 && oldIndex < itemCount) {
            notifyItemChanged(oldIndex)
        }
        if (newIndex >= 0 && newIndex < itemCount) {
            notifyItemChanged(newIndex)
        }
    }

    inner class ViewHolder(
        private val binding: ItemEpisodeTencentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(episode: Episode, position: Int) {
            // 提取集数（假设格式为"第X集"）
            val episodeNumber = episode.name.replace("第", "").replace("集", "")
            binding.episodeNumber.text = episodeNumber
            binding.episodeNameText.text = episode.name

            // 判断是否为VIP集数（假设第5集及以后为VIP）
            val isVip = position >= 4
            
            // 显示/隐藏VIP标签
            binding.vipBadge.visibility = if (isVip) android.view.View.VISIBLE else android.view.View.GONE

            // 使用 adapterPosition 而不是 position 参数来判断选中状态
            val currentPosition = bindingAdapterPosition
            val isSelected = currentPosition != RecyclerView.NO_POSITION && currentPosition == currentIndex
            
            // 判断是否为电影（时长≥60分钟）
            val isMovie = episode.durationMinutes >= 60
            
            // 高亮当前播放的集数（使用腾讯风格，增强对比度）
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
                // 未选中状态：根据时长设置背景色
                if (isMovie) {
                    // 电影（≥60分钟）：深蓝色背景 + 白色文字
                    binding.episodeCard.setCardBackgroundColor(
                        ContextCompat.getColor(binding.root.context, R.color.episode_movie_bg)
                    )
                    binding.episodeNumber.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.episode_movie_text)
                    )
                    binding.episodeNameText.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.episode_movie_text)
                    )
                } else {
                    // 普通影视（<60分钟）：白色背景 + 深色文字
                    binding.episodeCard.setCardBackgroundColor(
                        ContextCompat.getColor(binding.root.context, R.color.episode_normal_bg)
                    )
                    binding.episodeNumber.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.episode_normal_text)
                    )
                    binding.episodeNameText.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.episode_normal_text)
                    )
                }
            }

            binding.episodeCard.setOnClickListener {
                val clickPosition = bindingAdapterPosition
                if (clickPosition != RecyclerView.NO_POSITION) {
                    onEpisodeClick(clickPosition, episode)
                }
            }
        }
    }

    private class EpisodeDiffCallback : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem == newItem
        }
    }
}
