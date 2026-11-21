package com.tv.ball.presentation.shortdrama

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tv.ball.R
import com.tv.ball.databinding.ItemShortDramaBinding
import com.tv.ball.util.ImageLoader

class ShortDramaAdapter(
    private val onItemClick: (ShortDramaItem) -> Unit
) : ListAdapter<ShortDramaItem, ShortDramaAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShortDramaBinding.inflate(
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
        private val binding: ItemShortDramaBinding,
        private val onItemClick: (ShortDramaItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShortDramaItem) {
            binding.tvTitle.text = item.title
            // 采集源不一定给总集数，给了才显示，否则留空而不是显示一个「集」字
            binding.tvEpisodes.text = if (item.episodeCnt.isNotEmpty()) "${item.episodeCnt}集" else ""
            binding.tvPlayCount.text = if (item.playCnt == "0") "" else formatPlayCount(item.playCnt)
            
            ImageLoader.load(
                context = binding.root.context,
                url = item.cover,
                imageView = binding.ivCover,
                placeholder = R.drawable.ic_placeholder
            )
            
            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
        
        private fun formatPlayCount(count: String): String {
            return try {
                val num = count.toLong()
                when {
                    num >= 100000000 -> String.format("%.1f亿", num / 100000000.0)
                    num >= 10000 -> String.format("%.1f万", num / 10000.0)
                    else -> count
                }
            } catch (e: Exception) {
                count
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ShortDramaItem>() {
        override fun areItemsTheSame(oldItem: ShortDramaItem, newItem: ShortDramaItem): Boolean {
            return oldItem.bookId == newItem.bookId
        }

        override fun areContentsTheSame(oldItem: ShortDramaItem, newItem: ShortDramaItem): Boolean {
            return oldItem == newItem
        }
    }
}
