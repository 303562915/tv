package com.tv.ball.presentation.shortdrama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemShortDramaBinding
import com.tv.ball.util.ImageLoader
import java.util.Locale

/**
 * 短剧卡片适配器。
 *
 * 为什么卡片信息（分类/集数/年份、评分/播放量、完结角标、继续观看）都放在这里算：
 * 布局已经由另一位同学铺好了 4 个信息位（tvEpisodes / tvPlayCount / tvBadge / tvProgress），
 * 列表页（ShortDramaActivity）与首页「免费短剧」Tab（ShortDramaFragment）共用这个 Adapter，
 * 把「有就显示、没有就留空」的规则集中在一处，两处界面才不会一边显示「集」一边不显示。
 */
class ShortDramaAdapter(
    private val onItemClick: (ShortDramaItem) -> Unit
) : ListAdapter<ShortDramaItem, ShortDramaAdapter.ViewHolder>(DiffCallback()) {

    /**
     * 进度变化监听器的实例。
     * 必须是同一个对象才能 removeListener 摘掉，所以存成字段，而不是每次现造一个 lambda。
     */
    private var progressListener: (() -> Unit)? = null

    init {
        // 稳定 id：用 bookId 的 hashCode 当 item id（bookId 形如 "360zy:110377"，源 key + vod_id，全局唯一）。
        // 为什么需要：从详情页返回、或进度监听触发 notifyItemRangeChanged 时，没有稳定 id 的话
        // RecyclerView 只能按位置比对，整片重绑容易闪一下；有 id 才能认出「这一条还是那一条」。
        // 为什么用 hashCode：RecyclerView 只接受 Long，字符串得转；hashCode 理论上可能撞，
        // 但撞了只影响动画/复用观感，不影响数据正确性 —— 绑定用的仍是 getItem(position)，
        // DiffCallback 也按 bookId 比较，id 不参与数据判定。
        setHasStableIds(true)
    }

    /** 稳定 id 的具体来源，见 init 里的说明；只用于动画/复用识别，不做数据键 */
    override fun getItemId(position: Int): Long = getItem(position).bookId.hashCode().toLong()

    /**
     * 为什么要在挂到 RecyclerView 时才注册监听：
     * 进度不在 ShortDramaItem 里，DiffCallback 比较不到它 —— 用户看完一集回到列表，
     * 数据没变、列表就不会重绑，卡片上还是旧的「看到第 N 集」。这里补一条通知链路。
     */
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val listener: () -> Unit = {
            // 再 post 一帧才 notify：进度是播放页写完回调过来的，此刻列表可能正在
            // layout / onBind 过程中，直接 notify 会撞上
            // "Cannot call this method while RecyclerView is computing a layout or scrolling"。
            recyclerView.post { notifyItemRangeChanged(0, itemCount) }
        }
        // 同一个 adapter 万一被挂到第二个 RecyclerView 上，先摘掉旧监听器，避免泄漏 + 重复回调
        progressListener?.let { ShortDramaProgressStore.removeListener(it) }
        progressListener = listener
        ShortDramaProgressStore.addListener(listener)
    }

    /** 摘监听，避免 RecyclerView/Activity 销毁后监听器还持有适配器与旧视图 */
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        progressListener?.let { ShortDramaProgressStore.removeListener(it) }
        progressListener = null
    }

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

            // 「分类 · 集数 · 年份」：采集源经常只给其中一项，也可能给 "0"。
            // 先各自算成「片段」，再 filter + join，空值不会拼出孤立的「集」字，
            // 也不会留下 " · " 这种悬空分隔符。
            // 分类（type，来自 vod_class）原来抓了却从不显示，用户看不出是什么题材；
            // 排在第一位，因为它比集数/年份更能决定这张卡点不点。
            // 注意：仓库层不会拿 vod_remarks（「更新至80集」这类状态备注）冒充分类，
            // 否则这里会拼出「更新至80集 · 80集 · 2023」，同一个集数写两遍。
            val typePart = item.type.trim()
            val episodePart = item.episodeCnt.trim()
                .takeIf { it.isNotEmpty() && it != "0" }
                ?.let { "${it}集" }
                .orEmpty()
            val yearPart = item.year.trim()
            val episodesText = listOf(typePart, episodePart, yearPart)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            binding.tvEpisodes.text = episodesText

            // 右侧信息位：有评分优先显示「x.x分」（比播放量更能影响点不点），
            // 没评分才退回播放量；playCnt 为 "0"/空 = 源没给，留空而不是写个 0。
            val score = item.score.trim()
            val playCountText = when {
                score.isNotEmpty() -> "${score}分"
                item.playCnt.isBlank() || item.playCnt == "0" -> ""
                else -> formatPlayCount(item.playCnt)
            }
            binding.tvPlayCount.text = playCountText

            // 两个信息位都可能全空（源既没给集数/年份，也没给评分/播放量）。这时文案是空串，
            // 但 View 还是 wrap_content 的可见 View，各自带 8dp paddingBottom，
            // 卡片底部就凭空多出一条空白。所以按「有没有内容」决定可见性，GONE 后不占位也不留 padding。
            // 注意：每次 bind 都按当前数据重设（不是只在空的时候设 GONE），
            // RecyclerView 复用 ViewHolder 时上一张卡的 VISIBLE/GONE 才不会被带到下一张卡上。
            binding.tvEpisodes.visibility = if (episodesText.isNotEmpty()) View.VISIBLE else View.GONE
            binding.tvPlayCount.visibility =
                if (playCountText.isNotEmpty()) View.VISIBLE else View.GONE

            // 完结角标：只有明确完结才显示。GONE 而不是 INVISIBLE，未完结时不留空档。
            binding.tvBadge.visibility = if (item.finished) View.VISIBLE else View.GONE

            // 继续观看：进度存在且 episodeNum > 0 才显示「看到第N集」。
            // 这里读的是 SharedPreferences（首次读之后走内存缓存），onBind 里读不会触发磁盘 IO；
            // 记录进度是详情/播放页的责任，列表页只读不写。
            val watchedEpisode = ShortDramaProgressStore.progressOf(item.bookId)?.episodeNum ?: 0
            if (watchedEpisode > 0) {
                binding.tvProgress.text = "看到第${watchedEpisode}集"
                binding.tvProgress.visibility = View.VISIBLE
            } else {
                // 文本也清掉：RecyclerView 复用 ViewHolder 时，否则会把上一部剧的进度带到下一张卡上
                binding.tvProgress.text = ""
                binding.tvProgress.visibility = View.GONE
            }

            ImageLoader.load(
                context = binding.root.context,
                url = item.cover,
                imageView = binding.ivCover,
                placeholder = R.drawable.ic_placeholder,
                // 封面是 3:4（布局里 layout_constraintDimensionRatio="H,3:4"），
                // ImageLoader 默认 400x600 是 2:3，centerCrop 会把上下裁掉；这里按比例给 360x480，
                // 也避免平板 4~5 列时按 400px 宽解码造成的过采样浪费。
                width = 360,
                height = 480
            )

            // 封面是纯装饰图，但读屏用户需要知道这张卡讲什么，所以用标题做无障碍描述。
            // 放在 bind 里而不是布局里写死：标题是数据驱动的，ViewHolder 复用时必须跟着换。
            binding.ivCover.contentDescription = item.title

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun formatPlayCount(count: String): String {
            return try {
                val num = count.toLong()
                when {
                    // 必须指定 Locale：String.format 默认吃系统语言，
                    // 德语/法语等用逗号作小数点的语言下会输出「1,5万」，看起来像 15 万。
                    num >= 100000000 -> String.format(Locale.US, "%.1f亿", num / 100000000.0)
                    num >= 10000 -> String.format(Locale.US, "%.1f万", num / 10000.0)
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