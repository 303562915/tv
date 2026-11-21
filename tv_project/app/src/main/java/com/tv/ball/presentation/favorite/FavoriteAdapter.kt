package com.tv.ball.presentation.favorite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemFavoriteBinding
import com.tv.ball.util.ImageLoader
import java.text.SimpleDateFormat
import java.util.*

class FavoriteAdapter(
    private val onItemClick: (FavoriteItem) -> Unit,
    private val onItemDelete: (FavoriteItem) -> Unit,
    private val onItemLongClick: (FavoriteItem) -> Unit = {}
) : ListAdapter<FavoriteItem, FavoriteAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick, onItemDelete, onItemLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemFavoriteBinding,
        private val onItemClick: (FavoriteItem) -> Unit,
        private val onItemDelete: (FavoriteItem) -> Unit,
        private val onItemLongClick: (FavoriteItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(favoriteItem: FavoriteItem) {
            binding.tvTitle.text = favoriteItem.title

            // 格式化收藏时间
            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            binding.tvAddedTime.text = "收藏于 ${dateFormat.format(Date(favoriteItem.addedAt))}"

            // 分类标签：未分类不显示标签，省掉一行空白
            val category = favoriteItem.category?.takeIf { it.isNotBlank() }
            if (category != null) {
                binding.tvCategory.text = category
                binding.tvCategory.visibility = View.VISIBLE
            } else {
                binding.tvCategory.text = null
                binding.tvCategory.visibility = View.GONE
            }

            ImageLoader.load(
                context = binding.ivCover.context,
                url = favoriteItem.thumbnailUrl,
                imageView = binding.ivCover,
                placeholder = R.drawable.ic_launcher_background
            )

            binding.root.setOnClickListener {
                onItemClick(favoriteItem)
            }

            // 长按：移动到分类 / 新建分类 / 取消收藏
            binding.root.setOnLongClickListener {
                onItemLongClick(favoriteItem)
                true
            }

            binding.btnDelete.setOnClickListener {
                onItemDelete(favoriteItem)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FavoriteItem>() {
        override fun areItemsTheSame(oldItem: FavoriteItem, newItem: FavoriteItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteItem, newItem: FavoriteItem): Boolean {
            return oldItem == newItem
        }
    }
}