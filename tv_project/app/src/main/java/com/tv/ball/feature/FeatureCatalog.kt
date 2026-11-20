package com.tv.ball.feature

/**
 * 功能注册表。
 *
 * 每落地一个功能，就在 [entries] 里追加一行全限定名，
 * 由主智能体统一接线，功能实现方不直接改这个文件。
 */
object FeatureCatalog {

    val entries: List<FeatureModule> = listOf(
        com.tv.ball.feature.f001.F001Module,
        com.tv.ball.feature.f002.F002Module,
        com.tv.ball.feature.f007.F007Module,
        com.tv.ball.feature.f008.F008Module,
        com.tv.ball.feature.f004.F004Module,
        com.tv.ball.feature.f005.F005Module,
        // WIRE:CATALOG
    )

    /** 按分组聚合，分组内的顺序由 id 决定。 */
    fun grouped(): List<Pair<String, List<FeatureModule>>> =
        entries.groupBy { it.group }
            .toList()
            .sortedBy { it.first }
            .map { (g, list) -> g to list.sortedBy { it.id } }
}