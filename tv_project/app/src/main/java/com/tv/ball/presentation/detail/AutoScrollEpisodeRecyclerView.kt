package com.tv.ball.presentation.detail

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tv.ball.R

/**
 * 选集弹窗 / 对话框专用的 RecyclerView。
 *
 * 目的只有一个：`submitList()` 之后把「当前正在播的那一集」滚进可视区（尽量居中），
 * 用户打开选集弹窗时不用自己翻到正在看的那一集。
 *
 * 用法：XML 里把 `androidx.recyclerview.widget.RecyclerView` 换成这个类即可，
 * 调用方（播放器）代码一行都不用改——它会监听 adapter 的数据变化自动触发。
 *
 * 当前集的下标按下面的顺序猜：
 * 1. adapter 上的 `currentIndex` 字段（选集 Adapter 都是这个约定）；
 * 2. 兜底：扫已挂载的 item，看哪个卡片被涂成了主题色（当前集的样式）。
 * 两条都拿不到就安静放弃，绝不做「滚到第 0 个」这种误伤。
 */
class AutoScrollEpisodeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 每次数据变化自增，用来作废掉旧的那一次延迟滚动 */
    private var scrollToken = 0

    private val dataObserver = object : AdapterDataObserver() {
        override fun onChanged() = scheduleAutoScroll()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleAutoScroll()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            scheduleAutoScroll()

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleAutoScroll()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleAutoScroll()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            scheduleAutoScroll()
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(dataObserver)
        super.setAdapter(adapter)
        adapter?.registerAdapterDataObserver(dataObserver)
    }

    /**
     * 外部如果明确知道当前集下标，可以直接调这个（可选，不调也能自动判断）。
     */
    fun scrollToCurrentEpisode() = scheduleAutoScroll()

    private fun scheduleAutoScroll() {
        val token = ++scrollToken
        post {
            if (token != scrollToken) return@post
            centerOnCurrent(token)
        }
    }

    private fun centerOnCurrent(token: Int) {
        val adapter = adapter ?: return
        if (adapter.itemCount <= 0) return
        val layoutManager = layoutManager as? LinearLayoutManager ?: return

        val index = findCurrentIndex(adapter)
        if (index < 0 || index >= adapter.itemCount) return

        val horizontal = layoutManager.orientation == LinearLayoutManager.HORIZONTAL
        val extent = if (horizontal) width else height
        if (extent <= 0) return

        // 第一帧还没有子 View，拿不到真实 item 高度：先把它拉进视口（放在 1/4 处），
        // 等布局出来后再按实测高度精确居中。
        layoutManager.scrollToPositionWithOffset(index, extent / 4)

        post {
            if (token != scrollToken) return@post
            val itemView = findViewHolderForAdapterPosition(index)?.itemView ?: return@post
            val delta = if (horizontal) {
                (width - itemView.width) / 2 - itemView.left
            } else {
                (height - itemView.height) / 2 - itemView.top
            }
            if (delta != 0) scrollBy(if (horizontal) delta else 0, if (horizontal) 0 else delta)
        }
    }

    private fun findCurrentIndex(adapter: Adapter<*>): Int {
        readCurrentIndexOfAdapter(adapter)?.let { if (it >= 0) return it }
        return findHighlightedIndex()
    }

    /**
     * 反射读 adapter 的 `currentIndex`。adapter 在播放器包里，这边不能改它的签名；
     * 项目 release 未开混淆（isMinifyEnabled = false），字段名稳定，读失败就返回 null。
     */
    private fun readCurrentIndexOfAdapter(adapter: Adapter<*>): Int? {
        var type: Class<*>? = adapter.javaClass
        while (type != null && type != RecyclerView.Adapter::class.java) {
            val field = try {
                type.getDeclaredField("currentIndex")
            } catch (_: NoSuchFieldException) {
                null
            } catch (_: Throwable) {
                return null
            }
            if (field != null) {
                return try {
                    field.isAccessible = true
                    field.get(adapter) as? Int
                } catch (_: Throwable) {
                    null
                }
            }
            type = type.superclass
        }
        return null
    }

    /** 兜底：当前集的卡片底色是主题色，扫已挂载的 item 找它 */
    private fun findHighlightedIndex(): Int {
        if (childCount == 0) return -1
        val primary = ContextCompat.getColor(context, R.color.primary)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val card = child as? MaterialCardView ?: continue
            if (card.cardBackgroundColor.defaultColor == primary) {
                val position = getChildAdapterPosition(child)
                if (position != NO_POSITION) return position
            }
        }
        return -1
    }
}