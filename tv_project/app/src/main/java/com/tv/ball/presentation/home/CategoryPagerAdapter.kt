package com.tv.ball.presentation.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R

/**
 * 首页分类页容器。
 *
 * 每页自己持有一套 RecyclerView 配置（LayoutManager / 网格 span / 滚动监听 / 视频适配器 + footer），
 * 只在 onCreateViewHolder 时装配一次。原来的写法每次 bind 都新建 LayoutManager 并再挂一个
 * OnScrollListener，ViewHolder 一复用就叠出多个监听，同一个分类被重复预加载、旧 LayoutManager
 * 还被监听闭包攥着不放。
 */
class CategoryPagerAdapter(
    private val categories: List<Category>,
    private val onVideoClick: (VideoItem) -> Unit,
    private val onLoadMore: (categoryId: Int) -> Unit,
    private val onRetry: (categoryId: Int) -> Unit,
    /** 某页 ViewHolder 刚绑定完成：调用方此时才能安全地往里推数据 */
    private val onPageBound: (position: Int) -> Unit
) : RecyclerView.Adapter<CategoryPagerAdapter.PageViewHolder>() {

    companion object {
        private const val SPAN_COUNT = 3

        /** 还剩几项就开始预取下一页（3 列，6 项约两行） */
        private const val PREFETCH_DISTANCE = 6
    }

    private val holders = mutableMapOf<Int, PageViewHolder>()

    fun getRecyclerView(position: Int): RecyclerView? = holders[position]?.recyclerView

    /** 该页已提交的视频项数（不含 footer） */
    fun getVideoItemCount(position: Int): Int = holders[position]?.videoAdapter?.itemCount ?: 0

    /**
     * 把某个分类页的数据交给它自己的适配器。
     * 列表实例没变时直接走回调，不做无意义的 diff。
     */
    fun submit(
        position: Int,
        items: List<VideoItem>,
        footer: PageFooterState,
        onCommitted: (() -> Unit)? = null
    ) {
        holders[position]?.submit(items, footer, onCommitted)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_video_list, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // ViewHolder 可能换位复用：先把它挂在旧 position 上的登记摘掉，否则 submit 会落到绑错的页
        val previousPosition = holder.boundPosition
        if (previousPosition >= 0 && previousPosition != position && holders[previousPosition] === holder) {
            holders.remove(previousPosition)
        }
        holder.boundPosition = position
        holders[position] = holder
        holder.bindCategory(categories[position].id)
        // ViewHolder 刚建好时把缓存的列表推进去：否则切 Tab 后先渲染空页，要等下一次状态变化才补上
        onPageBound(position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        val position = holder.boundPosition
        if (position >= 0 && holders[position] === holder) {
            holders.remove(position)
        }
        holder.boundPosition = -1
    }

    override fun getItemCount(): Int = categories.size

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val recyclerView: RecyclerView = itemView as RecyclerView
        val videoAdapter = VideoListAdapter { video -> onVideoClick(video) }

        private val footerAdapter = PageFooterAdapter { onRetry(categoryId) }
        private val concatAdapter = ConcatAdapter(videoAdapter, footerAdapter)
        private val layoutManager = GridLayoutManager(itemView.context, SPAN_COUNT)

        private var categoryId: Int = -1
        private var lastItems: List<VideoItem>? = null

        var boundPosition: Int = -1

        init {
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (position < videoAdapter.itemCount) 1 else SPAN_COUNT
            }

            recyclerView.layoutManager = layoutManager
            recyclerView.adapter = concatAdapter

            // 滚动监听只挂这一次；往下滑且接近底部才请求下一页
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy <= 0) return
                    val total = videoAdapter.itemCount
                    if (total == 0) return
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible == RecyclerView.NO_POSITION) return
                    if (lastVisible >= total - PREFETCH_DISTANCE && categoryId >= 0) {
                        onLoadMore(categoryId)
                    }
                }
            })
        }

        fun bindCategory(category: Int) {
            if (categoryId == category) return
            categoryId = category
            // 换到别的分类：作废旧列表身份，确保下一次 submit 一定走 diff，不残留上一页的内容
            lastItems = null
        }

        fun submit(items: List<VideoItem>, footer: PageFooterState, onCommitted: (() -> Unit)?) {
            footerAdapter.setState(footer)
            if (lastItems === items) {
                onCommitted?.invoke()
                return
            }
            lastItems = items
            videoAdapter.submitList(items) {
                // footer 的位置跟着视频数变化，diff 落地后重新算一遍 span 缓存
                layoutManager.spanSizeLookup.invalidateSpanIndexCache()
                layoutManager.spanSizeLookup.invalidateSpanGroupIndexCache()
                onCommitted?.invoke()
            }
            layoutManager.spanSizeLookup.invalidateSpanIndexCache()
            layoutManager.spanSizeLookup.invalidateSpanGroupIndexCache()
        }
    }
}