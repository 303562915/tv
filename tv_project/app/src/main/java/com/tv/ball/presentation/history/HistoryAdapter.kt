package com.tv.ball.presentation.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tv.ball.R
import com.tv.ball.databinding.ItemHistoryBinding
import com.tv.ball.databinding.ItemHistoryHeaderBinding
import com.tv.ball.util.ImageLoader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 历史列表的一行：分组标题或一条记录。
 *
 * 记录按「今天 / 昨天 / 更早」分段（分组由 [HistoryViewModel.groupedHistory] 给出），
 * 所以列表不能再当成扁平 List<HistoryItem> 直接喂给适配器。
 */
sealed class HistoryRow {
    data class Header(val title: String) : HistoryRow()
    data class Entry(val item: HistoryItem) : HistoryRow()
}

class HistoryAdapter(
    private val onItemClick: (HistoryItem) -> Unit,
    private val onItemLongClick: (HistoryItem) -> Unit,
    private val onItemDelete: (HistoryItem) -> Unit,
    private val onItemToggleSelection: (HistoryItem) -> Unit,
    /** 选中键的计算口径由 Fragment 传进来（HistoryViewModel 的删除键），适配器不另写一套推导 */
    private val selectionKeyOf: (HistoryItem) -> String
) : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(DiffCallback()) {

    /**
     * 条目是否可以左滑露出删除区。
     * 直接删除模式下 Fragment 把它置 false：容器不再抢横向手势，留给 ItemTouchHelper。
     * bind 时会把这个值同步给每一行的 SwipeRevealLayout，所以滚入屏幕的新行也是对的。
     */
    var swipeEnabled: Boolean = true

    /** 多选模式：点条目 / 长按条目都变成切换选中，横向滑动让位给点选 */
    private var selectionMode: Boolean = false

    /** 当前选中集，键与 ViewModel 的删除键同口径 */
    private var selectedKeys: Set<String> = emptySet()

    /**
     * 下发多选状态。
     *
     * rows 本身不包含选中态，只提交同样的 rows 时 DiffUtil 认为内容没变、不会重绑，
     * 勾选标记就会停在旧值上；所以选中集变化走 payload 单独刷，
     * 只有模式切换（点击语义 + 滑动手势都变）才额外刷一次滑动状态。
     */
    fun applySelectionState(inSelectionMode: Boolean, keys: Set<String>) {
        val modeChanged = selectionMode != inSelectionMode
        selectionMode = inSelectionMode
        selectedKeys = keys

        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        if (modeChanged) notifyItemRangeChanged(0, itemCount, PAYLOAD_SWIPE)
    }

    private fun isSelected(item: HistoryItem): Boolean =
        selectionMode && selectedKeys.contains(selectionKeyOf(item))

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is HistoryRow.Header) TYPE_HEADER else TYPE_ENTRY

    /**
     * 给 ItemTouchHelper 用：这个 ViewHolder 是不是可滑删的条目。
     * 分组标题不该被拖动 / 滑走，否则删完一条重新分组时手势会落到 header 上。
     */
    fun isSwipeableHolder(holder: RecyclerView.ViewHolder): Boolean =
        holder.itemViewType == TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryViewHolder(
                ItemHistoryBinding.inflate(inflater, parent, false),
                onItemClick,
                onItemLongClick,
                onItemDelete,
                onItemToggleSelection,
                { swipeEnabled && !selectionMode },
                { selectionMode }
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Header -> (holder as HeaderViewHolder).bind(row.title)
            is HistoryRow.Entry -> (holder as EntryViewHolder).bind(row.item, isSelected(row.item))
        }
    }

    /**
     * 带 payload 的绑定：切删除方式只刷滑动状态，切多选只刷勾选状态，
     * 都不重跑封面加载和进度计算（Fragment 用 notifyItemRangeChanged 带 payload 触发）。
     */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val row = getItem(position)
        if (holder is EntryViewHolder && row is HistoryRow.Entry) {
            val refreshSelection = payloads.contains(PAYLOAD_SELECTION)
            val refreshSwipe = payloads.contains(PAYLOAD_SWIPE)
            if (refreshSelection) holder.bindSelectionState(isSelected(row.item))
            if (refreshSwipe) holder.bindSwipeState()
            if (refreshSelection || refreshSwipe) return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private class HeaderViewHolder(
        private val binding: ItemHistoryHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.root.text = title
        }
    }

    private class EntryViewHolder(
        private val binding: ItemHistoryBinding,
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemLongClick: (HistoryItem) -> Unit,
        private val onItemDelete: (HistoryItem) -> Unit,
        private val onToggleSelection: (HistoryItem) -> Unit,
        private val isSwipeEnabled: () -> Boolean,
        private val isSelectionMode: () -> Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(historyItem: HistoryItem, selected: Boolean) {
            val context = binding.root.context
            val title = historyItem.videoItem.title
            binding.videoTitle.text = title

            // 剧集名来自源站解析，常带「[feifan]」「【线路1】」这类前缀，直接展示是脏数据
            val episode = cleanEpisodeName(historyItem.currentEpisode)
            if (episode != null) {
                binding.videoEpisode.text = episode
                binding.videoEpisode.visibility = View.VISIBLE
            } else {
                binding.videoEpisode.visibility = View.GONE
            }

            binding.videoMeta.text = buildString {
                episode?.let { append(it).append(" · ") }
                append(formatWatchedAt(historyItem.lastWatchedAt))
            }

            val duration = historyItem.duration
            val watched = historyItem.watchedPosition.coerceAtLeast(0L)
            val primary = ContextCompat.getColor(context, R.color.theme_primary)
            val secondary = ContextCompat.getColor(context, R.color.theme_text_secondary)

            when {
                // 只点开过、没真正播放：没有进度可言，就别摆一条 0% 的空轨道给人看
                duration <= 0L -> {
                    binding.progressBar.progress = 0
                    binding.tvPercent.setTextColor(secondary)
                    binding.tvPercent.text = context.getString(R.string.history_not_started)
                    binding.tvRemaining.visibility = View.GONE
                }
                // 看到 98% 以上按看完处理：剩下几秒没意义，还给「剩余」只会显得进度坏了
                watched * 100 / duration >= 98 -> {
                    binding.progressBar.progress = 100
                    binding.tvPercent.setTextColor(secondary)
                    binding.tvPercent.text = context.getString(R.string.history_finished)
                    binding.tvRemaining.visibility = View.GONE
                }
                else -> {
                    val percent = (watched * 100 / duration).toInt().coerceIn(0, 100)
                    binding.progressBar.progress = percent
                    binding.tvPercent.setTextColor(primary)
                    binding.tvPercent.text = context.getString(R.string.history_percent, percent)
                    binding.tvRemaining.visibility = View.VISIBLE
                    binding.tvRemaining.text = context.getString(
                        R.string.history_remaining,
                        formatDuration(duration - watched)
                    )
                }
            }

            ImageLoader.load(
                context = context,
                url = historyItem.videoItem.coverUrl,
                imageView = binding.videoCover,
                placeholder = R.drawable.ic_launcher_background
            )

            // 每次绑定都把当前的滑动模式同步给容器，滚入屏幕的新行不会拿到旧状态
            bindSwipeState()
            bindSelectionState(selected)

            // 根节点现在是 SwipeRevealLayout，里面的卡片自己 clickable，点击到不了根节点，
            // 所以条目点击要挂到内容卡片上；滑出来的删除区单独走 onItemDelete。
            // 多选模式下点击和长按都只切换选中，不进播放器；两个判断读的是实时状态，切模式不用重绑监听
            val content = binding.root.contentView ?: binding.root
            content.setOnClickListener {
                if (isSelectionMode()) onToggleSelection(historyItem) else onItemClick(historyItem)
            }
            content.setOnLongClickListener {
                if (isSelectionMode()) onToggleSelection(historyItem) else onItemLongClick(historyItem)
                true
            }
            binding.btnSwipeDelete.setOnClickListener { onItemDelete(historyItem) }
        }

        /**
         * 只同步选中态：勾选标记 + 卡片描边。
         * 主色只出现在这里，卡片底色不动，多选时列表整体观感不变。
         */
        fun bindSelectionState(selected: Boolean) {
            binding.selectionMark.visibility = if (selected) View.VISIBLE else View.GONE

            val card = binding.root.contentView as? MaterialCardView ?: return
            if (selected) {
                card.strokeColor = ContextCompat.getColor(card.context, R.color.theme_primary)
                card.strokeWidth =
                    (SELECTED_STROKE_DP * card.resources.displayMetrics.density).toInt()
            } else {
                card.strokeWidth = 0
            }
        }

        /**
         * 只同步滑动状态：切删除方式时走 payload 绑定，不重跑封面 / 进度那套逻辑。
         * 切成「滑动即删除」后容器不再接管横向手势，已经展开的删除区要收回去。
         */
        fun bindSwipeState() {
            val enabled = isSwipeEnabled()
            binding.root.swipeEnabled = enabled
            if (!enabled && binding.root.isOpened()) binding.root.close()
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
            when {
                oldItem is HistoryRow.Header && newItem is HistoryRow.Header ->
                    oldItem.title == newItem.title
                oldItem is HistoryRow.Entry && newItem is HistoryRow.Entry ->
                    // 身份键用 detailUrl（缺失时退到详情页 URL / 标题）：同名不同线路、不同集不再互相顶掉
                    historyIdentityKey(oldItem.item) == historyIdentityKey(newItem.item)
                else -> false
            }

        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1

        /** 切删除方式时用的 payload：只刷新滑动状态，不做一次全量 bind。 */
        const val PAYLOAD_SWIPE = "payload_history_swipe"

        /** 多选态变化时用的 payload：只刷新勾选标记和描边，不重跑封面与进度。 */
        private const val PAYLOAD_SELECTION = "payload_history_selection"

        /** 选中时卡片描边宽度（dp）：只做一圈主色细边，不铺整卡底色 */
        private const val SELECTED_STROKE_DP = 2f

        /** 方括号 / 圆括号包起来的线路名，例如「[feifan] 第01集」「【线路2】第3集」 */
        private val BRACKET_BLOCK = Regex("""[\[【(（][^\]】)）]{0,24}[\]】)）]""")
        private val ZERO_PADDED_EPISODE = Regex("""第0+(\d+)""")
        private val MULTI_SPACE = Regex("""\s+""")

        /**
         * 把源站给的剧集名洗成能给人看的样子：去掉线路前缀、压掉多余空白、第01集→第1集。
         * 洗不出有效内容时返回 null，让调用方把角标整块隐藏。
         */
        fun cleanEpisodeName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw
                .replace(BRACKET_BLOCK, " ")
                .replace(MULTI_SPACE, " ")
                .trim()
                .replace(ZERO_PADDED_EPISODE) { "第" + it.groupValues[1] }
            return cleaned.ifBlank { null }
        }

        /**
         * 观看时间：今天 / 昨天带时刻，同年只显示月日，跨年补年份。
         */
        fun formatWatchedAt(timestamp: Long): String {
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val startOfYesterday = startOfToday - 86_400_000L
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val watchedYear = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)

            return when {
                timestamp >= startOfToday -> "今天 " + clock(timestamp)
                timestamp >= startOfYesterday -> "昨天 " + clock(timestamp)
                watchedYear == currentYear ->
                    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))
                else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timestamp))
            }
        }

        private fun clock(timestamp: Long): String =
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))

        /** 剩余时长：一小时以上带时分秒，否则分秒 */
        private fun formatDuration(millis: Long): String {
            val totalSeconds = (millis / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }
}
