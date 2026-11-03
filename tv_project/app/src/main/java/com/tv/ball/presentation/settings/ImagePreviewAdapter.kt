package com.tv.ball.presentation.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.util.ImageLoader

/**
 * 图片预览适配器 - 用于ViewPager2
 */
class ImagePreviewAdapter(private val images: List<String>) : 
    RecyclerView.Adapter<ImagePreviewAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_preview, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val imageUrl = images[position]
        ImageLoader.load(
            context = holder.itemView.context,
            url = imageUrl,
            imageView = holder.imageView,
            placeholder = R.drawable.ic_play
        )
    }

    override fun getItemCount() = images.size

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.ivImage)
    }
}
