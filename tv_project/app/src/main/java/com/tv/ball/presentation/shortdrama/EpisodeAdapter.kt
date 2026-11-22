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

    // 用「集号」而不是「下标」记高亮：
    // 详情页现在会分批显示（默认 30 集）、还能切播放源，同一个下标在不同列表里指的集数不一样，
    // 下标高亮会在展开/切源后跳到别的集上。
    private var selectedEpisodeNum = -1

    /** 「上次看到第几集」的角标：同样按集号记，展开/切源后不会指错格子 */
    private var lastWatchedEpisodeNum = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeTencentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(
            item,
            item.episodeNum == selectedEpisodeNum,
            item.episodeNum == lastWatchedEpisodeNum
        )
    }

    /**
     * 标记「上次看到第几集」：那一格加一圈主题色描边。传 0 或负数表示清掉。
     */
    fun setLastWatchedEpisodeNum(episodeNum: Int) {
        if (lastWatchedEpisodeNum == episodeNum) return
        lastWatchedEpisodeNum = episodeNum
        // 和 setSelectedEpisodeNum 一样整段刷新，分批 + 切源后下标不一定对得上集号
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 设置选中的集数（传 0 或负数表示取消高亮）
     */
    fun setSelectedEpisodeNum(episodeNum: Int) {
        if (selectedEpisodeNum == episodeNum) return
        selectedEpisodeNum = episodeNum
        // 列表分批后 itemCount 不大（最多几十个），整段刷新比逐个算差异更省心，也不会错位
        notifyItemRangeChanged(0, itemCount)
    }

    class ViewHolder(
        private val binding: ItemEpisodeTencentBinding,
        private val onItemClick: (EpisodeItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density + 0.5f).toInt()

        fun bind(item: EpisodeItem, isSelected: Boolean, isLastWatched: Boolean) {
            // 显示集数
            binding.episodeNumber.text = item.episodeNum.toString()
            binding.episodeNameText.text = "第${item.episodeNum}集"

            // VIP 判定按集号，不按下标：分批显示 + 切源会改变下标对应的集数，
            // 按下标会让「VIP」标签在展开/收起后乱跳
            val isVip = item.episodeNum >= 5

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

            // 「上次看到第几集」的角标：一圈 2dp 主题色描边。
            // 未选中的格子是白底，描边一眼能看到；选中的格子本身是主色实心，描边叠上去也不碍事
            if (isLastWatched) {
                binding.episodeCard.strokeWidth = dp(2)
                binding.episodeCard.setStrokeColor(
                    ContextCompat.getColor(binding.root.context, R.color.theme_primary)
                )
            } else {
                // 复用的 holder 必须显式收掉描边，否则角标会跟着格子乱跑
                binding.episodeCard.strokeWidth = 0
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