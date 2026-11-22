package com.tv.ball.presentation.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.domain.model.Episode

/**
 * 分组显示剧集的Adapter
 * 每个线路显示为一个分组，包含标题和剧集网格
 */
class GroupedEpisodeAdapter(
    private val onEpisodeClick: (Episode) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private var groups: List<EpisodeGroup> = emptyList()
    private var selectedEpisode: Episode? = null

    /** 上次看到的那一集（按 url 记） */
    private var lastWatchedUrl: String? = null
    
    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EPISODES = 1
    }
    
    data class EpisodeGroup(
        val sourceName: String,  // 线路名称，如 "线路1"
        val episodes: List<Episode>
    )
    
    /**
     * 设置剧集列表，自动按线路分组
     */
    fun setEpisodes(episodes: List<Episode>) {
        // 按线路名称分组
        val grouped = episodes.groupBy { episode ->
            // 从 "[线路1] 第1集" 中提取 "线路1"
            val match = """\[([^\]]+)\]""".toRegex().find(episode.name)
            match?.groupValues?.get(1) ?: "默认线路"
        }.map { (sourceName, episodeList) ->
            // 移除剧集名称中的线路前缀
            val cleanedEpisodes = episodeList.map { episode ->
                val cleanName = episode.name.replace("""\[[^\]]+\]\s*""".toRegex(), "")
                episode.copy(name = cleanName)
            }
            EpisodeGroup(sourceName, cleanedEpisodes)
        }
        
        groups = grouped
        notifyDataSetChanged()
    }
    
    /**
     * 设置选中的剧集
     */
    fun setSelectedEpisode(episode: Episode?) {
        selectedEpisode = episode
        notifyDataSetChanged()
    }

    /**
     * 标记「上次看到这一集」：对应格子上加一个小圆点角标。
     * 传 null 表示没有历史记录（或标题对不上），全部不标记。
     */
    fun setLastWatchedEpisode(episode: Episode?) {
        lastWatchedUrl = episode?.url
        notifyDataSetChanged()
    }
    
    override fun getItemCount(): Int {
        // 每个分组包含2个item：标题 + 剧集网格
        return groups.size * 2
    }
    
    override fun getItemViewType(position: Int): Int {
        // 偶数位置是标题，奇数位置是剧集网格
        return if (position % 2 == 0) VIEW_TYPE_HEADER else VIEW_TYPE_EPISODES
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_episode_group_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_episode_group_episodes, parent, false)
                EpisodesViewHolder(view, onEpisodeClick)
            }
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val groupIndex = position / 2
        val group = groups[groupIndex]
        
        when (holder) {
            is HeaderViewHolder -> holder.bind(group.sourceName)
            is EpisodesViewHolder -> holder.bind(group.episodes, selectedEpisode, lastWatchedUrl)
        }
    }
    
    /**
     * 标题ViewHolder
     */
    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSourceName: TextView = itemView.findViewById(R.id.tvSourceName)
        
        fun bind(sourceName: String) {
            tvSourceName.text = sourceName
        }
    }
    
    /**
     * 剧集网格ViewHolder
     */
    class EpisodesViewHolder(
        itemView: View,
        private val onEpisodeClick: (Episode) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val recyclerView: RecyclerView = itemView.findViewById(R.id.rvEpisodes)
        private val adapter = EpisodeGridAdapter(onEpisodeClick)
        
        init {
            recyclerView.layoutManager = GridLayoutManager(itemView.context, 4)
            recyclerView.adapter = adapter
        }
        
        fun bind(episodes: List<Episode>, selectedEpisode: Episode?, lastWatchedUrl: String?) {
            adapter.setEpisodes(episodes, selectedEpisode, lastWatchedUrl)
        }
    }
    
    /**
     * 剧集网格内部的Adapter
     */
    private class EpisodeGridAdapter(
        private val onEpisodeClick: (Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeGridAdapter.EpisodeViewHolder>() {
        
        private var episodes: List<Episode> = emptyList()
        private var selectedEpisode: Episode? = null
        private var lastWatchedUrl: String? = null

        fun setEpisodes(episodes: List<Episode>, selectedEpisode: Episode?, lastWatchedUrl: String?) {
            this.episodes = episodes
            this.selectedEpisode = selectedEpisode
            this.lastWatchedUrl = lastWatchedUrl
            notifyDataSetChanged()
        }
        
        override fun getItemCount() = episodes.size
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return EpisodeViewHolder(view, onEpisodeClick)
        }
        
        override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
            val episode = episodes[position]
            val isSelected = selectedEpisode?.url == episode.url
            val isLastWatched = !lastWatchedUrl.isNullOrEmpty() && episode.url == lastWatchedUrl
            holder.bind(episode, isSelected, isLastWatched)
        }
        
        class EpisodeViewHolder(
            itemView: View,
            private val onEpisodeClick: (Episode) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val tvEpisode: TextView = itemView.findViewById(R.id.tvEpisode)
            private val lastWatchedBadge: TextView = itemView.findViewById(R.id.lastWatchedBadge)

            fun bind(episode: Episode, isSelected: Boolean, isLastWatched: Boolean) {
                tvEpisode.text = episode.name

                // 「上次看到这一集」的小圆点：换成别的格子时必须显式收掉，否则会跟着复用乱跑
                lastWatchedBadge.visibility = if (isLastWatched) View.VISIBLE else View.GONE
                
                // 设置选中状态的样式
                if (isSelected) {
                    itemView.setBackgroundColor(
                        ContextCompat.getColor(itemView.context, R.color.theme_primary)
                    )
                    tvEpisode.setTextColor(
                        ContextCompat.getColor(itemView.context, android.R.color.white)
                    )
                } else {
                    itemView.setBackgroundColor(
                        ContextCompat.getColor(itemView.context, android.R.color.white)
                    )
                    tvEpisode.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.theme_text_primary)
                    )
                }
                
                itemView.setOnClickListener {
                    onEpisodeClick(episode)
                }
            }
        }
    }
}
