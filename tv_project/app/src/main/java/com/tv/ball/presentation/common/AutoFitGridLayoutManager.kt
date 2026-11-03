package com.tv.ball.presentation.common

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * 自适应列数的GridLayoutManager
 * 根据item的最小宽度自动计算列数
 */
class AutoFitGridLayoutManager(
    private val mContext: Context,
    private val columnWidth: Int // item的最小宽度（dp）
) : GridLayoutManager(mContext, 1) {

    private var columnWidthChanged = true
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        val width = width
        val height = height
        if (columnWidthChanged && width > 0 && height > 0) {
            val displayMetrics = mContext.resources.displayMetrics
            val columnWidthPx = (columnWidth * displayMetrics.density + 0.5f).toInt()
            val totalSpace = if (orientation == VERTICAL) {
                width - paddingRight - paddingLeft
            } else {
                height - paddingTop - paddingBottom
            }
            val spanCount = max(1, totalSpace / columnWidthPx)
            setSpanCount(spanCount)
            columnWidthChanged = false
        }
        if (lastWidth != width || lastHeight != height) {
            columnWidthChanged = true
        }
        lastWidth = width
        lastHeight = height
        super.onLayoutChildren(recycler, state)
    }
}
