package com.tv.ball.presentation.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
    private val onItemLongClick: (HistoryItem) -> Unit
) : ListAdapter<HistoryRow, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is HistoryRow.Header) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryViewHolder(
                ItemHistoryBinding.inflate(inflater, parent, false),
                onItemClick,
                onItemLongClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is HistoryRow.Header -> (holder as HeaderViewHolder).bind(row.title)
            is HistoryRow.Entry -> (holder as EntryViewHolder).bind(row.item)
        }
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
        private val onItemLongClick: (HistoryItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(historyItem: HistoryItem) {
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

            binding.root.setOnClickListener { onItemClick(historyItem) }
            binding.root.setOnLongClickListener {
                onItemLongClick(historyItem)
                true
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
            when {
                oldItem is HistoryRow.Header && newItem is HistoryRow.Header ->
                    oldItem.title == newItem.title
                oldItem is HistoryRow.Entry && newItem is HistoryRow.Entry ->
                    oldItem.item.videoItem.title == newItem.item.videoItem.title
                else -> false
            }

        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1

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
