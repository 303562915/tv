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
 * 选集格子适配器：播放器竖屏那条横向小格子（compact）、各选集弹窗里的网格，
 * 以及详情页 6 列网格（[useThemeColors] = true 的「详情页模式」）——选集实现归一后唯一的网格适配器。
 *
 * **分批（懒加载）**：100+ 集的片源一次 submitList 全部塞进去，首次布局就要 inflate 上百个
 * MaterialCardView，弹窗会明显卡一下。所以网格模式只在适配器里留一份全量 [fullEpisodes]，
 * 真正提交给 ListAdapter 的只有前 [PAGE_SIZE] 条；可滚动列表由 Activity 的 onScrolled 调
 * [loadMoreIfNeeded]，铺在外层 ScrollView 里的网格（详情页）保留外层滚动判定后调 [loadMore]。
 * 尾部挂一条「加载中…」footer（[FooterViewHolder]），让「还没铺完」这件事在 UI 上看得见。
 *
 * compact 模式（竖屏选集那一行横向小格子）**不参与分批**：它本来就是单行横向滚动、RecyclerView
 * 自己会回收，分批只会让「滚到当前集」更难对上位置。
 *
 * **详情页模式**（[useThemeColors] = true）：配色全走主题色（夜间模式下未选中的格子不是白底），
 * 不做电影判定，支持「按 url 记选中」（[setSelectedEpisode]）与「上次看到」描边
 * （[setLastWatchedEpisode]）、长按回调与无障碍描述。播放器两个调用点都用默认参数，行为不变。
 */
class EpisodeSelectorAdapter(
    private var currentIndex: Int,
    /** true = 竖屏选集小格子（固定尺寸、只显示集数、灰底）；false = 网格（弹窗/底栏/详情页） */
    private val compact: Boolean = false,
    /** true = 详情页网格：配色走主题色、不做电影判定（播放器弹窗用默认 false，配色不变） */
    private val useThemeColors: Boolean = false,
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

    /** 当前已铺出来的集数（网格 = 已提交批次数，compact = 全量） */
    val shownEpisodeCount: Int
        get() = if (compact) currentList.size else loadedCount

    /**
     * footer 被绑上来时是否自动追加下一批。默认 true（RV 自身可滚动的场景：footer 只有
     * 滚到底才进 viewport）。铺在外层 ScrollView 里 wrap_content 全量 layout 的网格
     * （详情页）必须关掉——footer 常驻 viewport，自动追加会连锁把全部批次铺完。
     */
    var autoLoadOnFooterBind = true

    /** 每铺完一批回调一次（详情页用它刷新「收起（已显示N/M集）」文案）；默认不回调 */
    var onBatchLoaded: (() -> Unit)? = null

    /** 长按回调（详情页「从这一集复制播放地址」）；默认不处理，由外部按需接线 */
    var onEpisodeLongClick: ((Episode) -> Unit)? = null

    /** 「上次看到」的那一集（按 url 记）；null = 无历史。只有网格 bind 会画描边 */
    private var lastWatchedUrl: String? = null

    /** 「选中」的那一集（按 url 记）：详情页切线路/重新 submitList 后依然对得上 */
    private var selectedUrl: String? = null

    /** 从剧集名里抠集号：取第一个数字串（「第12集」「12集」「12」都能吃） */
    private val episodeNumRegex = Regex("""(\d+)""")

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
            // 铺在外层 ScrollView 里的网格要关掉（见 [autoLoadOnFooterBind]）。
            if (autoLoadOnFooterBind) loadMore()
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
        super.submitList(fullEpisodes.take(loadedCount)) {
            syncFooter()
            onBatchLoaded?.invoke()
        }
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

    /**
     * 把已提交范围一次性顶到覆盖 [position]（0 起）：详情页「自动展开到上次看到的那一集」
     * 用，别让高亮落在根本没渲染出来的位置上。
     */
    fun revealUpTo(position: Int) {
        if (compact || position < 0) return
        val target = minOf(position + 1, fullEpisodes.size)
        if (target <= loadedCount) return
        loadedCount = target
        super.submitList(fullEpisodes.take(loadedCount)) { syncFooter() }
    }

    /**
     * 标记「上次看到这一集」：对应格子上加一圈 2dp 主题色描边（布局里没有可复用的角标视图，
     * 描边是唯一不动布局的表达方式）。传 null 表示没有历史记录，全部清掉。
     */
    fun setLastWatchedEpisode(episode: Episode?) {
        val newUrl = episode?.url
        if (lastWatchedUrl == newUrl) return
        lastWatchedUrl = newUrl
        // 只刷剧集格子（super.getItemCount() 不含 footer），数量不大，整段刷新最省心
        if (super.getItemCount() > 0) notifyItemRangeChanged(0, super.getItemCount())
    }

    /**
     * 按 url 落地选中态：切线路/重新 submitList 后按 url 对得上，不会指错格子。
     * 传 null 表示取消选中。旧 url 与新 url 各自定位，只精确刷新这两格。
     */
    fun setSelectedEpisode(episode: Episode?) {
        val oldUrl = selectedUrl
        selectedUrl = episode?.url

        val oldPosition = oldUrl?.let { findPositionByUrl(it) } ?: RecyclerView.NO_POSITION
        val newPosition = selectedUrl?.let { findPositionByUrl(it) } ?: RecyclerView.NO_POSITION

        if (newPosition != RecyclerView.NO_POSITION) {
            if (oldPosition != RecyclerView.NO_POSITION && oldPosition != newPosition) {
                notifyItemChanged(oldPosition)
            }
            notifyItemChanged(newPosition)
        } else if (super.getItemCount() > 0) {
            // 目标集还不在 currentList 里（例如刚 submitList 还没提交完）：整段刷新兜底，
            // 新列表绑定时会按 selectedUrl 取态，避免高亮留在旧格子上
            notifyItemRangeChanged(0, super.getItemCount())
        }
    }

    private fun findPositionByUrl(url: String): Int =
        currentList.indexOfFirst { it.url == url }

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
            // 集号提取：compact 沿用老的 replace 写法（竖屏行为不变）；网格归一为
            // 「正则取第一个数字串，取不到回落下标+1」——replace 会产出 "12 特别篇" 这种脏值。
            // 常见「第X集」「X集」「X」三种格式两种写法输出一致
            val episodeNumber = if (compact) {
                episode.name.replace("第", "").replace("集", "")
            } else {
                episodeNumRegex.find(episode.name)?.groupValues?.get(1)
                    ?: (position + 1).toString()
            }
            binding.episodeNumber.text = episodeNumber
            binding.episodeNameText.text = episode.name
            // 假 VIP 角标已废弃：本适配器没有任何会点亮它的分支，这里显式收起，
            // 布局里的 vipBadge 保留只是为了不让 binding 缺字段（item_episode_tencent.xml 已标 tools:visibility=gone）
            binding.vipBadge.visibility = View.GONE

            // 判断是否为电影（时长≥60分钟）；详情页模式不做这个判定（详情页本来就没有）
            val isMovie = !useThemeColors && episode.durationMinutes >= 60

            // 使用 adapterPosition 而不是 position 参数来判断选中状态；
            // selectedUrl 是详情页的按 url 选中（切线路/重提交后对得上），播放器仍走 currentIndex
            val currentPosition = bindingAdapterPosition
            val isSelected = currentPosition != RecyclerView.NO_POSITION &&
                (currentPosition == currentIndex ||
                    (!selectedUrl.isNullOrEmpty() && episode.url == selectedUrl))

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
            } else if (useThemeColors) {
                // 详情页网格：全部走主题色，夜间模式下未选中的格子不再是白底黑字
                binding.episodeCard.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.surface_block)
                )
                binding.episodeNumber.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.theme_text_primary)
                )
                binding.episodeNameText.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.theme_text_secondary)
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

            // 「上次看到这一集」：2dp 主题色描边（详情页模式的角标表达）。
            // 选中态是实心底色，描边叠在上面依旧看得出；复用的 holder 必须显式清掉描边，
            // 否则角标会跟着格子乱跑
            val isLastWatched = !lastWatchedUrl.isNullOrEmpty() && episode.url == lastWatchedUrl
            if (isLastWatched) {
                binding.episodeCard.strokeWidth = dpToPx(2f)
                binding.episodeCard.setStrokeColor(
                    ContextCompat.getColor(binding.root.context, R.color.theme_primary)
                )
            } else {
                binding.episodeCard.strokeWidth = 0
            }

            // 无障碍：整格念「第X集 剧集名」，否则读屏只会念出一个孤立数字
            binding.episodeCard.contentDescription = "第${episodeNumber}集 ${episode.name}"

            binding.episodeCard.setOnClickListener {
                val clickPosition = bindingAdapterPosition
                if (clickPosition != RecyclerView.NO_POSITION) {
                    onEpisodeClick(clickPosition, episode)
                }
            }

            // 长按回调（详情页长按复制播放地址）；没接线时不消费长按事件
            binding.episodeCard.setOnLongClickListener {
                val handler = onEpisodeLongClick ?: return@setOnLongClickListener false
                val clickPosition = bindingAdapterPosition
                if (clickPosition == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                handler(episode)
                true
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
