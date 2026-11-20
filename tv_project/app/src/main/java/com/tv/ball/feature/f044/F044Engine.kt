package com.tv.ball.feature.f044

/**
 * F044「搜索建议与联想」的能力层。
 *
 * 纯 Kotlin，不引用任何 Android API：[suggest] 只吃「输入串 + 历史列表 + 标题库」三种数据，
 * 吐出一串已经排好序、去过重的 [Suggestion]，界面只负责把候选画出来。
 * 这样「前缀优先 / 历史优先 / 去重 / 空输入给最近历史 / 上限截断」这几条规则都能被单测直接断言。
 *
 * 两条输入列表的约定：**越靠前的条目越新**（历史按下标从新到旧），
 * 因为空输入时返回的就是「最近历史」，同排名时也保留列表原来的先后。
 */
object F044Engine {

    /** 一条候选是从哪儿来的。 */
    enum class Kind {
        /** 来自搜索历史。 */
        HISTORY,

        /** 来自本地库标题。 */
        TITLE
    }

    /**
     * 一条候选。
     *
     * [text] 就是能直接填进搜索框的完整串（一定是历史或标题原文，做过 trim），
     * [kind] 决定界面上的角标是「历史」还是「标题」，也决定同排名时的先后。
     */
    data class Suggestion(
        val text: String,
        val kind: Kind
    )

    /** 默认最多给几条候选：一屏能看完，又不至于把输入框顶出视野。 */
    const val DEFAULT_LIMIT: Int = 8

    /** 历史来源的排位权重，比标题靠前。 */
    private const val SOURCE_HISTORY = 0

    /** 标题来源的排位权重。 */
    private const val SOURCE_TITLE = 1

    /** 前缀命中的排位权重，比中缀命中靠前。 */
    private const val RANK_PREFIX = 0

    /** 中缀命中的排位权重。 */
    private const val RANK_INFIX = 1

    /**
     * 出候选。
     *
     * 规则按优先级从上到下：
     * 1. 前缀命中整体排在中缀命中之前（输入「三」时「三体 第二季」排在「流浪地球 3」之前）；
     * 2. 同一档位里历史排在标题之前（历史是用户真的搜过的词，比片名更贴近意图）；
     * 3. 同来源同档位保留原列表顺序（历史列表本身就是从新到旧）；
     * 4. 文案相同的候选只留一次，留下的是排序更靠前的那份，所以历史里的词角标仍是「历史」；
     * 5. 空输入（含只输入空格）不算联想，直接给最近历史的前 [limit] 条；
     * 6. 结果最多 [limit] 条，`limit <= 0` 返回空列表。
     *
     * 匹配一律大小写不敏感（`lowercase()`），中文不受影响，英文片名不会被大小写挡掉。
     *
     * @param query 搜索框里的原始输入，内部会 trim。
     * @param history 搜索历史，最近的在前。
     * @param titles 本地库标题，顺序即展示顺序。
     * @param limit 最多返回几条。
     */
    fun suggest(
        query: String,
        history: List<String>,
        titles: List<String>,
        limit: Int = DEFAULT_LIMIT
    ): List<Suggestion> {
        val max = limit.coerceAtLeast(0)
        if (max == 0) return emptyList()

        val key = query.trim()
        if (key.isEmpty()) return recentHistory(history, max)

        val needle = key.lowercase()
        val ranked = ArrayList<Candidate>(history.size + titles.size)
        collect(ranked, history, Kind.HISTORY, SOURCE_HISTORY, needle)
        collect(ranked, titles, Kind.TITLE, SOURCE_TITLE, needle)

        val seen = HashSet<String>(ranked.size * 2)
        val result = ArrayList<Suggestion>(max)
        for (candidate in ranked.sortedWith(BY_RELEVANCE)) {
            // 去重放在排序之后：同一条文案在历史和标题里都命中时，留下排在前面的那份
            if (!seen.add(candidate.text.lowercase())) continue
            result.add(Suggestion(candidate.text, candidate.kind))
            if (result.size >= max) break
        }
        return result
    }

    /** 空输入时的返回：历史去空、去重后取前 [max] 条。 */
    private fun recentHistory(history: List<String>, max: Int): List<Suggestion> {
        val seen = HashSet<String>(history.size * 2)
        val result = ArrayList<Suggestion>(max)
        for (raw in history) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (!seen.add(text.lowercase())) continue
            result.add(Suggestion(text, Kind.HISTORY))
            if (result.size >= max) break
        }
        return result
    }

    /** 把一批来源里命中的文案收进 [into]，同时记下排位信息。 */
    private fun collect(
        into: MutableList<Candidate>,
        source: List<String>,
        kind: Kind,
        sourceRank: Int,
        needle: String
    ) {
        source.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed
            val lower = text.lowercase()
            if (!lower.contains(needle)) return@forEachIndexed
            val prefixRank = if (lower.startsWith(needle)) RANK_PREFIX else RANK_INFIX
            into.add(Candidate(text, kind, prefixRank, sourceRank, index))
        }
    }

    /**
     * 排序用的中间态。
     *
     * [order] 是它在原列表里的下标，只用来在三条排名都相同时保持稳定顺序。
     */
    private data class Candidate(
        val text: String,
        val kind: Kind,
        val prefixRank: Int,
        val sourceRank: Int,
        val order: Int
    )

    /** 前缀 → 历史 → 原顺序。 */
    private val BY_RELEVANCE: Comparator<Candidate> = compareBy(
        { it.prefixRank },
        { it.sourceRank },
        { it.order }
    )

    /** 界面上给候选加角标时用的一句话。 */
    fun kindKey(kind: Kind): String = when (kind) {
        Kind.HISTORY -> "history"
        Kind.TITLE -> "title"
    }
}
