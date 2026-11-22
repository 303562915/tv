package com.tv.ball.presentation.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.tv.ball.R
import com.tv.ball.data.model.FavoriteEntity
import com.tv.ball.databinding.ItemVideoBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.util.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VideoListAdapter(
    private val onVideoClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoListAdapter.ViewHolder>(DiffCallback()) {

    /**
     * 长按菜单里「收藏 / 分享」的协程作用域：只做一次短暂的 DB 写 / 弹系统分享，
     * 不持有任何 View 或 Fragment，所以按适配器存活即可。
     */
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

            // 长按：收藏 / 分享 / 取消。消费掉事件，避免同时触发点击
            binding.root.setOnLongClickListener {
                showItemMenu(binding.root.context, video)
                true
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
    
    /**
     * 列表项长按菜单：收藏 / 分享 / 取消。
     *
     * 用下标区分动作，菜单文案与下标一一对应（见 [MENU_LABELS]），
     * 以后加一项只要同步改这两处。
     */
    private fun showItemMenu(context: Context, video: VideoItem) {
        AlertDialog.Builder(context)
            .setTitle(video.title)
            .setItems(MENU_LABELS) { _, which ->
                when (which) {
                    MENU_FAVORITE -> addFavorite(context, video)
                    MENU_SHARE -> shareVideo(context, video)
                    else -> Unit // 取消
                }
            }
            .show()
    }

    /**
     * 收藏到「未分类」。
     *
     * 走 Room 的 [com.tv.ball.data.local.FavoriteDao]（`Dependencies.database` 就是
     * `VideoPlayerApplication.database`，与播放页 / 收藏页同一份库）：
     * 先 `isFavorite` 判重，已收藏只提示，不重复插一条；插入成功才提示成功。
     */
    private fun addFavorite(context: Context, video: VideoItem) {
        val videoId = favoriteVideoId(video)
        actionScope.launch {
            try {
                val favoriteDao = Dependencies.database.favoriteDao()
                if (favoriteDao.isFavorite(videoId)) {
                    CustomToast.show(context, "已在收藏夹")
                    return@launch
                }
                favoriteDao.insertFavorite(
                    FavoriteEntity(
                        videoId = videoId,
                        title = video.title,
                        // 空封面 / 空详情页链接存 null，别往库里塞空串
                        thumbnailUrl = video.coverUrl.takeIf { it.isNotBlank() },
                        detailUrl = video.detailUrl.takeIf { it.isNotBlank() },
                        episodesJson = null,
                        category = null // 未分类；要分类去收藏页长按调整
                    )
                )
                CustomToast.show(context, "收藏成功")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "收藏失败: ${e.message}", e)
                CustomToast.show(context, "收藏失败，请重试")
            }
        }
    }

    /**
     * 分享：标题 + 详情页链接。
     *
     * detailUrl 为空时**只发标题**——宁可信息少一点，也不拼一个假的 URL 出去。
     * provider 缺失（ActivityNotFoundException）时不崩，只提示。
     */
    private fun shareVideo(context: Context, video: VideoItem) {
        val detailUrl = video.detailUrl.trim()
        val shareText = if (detailUrl.isEmpty()) video.title else "${video.title}\n$detailUrl"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, video.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        try {
            context.startActivity(Intent.createChooser(sendIntent, "分享到"))
        } catch (e: ActivityNotFoundException) {
            android.util.Log.w(TAG, "没有可用的分享应用: ${e.message}")
            CustomToast.show(context, "没有可用的分享应用")
        }
    }

    /**
     * 列表项没有服务端数值 id（播放页的 VIDEO_ID 是进入时的 `System.currentTimeMillis()`，
     * 不是稳定的视频身份），所以用详情页 URL 生成稳定主键：
     * 同一视频反复收藏只会 REPLACE 掉自己那一条，不同视频靠 64 位哈希区分。
     * 空 detailUrl 退化为按标题取哈希，至少同标题的项不会互相顶掉其它项。
     */
    private fun favoriteVideoId(video: VideoItem): Long {
        val key = video.detailUrl.trim().ifEmpty { video.title.trim() }.lowercase()
        var hash = FNV_OFFSET_BASIS
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFFL)
            hash *= FNV_PRIME
        }
        // 去掉符号位：主键为正数，方便日志 / 排查时直接看
        return hash and Long.MAX_VALUE
    }

    private class DiffCallback : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem.detailUrl == newItem.detailUrl
        }
        
        override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
            return oldItem == newItem
        }
    }

    private companion object {
        /** 长按菜单项下标，顺序与 [MENU_LABELS] 一一对应。 */
        const val MENU_FAVORITE = 0
        const val MENU_SHARE = 1

        /** 「收藏 / 分享 / 取消」：与 [MENU_FAVORITE] / [MENU_SHARE] 同步维护。 */
        val MENU_LABELS = arrayOf("收藏", "分享", "取消")

        /** FNV-1a 64 位的 offset basis（0xCBF29CE484222325）与 prime（0x100000001B3）。 */
        const val FNV_OFFSET_BASIS = -0x340D631B7BDDDCDBL
        const val FNV_PRIME = 0x100000001B3L

        const val TAG = "VideoListAdapter"
    }
}
