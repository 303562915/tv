package com.tv.ball.feature.f033

/**
 * 队列里的一条。
 *
 * [id] 一旦生成就不再变：排序、删除、插入都只改位置，不改 id。
 * 在播项靠 id 认人，所以把某条从队尾拖到队首之后，界面高亮的还是原来那条。
 */
data class QueueItem(
    val id: String,
    val title: String
)

/**
 * F033「队列编辑」的队列状态。
 *
 * 刻意写成纯 Kotlin：不引用任何 Android API、不读系统时钟、不碰存储
 * （落盘由页面侧换成 JSON 写 SharedPreferences，换存储方案不用改这里）。
 *
 * [currentIndex] 始终跟着「同一项」走：上移/下移/插入都可能把它挤到别的下标，
 * 但指向的那一条不变；只有当前项自己被删掉时，才顺延到顶替上来的那条。
 */
class F033Queue(
    initial: List<QueueItem> = emptyList(),
    currentId: String? = null
) {

    private val list: MutableList<QueueItem> = ArrayList(initial)

    /** 在播项下标；[NO_ITEM] 表示队列为空，或者还没定下在播项。 */
    var currentIndex: Int =
        if (currentId == null) NO_ITEM else list.indexOfFirst { it.id == currentId }
        private set

    /** 只读视图，渲染用；改顺序请走 [move] / [removeAt] / [insertAt]。 */
    val items: List<QueueItem> get() = list

    val size: Int get() = list.size

    val isEmpty: Boolean get() = list.isEmpty()

    /** 当前在播的那一项。 */
    val currentItem: QueueItem? get() = list.getOrNull(currentIndex)

    /** 在播项 id：落盘时记下来，重建队列时靠它把指针找回来。 */
    val currentId: String? get() = currentItem?.id

    fun itemAt(index: Int): QueueItem? = list.getOrNull(index)

    /** 该 id 对应的下标；不在队列里返回 [NO_ITEM]。 */
    fun indexOf(id: String): Int =
        if (id.isEmpty()) NO_ITEM else list.indexOfFirst { it.id == id }

    /**
     * 把 [from] 位置那条搬到 [to] 位置，其余条目顺移（等价于「拖到目标位上」）。
     *
     * @return 越界、或者原地不动时返回 false，调用方据此决定要不要提示。
     */
    fun move(from: Int, to: Int): Boolean {
        if (from == to || from !in list.indices || to !in list.indices) return false
        // 先记住「在播的是哪一条」，再动列表：下标在这次搬运后可能已经指向别人了。
        val anchor = currentId
        list.add(to, list.removeAt(from))
        reanchor(anchor)
        return true
    }

    /**
     * 删除 [index] 位置那条，返回被删掉的条目；越界返回 null。
     *
     * 删掉的不是在播项时，在播项照旧；删掉的正好是在播项时，让顶替上来的那条接着播，
     * 队列彻底空了才回到 [NO_ITEM]。
     */
    fun removeAt(index: Int): QueueItem? {
        if (index !in list.indices) return null
        val anchor = currentId
        val removed = list.removeAt(index)
        currentIndex = when {
            anchor == null -> NO_ITEM
            // 当前项被删：让顶替上来的那条接着播（删的是队尾就退到最后一条），队列空了才彻底没有在播项。
            anchor == removed.id -> if (list.isEmpty()) NO_ITEM else index.coerceIn(0, list.lastIndex)
            else -> list.indexOfFirst { it.id == anchor }
        }
        return removed
    }

    /**
     * 在 [index] 处插入一条（插到该下标之前的位置），越界下标回落到队尾。
     *
     * @return 负下标直接拒绝。
     */
    fun insertAt(index: Int, item: QueueItem): Boolean {
        if (index < 0) return false
        val anchor = currentId
        list.add(index.coerceAtMost(list.size), item)
        reanchor(anchor)
        return true
    }

    /** 直接把在播项改成 [index] 那条；越界返回 false。 */
    fun selectAt(index: Int): Boolean {
        if (index !in list.indices) return false
        currentIndex = index
        return true
    }

    /** 在播项往前/往后挪一格（[step] 取 ±1）；已经在端点上返回 false。 */
    fun moveCurrent(step: Int): Boolean = move(currentIndex, currentIndex + step)

    /** 改完顺序后按 id 把指针重新钉回原来那条；anchor 为空则保持「没有在播项」。 */
    private fun reanchor(anchorId: String?) {
        currentIndex = if (anchorId == null) NO_ITEM else list.indexOfFirst { it.id == anchorId }
    }

    companion object {
        /** 「没有在播项」/「找不到」的统一下标。 */
        const val NO_ITEM: Int = -1
    }
}