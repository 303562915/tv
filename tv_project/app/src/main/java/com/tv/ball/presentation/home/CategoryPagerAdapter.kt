package com.tv.ball.presentation.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.presentation.detail.VideoDetailActivity

class CategoryPagerAdapter(
    private val categories: List<Category>,
    private val viewModel: HomeViewModel,
    private val onScrollListener: (RecyclerView, Int, Int) -> Unit
) : RecyclerView.Adapter<CategoryPagerAdapter.PageViewHolder>() {

    private val adapters = mutableMapOf<Int, VideoListAdapter>()
    private val recyclerViews = mutableMapOf<Int, RecyclerView>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_video_list, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val category = categories[position]
        holder.bind(category, position)
    }

    override fun getItemCount() = categories.size

    fun getAdapter(position: Int): VideoListAdapter? = adapters[position]

    fun getRecyclerView(position: Int): RecyclerView? = recyclerViews[position]

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val recyclerView: RecyclerView = itemView.findViewById(R.id.recyclerView)

        fun bind(category: Category, position: Int) {
            // 创建或复用 adapter
            val adapter = adapters.getOrPut(position) {
                VideoListAdapter { video ->
                    val intent = Intent(itemView.context, VideoDetailActivity::class.java)
                    intent.putExtra("DETAIL_URL", video.detailUrl)
                    intent.putExtra("COVER_URL", video.coverUrl)
                    itemView.context.startActivity(intent)
                }
            }

            val layoutManager = GridLayoutManager(itemView.context, 3)
            recyclerView.layoutManager = layoutManager
            recyclerView.adapter = adapter

            // 添加滚动监听
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                    val totalItemCount = layoutManager.itemCount

                    // 智能预加载
                    viewModel.checkAndPreload(lastVisiblePosition, totalItemCount)

                    // 通知外部滚动事件
                    onScrollListener(recyclerView, dx, dy)
                }
            })

            // 保存引用
            adapters[position] = adapter
            recyclerViews[position] = recyclerView
        }
    }
}
