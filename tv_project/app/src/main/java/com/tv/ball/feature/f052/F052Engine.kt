package com.tv.ball.feature.f052

/**
 * F052 的搜索范围过滤逻辑（纯 Kotlin，不碰 Android API）。
 *
 * 一次过滤由三个条件叠加而成，关系是 AND：
 * 1. 关键字：标题包含即命中，忽略大小写，空串表示不参与；
 * 2. 类型：多选，空集合表示不限类型，否则命中任一选中类型即可（OR 在集合内部）；
 * 3. 时长区间：闭区间 [min, max]，[NO_LIMIT] 表示该端不限。
 *
 * 文案全部由 [ScopeText] 传进来，引擎不认识 R 与 Context；默认值只在纯逻辑演示时用。
 */
object F052Engine {

    /** 一分钟的毫秒数，示例数据与时长档位都用它换算。 */
    const val MINUTE_MS: Long = 60_000L

    /** 时长区间的「不限」哨兵：上限用它表示没有上界。 */
    const val NO_LIMIT: Long = -1L

    /** 内容类型，顺序就是界面按钮的先后。 */
    enum class Type {
        MOVIE,
        SERIES,
        SHORT,
        LOCAL
    }

    /** 一条可搜索的内容。 */
    data class Doc(
        val id: String,
        val title: String,
        val type: Type,
        val durationMs: Long
    ) {
        /** 展示用分钟数，向下取整。 */
        val durationMinutes: Long
            get() = (durationMs / MINUTE_MS).coerceAtLeast(0L)
    }

    /** 时长四档，界面上的四个按钮逐条对应。 */
    enum class DurationBand(val minMs: Long, val maxMs: Long) {
        /** 不限时长。 */
        ANY(0L, NO_LIMIT),

        /** 10 分钟以内，含 10 分钟整。 */
        WITHIN_10(0L, 10 * MINUTE_MS),

        /** 10 到 45 分钟，两端都含。 */
        FROM_10_TO_45(10 * MINUTE_MS, 45 * MINUTE_MS),

        /** 45 分钟以上。 */
        OVER_45(45 * MINUTE_MS, NO_LIMIT);

        /** 这一档对应的过滤条件，关键字与类型留空由调用方补。 */
        fun toFilter(): ScopeFilter = ScopeFilter(minDurationMs = minMs, maxDurationMs = maxMs)
    }

    /**
     * 过滤条件。
     *
     * [types] 是可选集合，空 = 不限；[minDurationMs] 默认 0（无下界），
     * [maxDurationMs] 默认 [NO_LIMIT]（无上界）。
     */
    data class ScopeFilter(
        val keyword: String = "",
        val types: Set<Type> = emptySet(),
        val minDurationMs: Long = 0L,
        val maxDurationMs: Long = NO_LIMIT
    ) {

        /** 是否有关键字参与。 */
        val hasKeyword: Boolean
            get() = keyword.isNotBlank()

        /** 是否选了类型。 */
        val hasTypes: Boolean
            get() = types.isNotEmpty()

        /** 是否限定了时长区间。 */
        val hasDuration: Boolean
            get() = minDurationMs > 0L || maxDurationMs != NO_LIMIT

        /** 三个条件都没设，等价于「全部内容」。 */
        val isIdle: Boolean
            get() = !hasKeyword && !hasTypes && !hasDuration

        /** 换关键字。 */
        fun withKeyword(text: String): ScopeFilter = copy(keyword = text)

        /** 类型多选：选中则取消，未选中则加上。 */
        fun toggle(type: Type): ScopeFilter =
            copy(types = if (type in types) types - type else types + type)

        /** 切时长档位。 */
        fun withBand(band: DurationBand): ScopeFilter =
            copy(minDurationMs = band.minMs, maxDurationMs = band.maxMs)
    }

    /**
     * 生效条件的文案素材，由界面从 string 资源填好传进来。
     * 各格式化模板里的占位符顺序：类型 / 时长 / 关键字三个整串，以及时长区间的分钟数。
     */
    data class ScopeText(
        /** 类型 code -> 显示名。 */
        val typeNames: Map<Type, String>,
        /** 没选类型时的文案。 */
        val anyType: String,
        /** 时长不限时的文案。 */
        val anyDuration: String,
        /** 有关键字时的模板，`%1\$s` 是关键字。 */
        val keywordFormat: String,
        /** 没关键字时的文案。 */
        val noKeyword: String,
        /** 只有上界时的模板，`%1\$d` 是分钟数。 */
        val withinFormat: String,
        /** 双端区间的模板，`%1\$d` / `%2\$d` 是分钟数。 */
        val rangeFormat: String,
        /** 只有下界时的模板，`%1\$d` 是分钟数。 */
        val aboveFormat: String,
        /** 三段拼接模板，`%1\$s` 类型 / `%2\$s` 时长 / `%3\$s` 关键字。 */
        val template: String
    ) {
        companion object {
            /** 纯逻辑演示用的中文默认值；界面会用资源覆盖。 */
            val DEFAULT: ScopeText = ScopeText(
                typeNames = mapOf(
                    Type.MOVIE to "电影",
                    Type.SERIES to "剧集",
                    Type.SHORT to "短片",
                    Type.LOCAL to "本地"
                ),
                anyType = "全部类型",
                anyDuration = "不限时长",
                keywordFormat = "「%1\$s」",
                noKeyword = "无关键字",
                withinFormat = "%1\$d 分钟以内",
                rangeFormat = "%1\$d–%2\$d 分钟",
                aboveFormat = "%1\$d 分钟以上",
                template = "类型 %1\$s · 时长 %2\$s · 关键字 %3\$s"
            )
        }
    }

    /**
     * 过滤主入口：三个条件 AND 叠加，顺序固定为 关键字 -> 类型 -> 时长，
     * 结果保持入参原有顺序。
     */
    fun apply(docs: List<Doc>, filter: ScopeFilter): List<Doc> = docs.filter { matches(it, filter) }

    /** 单条判定，短路顺序与 [apply] 一致。 */
    fun matches(doc: Doc, filter: ScopeFilter): Boolean {
        val keyword = filter.keyword.trim()
        if (keyword.isNotEmpty() && !doc.title.contains(keyword, ignoreCase = true)) return false
        if (filter.types.isNotEmpty() && doc.type !in filter.types) return false
        if (doc.durationMs < filter.minDurationMs) return false
        if (filter.maxDurationMs != NO_LIMIT && doc.durationMs > filter.maxDurationMs) return false
        return true
    }

    /** 按类型统计全量条数，给类型按钮标数用。 */
    fun countByType(docs: List<Doc>): Map<Type, Int> =
        Type.values().associateWith { type -> docs.count { it.type == type } }

    /** 生效条件描述：类型 / 时长 / 关键字三段拼起来，界面直接显示。 */
    fun describe(filter: ScopeFilter, text: ScopeText = ScopeText.DEFAULT): String {
        val typePart = if (filter.types.isEmpty()) {
            text.anyType
        } else {
            Type.values()
                .filter { it in filter.types }
                .joinToString(separator = "、") { text.typeNames[it] ?: it.name }
        }
        val durationPart = describeDuration(filter.minDurationMs, filter.maxDurationMs, text)
        val keywordPart = if (filter.keyword.isNotBlank()) {
            String.format(text.keywordFormat, filter.keyword.trim())
        } else {
            text.noKeyword
        }
        return String.format(text.template, typePart, durationPart, keywordPart)
    }

    /** 时长档位的描述，按钮与条件行共用同一份文案，避免两处写法不一致。 */
    fun describeDuration(band: DurationBand, text: ScopeText = ScopeText.DEFAULT): String =
        describeDuration(band.minMs, band.maxMs, text)

    /** 时长区间描述：无上界走「以上」、无下界走「以内」，两端都有走区间。 */
    fun describeDuration(
        minMs: Long,
        maxMs: Long,
        text: ScopeText = ScopeText.DEFAULT
    ): String {
        val minMinute = (minMs / MINUTE_MS).coerceAtLeast(0L)
        val hasMin = minMs > 0L
        val hasMax = maxMs != NO_LIMIT
        val maxMinute = if (hasMax) (maxMs / MINUTE_MS).coerceAtLeast(0L) else 0L
        return when {
            !hasMin && !hasMax -> text.anyDuration
            !hasMin -> String.format(text.withinFormat, maxMinute)
            !hasMax -> String.format(text.aboveFormat, minMinute)
            else -> String.format(text.rangeFormat, minMinute, maxMinute)
        }
    }
}