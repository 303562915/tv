package com.tv.ball.presentation.videosource

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
            // 显示名称
            nameText.text = source.name

            // 根据选中状态设置卡片样式
            val cardView = itemView as com.google.android.material.card.MaterialCardView
            if (source.isSelected) {
                cardView.setCardBackgroundColor(itemView.context.getColor(R.color.theme_selected_bg))
                cardView.strokeColor = itemView.context.getColor(R.color.theme_selected_border)
                cardView.strokeWidth = 4
                speedText.text = "已选"
                speedText.setTextColor(itemView.context.getColor(R.color.theme_selected_border))
                nameText.setTextColor(itemView.context.getColor(R.color.black))
                nameText.alpha = 1.0f
            } else {
                cardView.strokeWidth = 0

                // 根据响应时间设置整个卡片的背景颜色
                val responseTime = source.responseTime
                if (responseTime != null) {
                    speedText.text = "${responseTime}ms"

                    // 根据响应时间设置颜色（浅色背景）
                    // 0-500ms: 浅绿色 (优秀)
                    // 500-1000ms: 浅黄色 (良好)
                    // 1000-1500ms: 浅橙色 (一般)
                    // 1500ms+: 浅红色 (较慢)
                    val backgroundColor = when {
                        responseTime < 500 -> Color.parseColor("#E8F5E9") // 浅绿色
                        responseTime < 1000 -> Color.parseColor("#FFF9C4") // 浅黄色
                        responseTime < 1500 -> Color.parseColor("#FFE0B2") // 浅橙色
                        else -> Color.parseColor("#FFCDD2") // 浅红色
                    }

                    val textColor = when {
                        responseTime < 500 -> Color.parseColor("#2E7D32") // 深绿色
                        responseTime < 1000 -> Color.parseColor("#F57F17") // 深黄色
                        responseTime < 1500 -> Color.parseColor("#E65100") // 深橙色
                        else -> Color.parseColor("#C62828") // 深红色
                    }

                    cardView.setCardBackgroundColor(backgroundColor)
                    speedText.setTextColor(textColor)
                    nameText.setTextColor(textColor)
                    nameText.alpha = 0.9f
                } else {
                    // 测试中显示灰色
                    cardView.setCardBackgroundColor(itemView.context.getColor(R.color.unselected_bg))
                    speedText.text = "测试中..."
                    speedText.setTextColor(itemView.context.getColor(R.color.unselected_text))
                    nameText.setTextColor(itemView.context.getColor(R.color.unselected_text))
                    nameText.alpha = 0.7f
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
