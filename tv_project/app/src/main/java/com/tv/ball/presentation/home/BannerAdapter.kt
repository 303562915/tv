package com.tv.ball.presentation.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.tv.ball.R
import com.tv.ball.util.ImageLoader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class BannerAdapter(
    private var items: List<BannerItem> = emptyList()
) : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

    init {
        Log.d("BannerAdapter", "初始化，数据数量: ${items.size}")
    }

    fun updateData(newItems: List<BannerItem>) {
        items = newItems
        Log.d("BannerAdapter", "更新数据，新数据数量: ${items.size}")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
        Log.d("BannerAdapter", "创建ViewHolder")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_banner, parent, false)
        return BannerViewHolder(view)
    }

    override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
        val actualPosition = position % items.size
        val item = items[actualPosition]
        Log.d("BannerAdapter", "绑定位置: $position, 实际位置: $actualPosition, 图片URL: ${item.imageUrl}")
        holder.bind(item)
    }

    override fun getItemCount(): Int {
        val count = if (items.isEmpty()) 0 else Int.MAX_VALUE
        Log.d("BannerAdapter", "getItemCount: $count, items.size: ${items.size}")
        return count
    }

    inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.bannerCard)
        private val imageView: ImageView = itemView.findViewById(R.id.bannerImage)
        private val descriptionView: TextView = itemView.findViewById(R.id.bannerDescription)

        fun bind(item: BannerItem) {
            Log.d("BannerAdapter", "开始加载图片: ${item.imageUrl}")
            
            // 使用优化的ImageLoader加载轮播图（高优先级）
            // 不传placeholder参数，空图片时显示空白
            ImageLoader.loadBanner(
                context = itemView.context,
                url = item.imageUrl,
                imageView = imageView,
                placeholder = 0, // 不使用占位图
                onSuccess = {
                    Log.d("BannerAdapter", "图片加载成功: ${item.imageUrl}")
                },
                onError = {
                    Log.e("BannerAdapter", "图片加载失败: ${item.imageUrl}")
                }
            )

            if (item.visible && item.description.isNotEmpty()) {
                descriptionView.text = item.description
                descriptionView.visibility = View.VISIBLE
            } else {
                descriptionView.visibility = View.GONE
            }

            card.setOnClickListener {
                // 统计点击次数
                if (item.id > 0) {
                    recordBannerClick(item.id)
                }

                if (item.linkUrl.startsWith("http://") || item.linkUrl.startsWith("https://")) {
                    // 使用内置 WebView 打开
                    val intent = Intent(itemView.context, com.tv.ball.presentation.webview.WebViewActivity::class.java)
                    intent.putExtra("url", item.linkUrl)
                    itemView.context.startActivity(intent)
                }
            }
        }

        private fun recordBannerClick(bannerId: Int) {
            Thread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val json = org.json.JSONObject()
                    json.put("id", bannerId)

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val body = json.toString().toRequestBody(mediaType)

                    val request = okhttp3.Request.Builder()
                        .url("https://sk.nasxiaolin.top/app/api/banner_click.php")
                        .post(body)
                        .build()

                    client.newCall(request).execute()
                    Log.d("BannerAdapter", "轮播图点击统计成功: $bannerId")
                } catch (e: Exception) {
                    Log.e("BannerAdapter", "轮播图点击统计失败: ${e.message}")
                }
            }.start()
        }
    }
}
