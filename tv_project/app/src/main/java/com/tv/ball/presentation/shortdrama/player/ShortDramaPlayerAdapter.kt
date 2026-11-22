package com.tv.ball.presentation.shortdrama.player

import android.util.SparseArray
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.databinding.ItemShortDramaPlayerPageBinding

/**
 * 竖屏连播的分页适配器。
 *
 * 为什么自己维护 position -> holder 的索引
 * ----------------------------------------
 * ViewPager2 只对外暴露「当前第几页」，但播放器要求「切页时把其它页的实例都暂停」。
 * RecyclerView 的 findViewHolderForAdapterPosition 只在已经布局完成的页上有效，
 * 而一页刚好被回收的瞬间会返回 null，导致那一个实例既没被暂停也没被回收（声音继续飘）。
 * 所以在 bind / recycle 时自己记账，切页时直接遍历真实存在的 holder。
 *
 * 换线路时不复用本对象：分集列表整个换了，换 adapter 比 notifyDataSetChanged 更不容易状态错位
 * （ViewPager2 对 item 数变化本身就敏感）。
 */
class ShortDramaPlayerAdapter(
    private val host: ShortDramaPlayerHost,
    private val engine: ShortDramaPlaybackEngine,
    private val items: List<ShortDramaQueueItem>
) : RecyclerView.Adapter<ShortDramaPlayerAdapter.PageViewHolder>() {

    private val holders = SparseArray<ShortDramaPlayerPageHolder>()

    class PageViewHolder(val holder: ShortDramaPlayerPageHolder) : RecyclerView.ViewHolder(holder.itemView)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemShortDramaPlayerPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(ShortDramaPlayerPageHolder(binding, host, engine))
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holders.put(position, holder.holder)
        holder.holder.bind(items[position], position)
    }

    /**
     * 页面重新挂回窗口时补一次绑定。
     *
     * 为什么不能只靠 onBindViewHolder：RecyclerView 有视图缓存（默认 2 个 ViewHolder），
     * 进缓存的页不会走 onViewRecycled，回到屏幕时也不会走 onBindViewHolder。
     * 而播放器实例在 detach 那一刻就归还了，所以这里必须把它重新装回来，否则这一页是空的。
     */
    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        holders.put(position, holder.holder)
        if (holder.holder.pagePosition != position) holder.holder.bind(items[position], position)
    }

    /**
     * 页面滑出窗口就归还实例。
     *
     * 为什么不放在 onViewRecycled：进视图缓存的页不会收到 onViewRecycled，
     * 于是「已经滑走、却还占着借出名额」的实例越积越多，池子被填满后新页拿不到实例，
     * 池子满载后还去抢占别的实例就会让两个页面共用同一个 ExoPlayer（黑屏 / 串音）。
     * detach 是这里唯一一定会发生的时机，归还纪律挂在它上面；onViewRecycled 留一次幂等兜底。
     */
    override fun onViewDetachedFromWindow(holder: PageViewHolder) {
        super.onViewDetachedFromWindow(holder)
        removeFromIndex(holder.holder)
        holder.holder.unbind()
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        removeFromIndex(holder.holder)
        // 进视图缓存不会到这里，但真正被复用的页会；unbind 可重复调用，所以不影响上面的 detach 归还
        holder.holder.unbind()
    }

    fun holderAt(position: Int): ShortDramaPlayerPageHolder? = holders.get(position)

    fun forEachHolder(action: (ShortDramaPlayerPageHolder) -> Unit) {
        for (i in 0 until holders.size()) action(holders.valueAt(i))
    }

    /** Activity 销毁时统一解绑。必须在 engine.shutdown() 之前调用，顺序反了会还原到已经释放的实例上 */
    fun releaseAll() {
        for (i in holders.size() - 1 downTo 0) holders.valueAt(i).unbind()
        holders.clear()
    }

    private fun removeFromIndex(target: ShortDramaPlayerPageHolder) {
        for (i in holders.size() - 1 downTo 0) {
            if (holders.valueAt(i) === target) holders.removeAt(i)
        }
    }
}
