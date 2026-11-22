package com.tv.ball.presentation.player

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemEpisodeTencentBinding
import com.tv.ball.domain.model.Episode

/**
 * 选集格子适配器：播放器竖屏那条横向小格子（compact），以及各选集弹窗里的网格。
 *
 * **分批（懒加载）**：100+ 集的片源一次 submitList 全部塞进去，首次布局就要 inflate 上百个
 * MaterialCardView，弹窗会明显卡一下。所以网格模式只在适配器里留一份全量 [fullEpisodes]，
 * 真正提交给 ListAdapter 的只有前 [PAGE_SIZE] 条；Activity 的 onScrolled 调 [loadMoreIfNeeded]，
 * 距尾部不足 [LOAD_MORE_THRESHOLD] 条时追加下一批。尾部挂一条「加载中…」footer（[FooterViewHolder]），
 * 让「还没铺完」这件事在 UI 上看得见。
 *
 * compact 模式（竖屏选集那一行横向小格子）**不参与分批**：它本来就是单行横向滚动、RecyclerView
 * 自己会回收，分批只会让「滚到当前集」更难对上位置。
 */
class EpisodeSelectorAdapter(
    private var currentIndex: Int,
    /** true = 竖屏选集小格子（固定尺寸、只显示集数、灰底）；false = 网格（弹窗/底栏） */
    private val compact: Boolean = false,
    private val onEpisodeClick: (Int, Episode) -> Unit
) : ListAdapter<Episode, RecyclerView.ViewHolder>(EpisodeDiffCallback()) {

    // ---------------- 分批（懒加载）状态 ----------------

    /** 全量剧集：分批只发生在「提交给 ListAdapter」这一层，原始数据留在这里 */
    private var fullEpisodes: List<Episode> = emptyList()

    /** 已经提交给 ListAdapter 的条数（= 当前这批的末尾下标） */
    private var loadedCount = 0

    /**
     * footer 当前是否真的挂在列表尾部。
     * itemCount 的自增自减只认这个字段：只有它跟着 notifyItemInserted/Removed 一起变，
     * RecyclerView 才不会出现「itemCount 变了但没人通知」的错位崩溃。
     */
    private var footerAttached = false

    /** 还有没提交的剧集（只有网格模式才会出现） */
    val hasMore: Boolean
        get() = !compact && loadedCount < fullEpisodes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_FOOTER) {
            return FooterViewHolder(createFooterView(parent))
        }
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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FooterViewHolder) {
            // footer 被绑上来说明用户已经滚到底：顺手追加下一批。
            // onScrolled 之外的第二道保险 —— 列表短到滚不动时 onScrolled 根本不会回调。
            loadMore()
            return
        }
        (holder as ViewHolder).bind(getItem(position), position)
    }

    override fun getItemCount(): Int = super.getItemCount() + if (footerAttached) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (footerAttached && position == super.getItemCount()) VIEW_TYPE_FOOTER else VIEW_TYPE_EPISODE

    override fun submitList(list: List<Episode>?) {
        submitEpisodes(list.orEmpty(), null)
    }

    override fun submitList(list: List<Episode>?, commitCallback: Runnable?) {
        submitEpisodes(list.orEmpty(), commitCallback)
    }

    /**
     * 用全量列表重新开始一轮分批：网格模式只先提交前 [PAGE_SIZE] 条。
     * Activity 侧照旧调 `submitList(...)` 也会走到这里，绕不过分批。
     */
    fun submitEpisodes(episodes: List<Episode>, commitCallback: Runnable? = null) {
        fullEpisodes = episodes
        loadedCount = if (compact) episodes.size else minOf(PAGE_SIZE, episodes.size)
        super.submitList(episodes.take(loadedCount)) {
            syncFooter()
            commitCallback?.run()
        }
    }

    /** 追加下一批；返回 true 表示还追加了（还有更多没铺出来） */
    fun loadMore(): Boolean {
        if (!hasMore) return false
        loadedCount = minOf(loadedCount + PAGE_SIZE, fullEpisodes.size)
        super.submitList(fullEpisodes.take(loadedCount)) { syncFooter() }
        return true
    }

    /**
     * 滚到底部附近就追加下一批。
     *
     * [lastVisiblePosition] 传 `LayoutManager.findLastVisibleItemPosition()`；
     * 距尾部不足 [LOAD_MORE_THRESHOLD] 条就追加，不用等真的贴到最后一行。
     */
    fun loadMoreIfNeeded(lastVisiblePosition: Int) {
        if (!hasMore) return
        if (lastVisiblePosition >= itemCount - LOAD_MORE_THRESHOLD) loadMore()
    }

    /** 给 GridLayoutManager 用：footer 独占一整行，其余集数仍是一格 */
    fun createSpanSizeLookup(spanCount: Int): GridLayoutManager.SpanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (footerAttached && position == itemCount - 1) spanCount else 1
        }

    /**
     * footer 的增删只在 diff 提交完成之后通知一次：数量变化必须伴随 notify，
     * 否则 RecyclerView 会在下一次 layout 抛 "Inconsistency detected"。
     */
    private fun syncFooter() {
        val needFooter = hasMore
        if (needFooter == footerAttached) return
        footerAttached = needFooter
        if (needFooter) {
            notifyItemInserted(super.getItemCount())
        } else {
            notifyItemRemoved(super.getItemCount())
        }
    }

    /** footer 视图：弹窗布局里没有多余的位置放它，直接在代码里造一个居中的说明文字 */
    private fun createFooterView(parent: ViewGroup): TextView {
        val density = parent.context.resources.displayMetrics.density
        val padding = (FOOTER_PADDING_DP * density).toInt()
        return TextView(parent.context).apply {
            text = FOOTER_TEXT
            gravity = Gravity.CENTER
            textSize = FOOTER_TEXT_SIZE_SP
            setTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))
            setPadding(padding, padding, padding, padding)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
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

    /** 「加载中…」footer：不参与点击，只占位 */
    private class FooterViewHolder(textView: TextView) : RecyclerView.ViewHolder(textView)

    inner class ViewHolder(
        private val binding: ItemEpisodeTencentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun dpToPx(dp: Float): Int =
            (dp * binding.root.resources.displayMetrics.density + 0.5f).toInt()

        fun bind(episode: Episode, position: Int) {
            // 提取集数（假设格式为"第X集"）
            val episodeNumber = episode.name.replace("第", "").replace("集", "")
            binding.episodeNumber.text = episodeNumber
            binding.episodeNameText.text = episode.name
            // 假 VIP 角标已废弃：本适配器没有任何会点亮它的分支，这里显式收起，
            // 布局里的 vipBadge 保留只是为了不让 binding 缺字段（item_episode_tencent.xml 已标 tools:visibility=gone）
            binding.vipBadge.visibility = View.GONE

            // 使用 adapterPosition 而不是 position 参数来判断选中状态
            val currentPosition = bindingAdapterPosition
            val isSelected = currentPosition != RecyclerView.NO_POSITION && currentPosition == currentIndex

            if (compact) {
                // 竖屏小格子：只显示集数，灰底；当前集换字色 + 加粗 + 主题色描边，不用实心色块
                binding.episodeNameText.visibility = View.GONE
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
                // 当前集再加粗（复用 holder 时要显式回落，否则未选中项会残留粗体）
                binding.episodeNumber.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // 当前集 1dp 主题色描边，未选中不描边
                if (isSelected) {
                    binding.episodeCard.strokeWidth = dpToPx(1f)
                    binding.episodeCard.setStrokeColor(
                        ContextCompat.getColor(binding.root.context, R.color.theme_primary)
                    )
                } else {
                    binding.episodeCard.strokeWidth = 0
                }
                binding.episodeCard.setOnClickListener {
                    val clickPosition = bindingAdapterPosition
                    if (clickPosition != RecyclerView.NO_POSITION) {
                        onEpisodeClick(clickPosition, episode)
                    }
                }
                return
            }

            binding.episodeNameText.visibility = View.VISIBLE

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

        /** 每批提交多少集：100+ 集的片源不再一次铺开 */
        private const val PAGE_SIZE = 200

        /** 距列表尾部还剩多少条就开始追加下一批 */
        private const val LOAD_MORE_THRESHOLD = 20

        private const val VIEW_TYPE_EPISODE = 0
        private const val VIEW_TYPE_FOOTER = 1

        /** footer 文案（布局文件不在本次改动范围，直接写死中文，与页面其它文案口径一致） */
        private const val FOOTER_TEXT = "加载中…"
        private const val FOOTER_TEXT_SIZE_SP = 12f
        private const val FOOTER_PADDING_DP = 12f
    }
}
