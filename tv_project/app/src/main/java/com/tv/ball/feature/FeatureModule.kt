package com.tv.ball.feature

import android.content.Context

/**
 * 功能中心里的一项独立功能。
 *
 * 每个功能自成一套文件（`feature/f###/`），由 [FeatureCatalog] 汇总，
 * 这样多个功能并行开发时不会互相踩同一份源码。
 */
interface FeatureModule {
    /** 稳定 id，例如 "F001"。 */
    val id: String
    /** 功能中心里显示的名字。 */
    val title: String
    /** 一句话说明这个功能解决什么。 */
    val description: String
    /** 分组名，决定它在功能中心里的分区。 */
    val group: String
    /** 打开功能页面。 */
    fun open(context: Context)
}