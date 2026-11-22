package com.tv.ball.presentation.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemEpisodeDetailBinding
import com.tv.ball.domain.model.Episode

/**
 * 详情页「选集」适配器 —— **只用于详情页 6 列网格**
 * （[VideoDetailActivity] 的 rvEpisodes，布局 item_episode_detail.xml）。
 *
 * 本仓库历史上有三套选集实现并存，改动前先看清是哪一套：
 * 1. 本文件：详情页 6 列网格，唯一在用的详情页选集实现；
 * 2. 播放器选集：见播放器页的 EpisodeSelectorAdapter，不要在这里加播放器逻辑；
 * 3. [GroupedEpisodeAdapter]：分组网格版。grep 确认 0 处外部引用（只剩自身文件 + item_episode.xml 的
 *    tools 预览），属死代码，应随清理删除；删除前不要往它里面加功能，也不要把本文件的逻辑同步过去。
 *
 * 选中态与「上次看到」约定：
 * - 选中按 [Episode.url] 记，不按下标：切线路 / 重新 submitList 后下标指向的集已经变了。
 * - 「上次看到这一集」只用 2dp 主题色描边表达（与短剧详情一致）。item_episode.xml 里的
 *   lastWatchedBadge 角标是第 3 套实现的历史遗留，本适配器不使用、不显示。
 */
class EpisodeAdapter(
    private val onEpisodeClick: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.EpisodeViewHolder>(EpisodeDiffCallback()) {

    /** 长按回调（例如「从这一集开始下载」）；默认不处理，由外部按需接线 */
    var onEpisodeLongClick: ((Episode) -> Unit)? = null

    /** 当前选中的那一集：按 url 记，切线路/submitList 后依然对得上 */
    private var selectedUrl: String? = null

    /** 上次看到的那一集（按 url 记，切换线路/重新 submitList 后依然对得上） */
    private var lastWatchedUrl: String? = null

    /** 从剧集名里抠集号：取第一个数字串（「第12集」「12集」「12」都能吃） */
    private val episodeNumRegex = Regex("""(\d+)""")

    /**
     * 标记「上次看到这一集」：加一圈主题色描边，和「正在播」的实心底色不是一个东西。
     * 传 null 表示没有历史记录，全部清掉。
     */
    fun setLastWatchedEpisode(episode: Episode?) {
        val newUrl = episode?.url
        if (lastWatchedUrl == newUrl) return
        lastWatchedUrl = newUrl
        // itemCount 不大（一屏几十个格子），整段刷新最省心，也不会漏掉复用出去的 holder
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 兼容旧调用：按 position 取出那一集，再按 url 记选中（越界/传 -1 等同取消选中）。
     */
    fun setSelectedPosition(position: Int) {
        applySelectedUrl(currentList.getOrNull(position)?.url)
    }

    /**
     * 按剧集设置选中：内部按 url 记，所以切线路后不会指错格子。传 null 表示取消选中。
     */
    fun setSelectedEpisode(episode: Episode?) {
        applySelectedUrl(episode?.url)
    }

    /**
     * 按 url 落地选中态：旧 url 与新 url 各自定位，只精确刷新这两格。
     */
    private fun applySelectedUrl(url: String?) {
        val oldUrl = selectedUrl
        selectedUrl = url

        val oldPosition = oldUrl?.let { findPositionByUrl(it) } ?: RecyclerView.NO_POSITION
        val newPosition = url?.let { findPositionByUrl(it) } ?: RecyclerView.NO_POSITION

        if (newPosition != RecyclerView.NO_POSITION) {
            if (oldPosition != RecyclerView.NO_POSITION && oldPosition != newPosition) {
                notifyItemChanged(oldPosition)
            }
            notifyItemChanged(newPosition)
        } else if (itemCount > 0) {
            // 目标集还不在 currentList 里（例如刚 submitList 还没提交完）：整段刷新兜底，
            // 新列表绑定时会按 selectedUrl 取态，避免高亮留在旧格子上
            notifyItemRangeChanged(0, itemCount)
        }
    }

    private fun findPositionByUrl(url: String): Int =
        currentList.indexOfFirst { it.url == url }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeDetailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(position)
        // 按 url 判选中：position 只用来兜底集号，不参与选中判定
        holder.bind(episode, position, episode.url == selectedUrl)
    }

    inner class EpisodeViewHolder(
        private val binding: ItemEpisodeDetailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density + 0.5f).toInt()

        fun bind(episode: Episode, position: Int, isSelected: Boolean) {
            val context = binding.root.context

            // 集号：正则取第一个数字串，取不到时回落到「下标 + 1」。
            // 老写法 replace("第","").replace("集","") 会产出 "12 特别篇" 这种脏值
            val episodeNumber = episodeNumRegex.find(episode.name)?.groupValues?.get(1)
                ?: (position + 1).toString()

            binding.episodeNumber.text = episodeNumber
            binding.episodeNameText.text = episode.name
            // VIP 标签当前没有业务判定，统一收起
            binding.vipBadge.visibility = View.GONE

            // 配色全部走主题色：夜间模式下未选中的格子不再是白底黑字
            val cardColorRes: Int
            val mainTextColorRes: Int
            val subTextColorRes: Int
            if (isSelected) {
                // 选中状态：主题色实心底 + onPrimary 文字（夜间同样是白字）
                cardColorRes = R.color.primary
                mainTextColorRes = R.color.theme_on_primary
                subTextColorRes = R.color.theme_on_primary
            } else {
                // 未选中状态：块状背景 + 正文/次要文字
                cardColorRes = R.color.surface_block
                mainTextColorRes = R.color.theme_text_primary
                subTextColorRes = R.color.theme_text_secondary
            }
            binding.episodeCard.setCardBackgroundColor(ContextCompat.getColor(context, cardColorRes))
            binding.episodeNumber.setTextColor(ContextCompat.getColor(context, mainTextColorRes))
            binding.episodeNameText.setTextColor(ContextCompat.getColor(context, subTextColorRes))

            // 「上次看到这一集」：2dp 主题色描边（唯一表达方式）。选中态是实心底色，描边叠在上面依旧看得出
            val isLastWatched = !lastWatchedUrl.isNullOrEmpty() && episode.url == lastWatchedUrl
            if (isLastWatched) {
                binding.episodeCard.strokeWidth = dp(2)
                binding.episodeCard.setStrokeColor(
                    ContextCompat.getColor(context, R.color.theme_primary)
                )
            } else {
                // 复用的 holder 必须显式清掉描边，否则角标会跟着格子乱跑
                binding.episodeCard.strokeWidth = 0
            }

            // 无障碍：整格念「第X集 剧集名」，否则读屏只会念出一个孤立数字
            binding.episodeCard.contentDescription = "第${episodeNumber}集 ${episode.name}"

            binding.episodeCard.setOnClickListener {
                // 用 bindingAdapterPosition 而不是 bind 时的 position：holder 可能已经挪位
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                // 先把选中态钉在 url 上再回调：回调里可能切源/换列表，等返回时列表已经不是这一份了
                this@EpisodeAdapter.setSelectedEpisode(episode)
                onEpisodeClick(episode)
            }

            binding.episodeCard.setOnLongClickListener {
                val handler = this@EpisodeAdapter.onEpisodeLongClick
                    ?: return@setOnLongClickListener false
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@setOnLongClickListener false
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
}
