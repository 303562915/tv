package com.tv.ball.presentation.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.domain.model.Episode

class DownloadEpisodeAdapter(
    private val episodes: List<Episode>,
    private val currentIndex: Int,
    private var multiSelectMode: Boolean = false,
    private val onEpisodeClick: (Episode) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<DownloadEpisodeAdapter.ViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val episodeName: TextView = view.findViewById(R.id.episodeName)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    if (multiSelectMode) {
                        // 多选模式：切换选中状态
                        if (selectedPositions.contains(position)) {
                            selectedPositions.remove(position)
                        } else {
                            selectedPositions.add(position)
                        }
                        notifyItemChanged(position)
                        onSelectionChanged?.invoke(selectedPositions.size)
                    } else {
                        // 单选模式：直接回调
                        onEpisodeClick(episodes[position])
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = episodes[position]
        holder.episodeName.text = episode.name
        
        // 根据模式显示/隐藏checkbox
        holder.checkbox.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        holder.checkbox.isChecked = selectedPositions.contains(position)
        
        // 标记当前播放的集
        if (position == currentIndex) {
            holder.episodeName.setTextColor(holder.itemView.context.getColor(android.R.color.holo_blue_dark))
        } else {
            holder.episodeName.setTextColor(holder.itemView.context.getColor(android.R.color.black))
        }
    }

    override fun getItemCount() = episodes.size
    
    fun setMultiSelectMode(enabled: Boolean) {
        multiSelectMode = enabled
        if (!enabled) {
            selectedPositions.clear()
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPositions.size)
    }
    
    fun selectAll(select: Boolean) {
        if (select) {
            selectedPositions.clear()
            selectedPositions.addAll(episodes.indices)
        } else {
            selectedPositions.clear()
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPositions.size)
    }
    
    fun getSelectedEpisodes(): List<Episode> {
        return selectedPositions.sorted().map { episodes[it] }
    }
}
