package com.tv.ball.presentation.ranking

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemRankingBinding

class RankingAdapter(
    private val onItemClick: (RankingItem) -> Unit
) : ListAdapter<RankingItem, RankingAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRankingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRankingBinding,
        private val onItemClick: (RankingItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RankingItem) {
            binding.tvRank.text = item.rank.toString()
            binding.tvTitle.text = item.title
            binding.tvPlatform.text = item.platform
            binding.tvOnlineDays.text = item.onlineDays
            binding.tvHeat.text = "实时热量 ${item.heat}"
            
            if (item.playCount.isNotEmpty()) {
                binding.tvPlayCount.text = "实时播放量 ${item.playCount}"
                binding.tvPlayCount.visibility = android.view.View.VISIBLE
            } else {
                binding.tvPlayCount.visibility = android.view.View.GONE
            }
            
            // 排名只用文字上色，徽章底保持 inset 井
            val rankTextColor = when (item.rank) {
                1, 2, 3 -> R.color.theme_primary
                else -> R.color.theme_text_secondary
            }
            binding.tvRank.setTextColor(binding.root.context.getColor(rankTextColor))
            
            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RankingItem>() {
        override fun areItemsTheSame(oldItem: RankingItem, newItem: RankingItem): Boolean {
            return oldItem.rank == newItem.rank
        }

        override fun areContentsTheSame(oldItem: RankingItem, newItem: RankingItem): Boolean {
            return oldItem == newItem
        }
    }
}
