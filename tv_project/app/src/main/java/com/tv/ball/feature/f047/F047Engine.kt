package com.tv.ball.feature.f047

/**
 * F047 的检索与分组逻辑（纯 Kotlin，不碰 Android API）。
 *
 * 职责只有三件：
 * 1. [group]：把扁平命中列表按来源切成 [Section]，组内按标题排序，空组不出现；
 * 2. [summary]：给出「总条数 + 各组条数」的汇总文案；
 * 3. [match] / [search]：给界面用的极简关键词过滤，保证 Activity 只负责画图。
 */
object F047Engine {

    /** 命中条目。id 唯一，[title] 用来排序，[detail] 是副标题。 */
    data class Hit(
        val id: String,
        val source: String,
        val title: String,
        val detail: String = ""
    )

    /**
     * 一个分组。组标题只带 [label]（来源显示名）和条数 [hits].size，
     * 不在引擎里拼文案，方便 Activity 用 string 资源占位。
     */
    data class Section(
        val source: String,
        val label: String,
        val hits: List<Hit>
    ) {
        val count: Int get() = hits.size
    }

    /** 组内排序：标题升序，标题相同再按 id 升序，保证输出稳定。 */
    private val hitOrder: Comparator<Hit> = compareBy({ it.title }, { it.id })

    /**
     * 按来源分组。
     *
     * - 分组顺序由 [sources] 的声明顺序决定（调用方传什么顺序就按什么顺序展示），
     *   没在 [sources] 里出现的来源按名称追加在后面；
     * - 组内用 [hitOrder] 排序；
     * - 空组不出现在返回值里。
     */
    fun group(
        matches: List<Hit>,
        sources: List<String> = matches.map { it.source }.distinct(),
        labels: Map<String, String> = emptyMap()
    ): List<Section> {
        if (matches.isEmpty()) return emptyList()

        val declared = LinkedHashSet<String>()
        declared.addAll(sources)
        matches.forEach { declared.add(it.source) }

        return declared.mapNotNull { source ->
            val hits = matches.filter { it.source == source }.sortedWith(hitOrder)
            if (hits.isEmpty()) {
                null
            } else {
                Section(source = source, label = labels[source] ?: source, hits = hits)
            }
        }
    }

    /** 总条数。 */
    fun totalCount(sections: List<Section>): Int = sections.sumOf { it.count }

    /**
     * 汇总文案：`%1$d` 总条数，`%2$s` 各组条数（形如「观看历史 3 · 收藏 2」）。
     *
     * [summaryFormat] 与 [sectionFormat] 都是格式化模板，由调用方从 string 资源传入，
     * 这样引擎不需要认识 R 与 Context。
     */
    fun summary(
        sections: List<Section>,
        summaryFormat: String,
        sectionFormat: String
    ): String {
        val total = totalCount(sections)
        val perSection = sections.joinToString(separator = " · ") { section ->
            String.format(sectionFormat, section.label, section.count)
        }
        return String.format(summaryFormat, total, perSection)
    }

    /** 各组条数，方便界面单独取用。 */
    fun counts(sections: List<Section>): Map<String, Int> =
        sections.associate { it.source to it.count }

    /** 标题 / 副标题都参与匹配；空关键词返回全部。 */
    fun match(hits: List<Hit>, query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return hits
        return hits.filter {
            it.title.contains(q, ignoreCase = true) || it.detail.contains(q, ignoreCase = true)
        }
    }

    /** 搜索 + 分组一步到位。 */
    fun search(
        hits: List<Hit>,
        query: String,
        sources: List<String> = hits.map { it.source }.distinct(),
        labels: Map<String, String> = emptyMap()
    ): List<Section> = group(match(hits, query), sources, labels)
}
