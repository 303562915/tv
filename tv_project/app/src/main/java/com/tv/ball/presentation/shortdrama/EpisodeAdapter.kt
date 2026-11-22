package com.tv.ball.presentation.shortdrama

import android.view.LayoutInflater
import android.view.View
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

    /**
     * 长按钩子：外层页面按需接线（当前详情页未接，属预留能力）。
     * 未接线（null）时不消费事件，长按照常交回系统默认处理。
     */
    var onEpisodeLongClick: ((EpisodeItem) -> Unit)? = null

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
        // 每次创建 holder 时取一次钩子，回调时再读 onEpisodeLongClick 的最新值（可能晚于 holder 创建才接线）
        return ViewHolder(binding, onItemClick) { item ->
            val hook = onEpisodeLongClick
            if (hook == null) {
                // 没人接长按：返回 false，不吞掉事件
                false
            } else {
                hook(item)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(
            item,
            item.episodeNum == selectedEpisodeNum,
            item.episodeNum == lastWatchedEpisodeNum
        )
    }

    /** 只刷「某集号」对应的那一格：集号无效或不在当前列表里就不用刷 */
    private fun notifyEpisodeChanged(episodeNum: Int) {
        if (episodeNum <= 0) return
        val index = currentList.indexOfFirst { it.episodeNum == episodeNum }
        if (index >= 0) notifyItemChanged(index)
    }

    /**
     * 标记「上次看到第几集」：那一格加一圈主题色描边。传 0 或负数表示清掉。
     */
    fun setLastWatchedEpisodeNum(episodeNum: Int) {
        if (lastWatchedEpisodeNum == episodeNum) return
        val previous = lastWatchedEpisodeNum
        lastWatchedEpisodeNum = episodeNum
        // 按集号算出新旧两个位置各刷一次，不再整段 notifyItemRangeChanged（那会让所有格子重绑、闪一下）
        notifyEpisodeChanged(previous)
        notifyEpisodeChanged(episodeNum)
    }

    /**
     * 设置选中的集数（传 0 或负数表示取消高亮）
     */
    fun setSelectedEpisodeNum(episodeNum: Int) {
        if (selectedEpisodeNum == episodeNum) return
        val previous = selectedEpisodeNum
        selectedEpisodeNum = episodeNum
        // 取消高亮（传 -1）时要刷的是「上一格」：previous 有效就还能找到下标，
        // 新集号是 -1 时 notifyEpisodeChanged 会直接跳过，不会漏刷也不会错刷
        notifyEpisodeChanged(previous)
        notifyEpisodeChanged(episodeNum)
    }

    class ViewHolder(
        private val binding: ItemEpisodeTencentBinding,
        private val onItemClick: (EpisodeItem) -> Unit,
        private val onItemLongClick: (EpisodeItem) -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density + 0.5f).toInt()

        fun bind(item: EpisodeItem, isSelected: Boolean, isLastWatched: Boolean) {
            val ctx = binding.root.context

            // 显示集数
            binding.episodeNumber.text = item.episodeNum.toString()
            binding.episodeNameText.text = "第${item.episodeNum}集"

            // 短剧全免费：原来「集号 >= 5 一律标 VIP」是假数据，已删掉该判定。
            // 控件和 id 保留（将来真接付费数据源再用），这里恒隐藏。
            binding.vipBadge.visibility = View.GONE

            // 高亮当前播放的集数（使用腾讯风格）
            if (isSelected) {
                // 选中状态：主色实心底 + on-primary 文字（浅色主题下也够对比）
                binding.episodeCard.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.primary)
                )
                val onPrimary = ContextCompat.getColor(ctx, R.color.theme_on_primary)
                binding.episodeNumber.setTextColor(onPrimary)
                binding.episodeNameText.setTextColor(onPrimary)
            } else {
                // 未选中状态：跟随主题的卡片底色 + 主/副文字色。
                // 不再写死 android.R.color.white/black，否则夜间模式会是一片白底黑字。
                binding.episodeCard.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.surface_block)
                )
                binding.episodeNumber.setTextColor(
                    ContextCompat.getColor(ctx, R.color.theme_text_primary)
                )
                binding.episodeNameText.setTextColor(
                    ContextCompat.getColor(ctx, R.color.theme_text_secondary)
                )
            }

            // 「上次看到第几集」的角标：一圈 2dp 主题色描边。
            // 未选中的格子是 surface_block 底，描边一眼能看到；选中的格子本身是主色实心，描边叠上去也不碍事
            if (isLastWatched) {
                binding.episodeCard.strokeWidth = dp(2)
                binding.episodeCard.setStrokeColor(
                    ContextCompat.getColor(ctx, R.color.theme_primary)
                )
            } else {
                // 复用的 holder 必须显式收掉描边，否则角标会跟着格子乱跑
                binding.episodeCard.strokeWidth = 0
            }

            // TalkBack 只读一个数字没有语义，补一句完整的「第 N 集」
            binding.episodeCard.contentDescription = "第${item.episodeNum}集"

            // 点击反馈交给布局里的 ?attr/selectableItemBackground（ripple）；
            // 原来手写的 scale 动画在快速连点时会残留半缩放状态，已删掉
            binding.episodeCard.setOnClickListener {
                onItemClick(item)
            }

            binding.episodeCard.setOnLongClickListener {
                onItemLongClick(item)
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
