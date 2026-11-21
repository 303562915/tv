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
    /** true = 竖屏选集小格子（固定尺寸、只显示集数、灰底）；false = 网格（弹窗/底栏） */
    private val compact: Boolean = false,
    private val onEpisodeClick: (Int, Episode) -> Unit
) : ListAdapter<Episode, EpisodeSelectorAdapter.ViewHolder>(EpisodeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeTencentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        if (compact) {
            // 横向一行里不能用 match_parent，否则单个格子会撑满整屏
            val density = parent.context.resources.displayMetrics.density
            val width = (CHIP_WIDTH_DP * density).toInt()
            val height = (CHIP_HEIGHT_DP * density).toInt()
            val margin = (CHIP_MARGIN_DP * density).toInt()
            binding.root.layoutParams = RecyclerView.LayoutParams(width, height).apply {
                setMargins(margin, margin, margin, margin)
            }
        }
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
            binding.vipBadge.visibility = android.view.View.GONE

            // 使用 adapterPosition 而不是 position 参数来判断选中状态
            val currentPosition = bindingAdapterPosition
            val isSelected = currentPosition != RecyclerView.NO_POSITION && currentPosition == currentIndex

            if (compact) {
                // 竖屏小格子：只显示集数，灰底；当前集只换文字颜色，不用实心色块
                binding.episodeNameText.visibility = android.view.View.GONE
                binding.episodeCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (isSelected) R.color.episode_chip_bg_selected else R.color.episode_chip_bg
                    )
                )
                binding.episodeNumber.setTextColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (isSelected) R.color.episode_chip_text_selected else R.color.episode_chip_text
                    )
                )
                binding.episodeCard.setOnClickListener {
                    val clickPosition = bindingAdapterPosition
                    if (clickPosition != RecyclerView.NO_POSITION) {
                        onEpisodeClick(clickPosition, episode)
                    }
                }
                return
            }

            binding.episodeNameText.visibility = android.view.View.VISIBLE

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

    companion object {
        /** 竖屏选集小格子尺寸（dp） */
        private const val CHIP_WIDTH_DP = 56f
        private const val CHIP_HEIGHT_DP = 40f
        private const val CHIP_MARGIN_DP = 3f
    }
}

