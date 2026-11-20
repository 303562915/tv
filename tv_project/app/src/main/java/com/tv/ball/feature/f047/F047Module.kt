package com.tv.ball.feature.f047

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「搜索结果分类与空态」在功能中心里的登记项。 */
object F047Module : FeatureModule {
    override val id: String = "F047"
    override val title: String = "搜索结果分类与空态"
    override val description: String = "搜索结果按数据来源分组、每组显示条数，无结果时给出空态图标与可操作建议"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F047Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
