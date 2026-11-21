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

    /**
     * 条目是否可以左滑露出删除区。
     * 直接删除模式下 Fragment 把它置 false：容器不再抢横向手势，留给 ItemTouchHelper。
     * bind 时同步给每一行的 SwipeRevealLayout，所以滚入屏幕的新行也是对的。
     */
    var swipeEnabled: Boolean = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClick, onItemDelete, onItemLongClick, { swipeEnabled })
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemFavoriteBinding,
        private val onItemClick: (FavoriteItem) -> Unit,
        private val onItemDelete: (FavoriteItem) -> Unit,
        private val onItemLongClick: (FavoriteItem) -> Unit,
        private val isSwipeEnabled: () -> Boolean
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

            // 每次绑定都把当前的滑动模式同步给容器，滚入屏幕的新行不会拿到旧状态
            binding.root.swipeEnabled = isSwipeEnabled()

            // 根节点是 SwipeRevealLayout、卡片自己 clickable，点击到不了根节点，
            // 所以点卡片 = 打开详情，滑动露出的删除按钮才 = 取消收藏
            val content = binding.root.contentView ?: binding.root
            content.setOnClickListener {
                onItemClick(favoriteItem)
            }

            // 长按：移动到分类 / 新建分类 / 取消收藏
            content.setOnLongClickListener {
                onItemLongClick(favoriteItem)
                true
            }

            binding.btnSwipeDelete.setOnClickListener {
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