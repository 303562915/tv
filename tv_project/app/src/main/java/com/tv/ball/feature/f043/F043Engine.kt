package com.tv.ball.feature.f043

/**
 * F043「全局搜索」的能力层。
 *
 * 纯 Kotlin，不引用任何 Android API：输入一批来自四类数据源（历史 / 收藏 / 本地 / 播放列表）的
 * [Doc]，输出按来源分好的 [Group]。界面只负责把分组画出来，检索规则全部在这里，
 * 这样「大小写不敏感 / 分组 / 组内按标题排序 / 空查询返回空」都能被单测直接断言。
 */
object F043Engine {

    /** 一条可被搜索的条目来自哪类数据源。 */
    enum class Source(val key: String) {
        /** 观看历史。 */
        HISTORY("history"),

        /** 收藏。 */
        FAVORITE("favorite"),

        /** 本地视频。 */
        LOCAL("local"),

        /** 播放列表。 */
        PLAYLIST("playlist");

        companion object {
            /** 分组展示的固定顺序：历史 → 收藏 → 本地 → 播放列表。 */
            val DISPLAY_ORDER: List<Source> = listOf(HISTORY, FAVORITE, LOCAL, PLAYLIST)
        }
    }

    /**
     * 一条待检索的文档。
     *
     * [title] 与 [subtitle] 都参与匹配；[id] 只在「最近记录」里去重，不影响命中。
     */
    data class Doc(
        val id: String,
        val source: Source,
        val title: String,
        val subtitle: String = ""
    )

    /**
     * 一个来源的命中分组。
     *
     * [hits] 已经按标题排好序，界面照着顺序画行即可。
     */
    data class Group(
        val source: Source,
        val hits: List<Doc>
    ) {
        /** 组标题上的条数。 */
        val count: Int get() = hits.size
    }

    /**
     * 检索入口。
     *
     * 规则：
     * 1. 查询串先 trim；空查询直接返回空列表（不返回「全部」，避免一进页面就刷一屏）；
     * 2. 大小写不敏感（统一 lowercase 后做 contains），标题和副标题任一命中即可；
     * 3. 结果按 [Source.DISPLAY_ORDER] 分组，没有命中的来源整组不出现；
     * 4. 组内按标题排序：忽略大小写，标题相同再按 id 兜底，保证顺序稳定。
     */
    fun search(docs: List<Doc>, query: String): List<Group> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val matched = docs.filter { doc ->
            doc.title.lowercase().contains(needle) || doc.subtitle.lowercase().contains(needle)
        }

        return Source.DISPLAY_ORDER.mapNotNull { source ->
            val hits = matched
                .filter { it.source == source }
                .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
            if (hits.isEmpty()) null else Group(source, hits)
        }
    }

    /**
     * 空查询时的「最近记录」：按 [Source.DISPLAY_ORDER] 取每个来源最前面的 [perSource] 条。
     *
     * 只在界面上做提示用，不参与 [search] 的结果口径。
     */
    fun recent(docs: List<Doc>, perSource: Int = 1): List<Doc> {
        if (perSource <= 0) return emptyList()
        val out = ArrayList<Doc>(perSource * Source.DISPLAY_ORDER.size)
        Source.DISPLAY_ORDER.forEach { source ->
            docs.filter { it.source == source }
                .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
                .take(perSource)
                .forEach { out.add(it) }
        }
        return out
    }

    /** 每个来源各有多少条，用于搜索框下方那行统计。 */
    fun stats(docs: List<Doc>): Map<Source, Int> =
        Source.DISPLAY_ORDER.associateWith { source -> docs.count { it.source == source } }

    /** 所有分组里的命中总数。 */
    fun totalHits(groups: List<Group>): Int = groups.sumOf { it.count }
}
