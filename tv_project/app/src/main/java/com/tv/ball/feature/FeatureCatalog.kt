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
        com.tv.ball.feature.f006.F006Module,
        com.tv.ball.feature.f003.F003Module,
        com.tv.ball.feature.f009.F009Module,
        com.tv.ball.feature.f010.F010Module,
        com.tv.ball.feature.f011.F011Module,
        com.tv.ball.feature.f012.F012Module,
        com.tv.ball.feature.f013.F013Module,
        com.tv.ball.feature.f016.F016Module,
        com.tv.ball.feature.f017.F017Module,
        com.tv.ball.feature.f019.F019Module,
        com.tv.ball.feature.f014.F014Module,
        com.tv.ball.feature.f015.F015Module,
        com.tv.ball.feature.f018.F018Module,
        com.tv.ball.feature.f020.F020Module,
        com.tv.ball.feature.f021.F021Module,
        com.tv.ball.feature.f022.F022Module,
        com.tv.ball.feature.f023.F023Module,
        com.tv.ball.feature.f024.F024Module,
        com.tv.ball.feature.f025.F025Module,
        com.tv.ball.feature.f027.F027Module,
        com.tv.ball.feature.f028.F028Module,
        com.tv.ball.feature.f026.F026Module,
        com.tv.ball.feature.f029.F029Module,
        com.tv.ball.feature.f030.F030Module,
        com.tv.ball.feature.f032.F032Module,
        com.tv.ball.feature.f033.F033Module,
        com.tv.ball.feature.f034.F034Module,
        com.tv.ball.feature.f036.F036Module,
        com.tv.ball.feature.f035.F035Module,
        com.tv.ball.feature.f038.F038Module,
        com.tv.ball.feature.f043.F043Module,
        com.tv.ball.feature.f040.F040Module,
        com.tv.ball.feature.f042.F042Module,
        com.tv.ball.feature.f041.F041Module,
        com.tv.ball.feature.f047.F047Module,
        com.tv.ball.feature.f039.F039Module,
        com.tv.ball.feature.f044.F044Module,
        com.tv.ball.feature.f046.F046Module,
        com.tv.ball.feature.f048.F048Module,
        com.tv.ball.feature.f052.F052Module,
        com.tv.ball.feature.f053.F053Module,
        com.tv.ball.feature.f055.F055Module,
        com.tv.ball.feature.f060.F060Module,
        com.tv.ball.feature.f056.F056Module,
        com.tv.ball.feature.f058.F058Module,
        com.tv.ball.feature.f059.F059Module,
        com.tv.ball.feature.f061.F061Module,
        com.tv.ball.feature.f057.F057Module,
        com.tv.ball.feature.f062.F062Module,
        com.tv.ball.feature.f063.F063Module,
        com.tv.ball.feature.f064.F064Module,
        com.tv.ball.feature.f066.F066Module,
        com.tv.ball.feature.f070.F070Module,
        com.tv.ball.feature.f071.F071Module,
        com.tv.ball.feature.f073.F073Module,
        com.tv.ball.feature.f075.F075Module,
        com.tv.ball.feature.f072.F072Module,
        com.tv.ball.feature.f076.F076Module,
        com.tv.ball.feature.f080.F080Module,
        com.tv.ball.feature.f081.F081Module,
        com.tv.ball.feature.f083.F083Module,
        com.tv.ball.feature.f084.F084Module,
        com.tv.ball.feature.f085.F085Module,
        com.tv.ball.feature.f086.F086Module,
        com.tv.ball.feature.f087.F087Module,
        // WIRE:CATALOG
    )

    /** 按分组聚合，分组内的顺序由 id 决定。 */
    fun grouped(): List<Pair<String, List<FeatureModule>>> =
        entries.groupBy { it.group }
            .toList()
            .sortedBy { it.first }
            .map { (g, list) -> g to list.sortedBy { it.id } }
}