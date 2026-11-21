package com.tv.ball.presentation.videosource

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import kotlin.math.abs

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
        private val initialText: TextView = itemView.findViewById(R.id.initialText)
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

            bindInitial(source.name)

            itemView.setOnClickListener {
                onItemClick(source)
            }
        }

        /**
         * 头像：名字首字 + 按名字定色的圆形底。
         *
         * 不再去拉远程 logo——源站大多没有可用的图标，失败就整块空着，不如首字稳定。
         * 颜色由名字的 hash 决定，同一个源每次都同一个颜色，不会因为列表位置变化而跳。
         */
        private fun bindInitial(name: String) {
            val initial = name.trim().firstOrNull()?.let { ch ->
                when {
                    ch.isLetterOrDigit() -> ch.uppercaseChar().toString()
                    else -> "·"
                }
            } ?: "·"

            val palette = AVATAR_COLORS
            val color = palette[abs(name.hashCode()) % palette.size]
            initialText.text = initial
            initialText.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private companion object {
        /**
         * 头像底色：固定一组，亮 / 暗主题下都够深，白字压得住。
         *
         * 不用主题色：主题色只有一个，列表 6 条源全同色时首字头像反而不好区分。
         */
        val AVATAR_COLORS = intArrayOf(
            0xFF2F6BFF.toInt(),
            0xFF7A4BFF.toInt(),
            0xFFB23BFF.toInt(),
            0xFF00A3A3.toInt(),
            0xFFD9631E.toInt(),
            0xFFB0305A.toInt()
        )
    }
}