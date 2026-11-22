package com.tv.ball.presentation.home

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.tv.ball.R
import com.tv.ball.databinding.ItemVideoBinding
import com.tv.ball.util.ImageLoader

class VideoListAdapter(
    private val onVideoClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoListAdapter.ViewHolder>(DiffCallback()) {

    /**
     * 标题里要高亮的关键词（搜索结果用）。空串 = 不高亮，标题按纯文本显示，
     * 首页等其它使用方不设置这个属性时行为完全不变。
     */
    var highlightQuery: String = ""
        set(value) {
            if (field == value) return
            field = value
            // 关键词变了：已在屏上的标题要按新关键词重刷（结果列表本身可能还没换）
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(video: VideoItem) {
            binding.videoTitle.text = highlightTitle(video.title)
            binding.videoQuality.text = video.quality
            
            // 处理空的封面 URL
            val coverUrl = if (video.coverUrl.isNullOrEmpty()) {
                android.util.Log.d("VideoListAdapter", "封面 URL 为空，使用占位图: ${video.title}")
                // 空封面时用 inset 井占位
                binding.videoCover.setBackgroundColor(
                    binding.root.context.getColor(R.color.surface_inset)
                )
                null
            } else {
                binding.videoCover.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                video.coverUrl
            }
            
            // 使用优化的ImageLoader，根据位置设置优先级
            // 不使用占位图，空图片时显示空白
            val priority = if (position < 9) Priority.HIGH else Priority.NORMAL
            ImageLoader.load(
                context = binding.root.context,
                url = coverUrl,
                imageView = binding.videoCover,
                placeholder = 0, // 不使用占位图
                priority = priority
            )
            
            binding.root.setOnClickListener {
                onVideoClick(video)
            }
        }

        /**
         * 搜索命中：标题里所有出现的关键词（大小写不敏感）都上主色 + 加粗。
         * 只处理标题，简介 / 其它字段不动；没有关键词时原样返回纯文本，
         * 顺便避免给每个 item 白造一堆 Span（首页整页滑下来是有成本的）。
         */
        private fun highlightTitle(title: String): CharSequence {
            val query = highlightQuery.trim()
            if (query.isEmpty()) return title

            val spannable = SpannableString(title)
            val color = ContextCompat.getColor(binding.root.context, R.color.theme_primary)
            var start = title.indexOf(query, ignoreCase = true)
            while (start >= 0) {
                val end = start + query.length
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // 从命中结尾继续找，标题里重复出现的关键词逐个上高亮
                start = title.indexOf(query, start + query.length, ignoreCase = true)
            }
            return spannable
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.detailUrl == newItem.detailUrl
        }
        
        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem == newItem
        }
    }
}
