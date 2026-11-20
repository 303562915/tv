package com.tv.ball.presentation.videosource

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.util.ImageLoader

class VideoSourceAdapter(
    private val sources: List<VideoSource>,
    private val onItemClick: (VideoSource) -> Unit
) : RecyclerView.Adapter<VideoSourceAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_source, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]
        holder.bind(source)
    }

    override fun getItemCount() = sources.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val speedText: TextView = itemView.findViewById(R.id.speedText)

        fun bind(source: VideoSource) {
            nameText.text = source.name

            // 卡片只表达结构：一律 card 底、无描边；状态落在文字上
            val cardView = itemView as com.google.android.material.card.MaterialCardView
            val context = itemView.context
            cardView.strokeWidth = 0
            cardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_card))
            nameText.setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))

            if (source.isSelected) {
                // 选中项：只留一个 primary 的标记
                speedText.text = "已选"
                speedText.setTextColor(ContextCompat.getColor(context, R.color.theme_primary))
            } else {
                val responseTime = source.responseTime
                if (responseTime != null) {
                    speedText.text = "${responseTime}ms"
                    val colorRes = when {
                        responseTime < 500 -> R.color.theme_success
                        responseTime < 1500 -> R.color.theme_warning
                        else -> R.color.theme_error
                    }
                    speedText.setTextColor(ContextCompat.getColor(context, colorRes))
                } else {
                    speedText.text = "测试中..."
                    speedText.setTextColor(
                        ContextCompat.getColor(context, R.color.theme_text_secondary)
                    )
                }
            }

            // 加载logo（失败时自动隐藏）
            ImageLoader.loadLogo(
                context = itemView.context,
                url = source.iconUrl,
                imageView = iconImage
            )

            itemView.setOnClickListener {
                onItemClick(source)
            }
        }
    }
}