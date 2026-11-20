package com.tv.ball.feature.f041

import com.google.gson.Gson

/**
 * 浏览模式：网格按 [F041Engine.GRID_COLUMNS] 列铺开，列表一行一条。
 *
 * 放在文件顶层而不是塞进 Engine 里，是为了让「一页就是一种模式」这件事在类型上说得清：
 * [name] 同时被当作这一页的页标识，滚动位置就挂在它下面。
 */
enum class ViewMode {
    GRID,
    LIST;

    /** 切到另一种模式。只有两种模式，所以不需要额外的中间态。 */
    fun toggle(): ViewMode = if (this == GRID) LIST else GRID
}

/**
 * F041「视图模式切换」的能力层。
 *
 * 三件事：当前模式在网格 / 列表之间切换、每种模式铺几列、
 * 以及**每页各自一份**滚动位置。
 *
 * 刻意写成纯 Kotlin：不引用任何 Android API，持久化只交出一段 JSON 字符串，
 * 真正落盘到 SharedPreferences 的活由 [F041Activity] 做，
 * 这样「切模式不丢位置」这条规则可以脱离页面单测。
 */
class F041Engine(private val gson: Gson = Gson()) {

    /** 当前模式。换模式不会清掉上一页记住的位置，两页各留一份。 */
    var mode: ViewMode = ViewMode.GRID
        private set

    /**
     * 页标识 -> 该页第一个可见条目的下标。
     *
     * 用 [LinkedHashMap] 而不是 HashMap：导出 JSON 时顺序稳定，
     * 同一次操作重复落盘得到同一段字符串，排查时好对比。
     */
    private val scrollByPage = LinkedHashMap<String, Int>()

    /** 切到另一种模式，返回切换后的模式。 */
    fun toggle(): ViewMode {
        mode = mode.toggle()
        return mode
    }

    /** 直接把模式设成 [value]：重进页面时按上次保存的模式摆好，不必再 toggle 一次。 */
    fun setMode(value: ViewMode) {
        mode = value
    }

    /** [mode] 这种模式铺几列：网格 4 列、列表 1 列。 */
    fun columns(mode: ViewMode = this.mode): Int = columnsOf(mode)

    /**
     * 记下某页的滚动位置。
     *
     * 负数当下标 0 处理（列表还没布局时 `findFirstVisibleItemPosition` 会返回 -1），
     * 免得脏下标把页面滚到列表外面去。
     */
    fun saveScroll(pageKey: String, index: Int) {
        val key = pageKey.trim()
        if (key.isEmpty()) return
        scrollByPage[key] = index.coerceAtLeast(0)
    }

    /** 某页记住的位置；没记过就是 0，也就是从头开始。 */
    fun restoreScroll(pageKey: String): Int = scrollByPage[pageKey.trim()] ?: 0

    /** 某页是否已有位置记录，界面靠它区分「记住第 3 条」和「还没记过」。 */
    fun hasScroll(pageKey: String): Boolean = scrollByPage.containsKey(pageKey.trim())

    /** 清空全部页的位置记录，当前模式保持不变。 */
    fun clearScroll() {
        scrollByPage.clear()
    }

    /** 序列化成一段 JSON，交给调用方写进 SharedPreferences。 */
    fun exportJson(): String = gson.toJson(F041StateDto(mode.name, LinkedHashMap(scrollByPage)))

    /**
     * 从 JSON 恢复，先清空当前内存状态。
     *
     * 空串、坏 JSON、缺字段一律按「没存过」处理：退回网格视图 + 从头开始。
     * 位置记忆只是浏览便利，读失败时宁可从头浏览，也不该让页面抛异常打不开。
     */
    fun importJson(json: String?) {
        scrollByPage.clear()
        mode = ViewMode.GRID
        if (json.isNullOrBlank()) return

        val restored = runCatching { gson.fromJson(json, F041StateDto::class.java) }.getOrNull() ?: return
        mode = ViewMode.values().firstOrNull { it.name == restored.mode } ?: ViewMode.GRID
        restored.scroll?.forEach { (pageKey, index) ->
            val key = pageKey.trim()
            if (key.isNotEmpty() && index != null) scrollByPage[key] = index.coerceAtLeast(0)
        }
    }

    companion object {
        /** 网格模式的列数。 */
        const val GRID_COLUMNS: Int = 4

        /** 列表模式的列数（一行一条）。 */
        const val LIST_COLUMNS: Int = 1

        /** [mode] 对应的列数，Activity 靠它决定挂 GridLayoutManager 还是 LinearLayoutManager。 */
        fun columnsOf(mode: ViewMode): Int = when (mode) {
            ViewMode.GRID -> GRID_COLUMNS
            ViewMode.LIST -> LIST_COLUMNS
        }

        /** 页标识：一种模式就是一页，直接用枚举名当 key，落盘也读得懂。 */
        fun pageKeyOf(mode: ViewMode): String = mode.name
    }
}

/**
 * 落盘用的扁平结构。
 *
 * 字段全可空：旧版本或少字段的 JSON 不会把页面弄崩，
 * 判脏统一交给 [F041Engine.importJson]。
 */
private data class F041StateDto(
    val mode: String? = null,
    val scroll: Map<String, Int?>? = null
)
