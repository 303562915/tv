package com.tv.ball.presentation.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R

data class ParsedLink(
    val url: String,
    val type: String,  // M3U8, MP4, FLV等
    val isCurrent: Boolean = false
)

class ParseLinkAdapter(
    private val links: List<ParsedLink>,
    private val onLinkClick: (ParsedLink) -> Unit
) : RecyclerView.Adapter<ParseLinkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typeLabel: TextView = view.findViewById(R.id.typeLabel)
        val linkTitle: TextView = view.findViewById(R.id.linkTitle)
        val linkUrl: TextView = view.findViewById(R.id.linkUrl)
        val currentLabel: TextView = view.findViewById(R.id.currentLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parse_link, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val link = links[position]
        
        holder.typeLabel.text = link.type
        holder.linkTitle.text = "链接 ${position + 1}"
        holder.linkUrl.text = link.url
        
        // 设置类型标签颜色
        val color = when (link.type) {
            "M3U8" -> 0xFF1E88E5.toInt()
            "MP4" -> 0xFF43A047.toInt()
            "FLV" -> 0xFFFB8C00.toInt()
            else -> 0xFF757575.toInt()
        }
        holder.typeLabel.setBackgroundColor(color)
        
        // 显示/隐藏当前播放标识
        holder.currentLabel.visibility = if (link.isCurrent) View.VISIBLE else View.GONE
        
        holder.itemView.setOnClickListener {
            onLinkClick(link)
        }
    }

    override fun getItemCount() = links.size
}
