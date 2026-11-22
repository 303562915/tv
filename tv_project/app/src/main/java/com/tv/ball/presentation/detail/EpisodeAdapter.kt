package com.tv.ball.presentation.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemEpisodeDetailBinding
import com.tv.ball.domain.model.Episode

class EpisodeAdapter(
    private val onEpisodeClick: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.EpisodeViewHolder>(EpisodeDiffCallback()) {

    private var selectedPosition = -1

    /** 上次看到的那一集（按 url 记，切换线路/重新 submitList 后依然对得上） */
    private var lastWatchedUrl: String? = null

    /**
     * 标记「上次看到这一集」：加一圈主题色描边，和「正在播」的实心底色不是一个东西。
     * 传 null 表示没有历史记录，全部清掉。
     */
    fun setLastWatchedEpisode(episode: Episode?) {
        lastWatchedUrl = episode?.url
        // itemCount 不大（一屏几十个格子），整段刷新最省心，也不会漏掉复用出去的 holder
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 设置选中的集数
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

    /**
     * 根据Episode查找并设置选中位置
     */
    fun setSelectedEpisode(episode: Episode?) {
        if (episode == null) {
            setSelectedPosition(-1)
            return
        }

        val position = currentList.indexOfFirst { it.url == episode.url }
        if (position != -1) {
            setSelectedPosition(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeDetailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(position)
        android.util.Log.d("EpisodeAdapter", "onBindViewHolder: position=$position, name=${episode.name}, url=${episode.url}")
        holder.bind(episode, position, position == selectedPosition)
    }

    inner class EpisodeViewHolder(
        private val binding: ItemEpisodeDetailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density + 0.5f).toInt()
        
        fun bind(episode: Episode, position: Int, isSelected: Boolean) {
            android.util.Log.d("EpisodeAdapter", "bind: position=$position, name=${episode.name}, isSelected=$isSelected")
            
            // 提取集数（从"第X集"或"X集"中提取数字）
            val episodeNumber = episode.name
                .replace("第", "")
                .replace("集", "")
                .trim()
            
            android.util.Log.d("EpisodeAdapter", "episodeNumber=$episodeNumber")
            
            binding.episodeNumber.text = episodeNumber
            binding.episodeNameText.text = episode.name
            binding.vipBadge.visibility = android.view.View.GONE
            
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

            // 「上次看到这一集」的角标：用 2dp 主题色描边。选中态是实心底色，描边叠在上面依旧看得出
            val isLastWatched = !lastWatchedUrl.isNullOrEmpty() && episode.url == lastWatchedUrl
            if (isLastWatched) {
                binding.episodeCard.strokeWidth = dp(2)
                binding.episodeCard.setStrokeColor(
                    ContextCompat.getColor(binding.root.context, R.color.theme_primary)
                )
            } else {
                // 复用的 holder 必须显式清掉描边，否则角标会跟着格子乱跑
                binding.episodeCard.strokeWidth = 0
            }

            binding.episodeCard.setOnClickListener {
                val clickPosition = bindingAdapterPosition
                if (clickPosition != RecyclerView.NO_POSITION) {
                    onEpisodeClick(episode)
                    // 更新选中状态 - 使用外部类的方法
                    this@EpisodeAdapter.setSelectedPosition(clickPosition)
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

