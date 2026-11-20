package com.tv.ball.feature.f049

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「热词与最近查询排序」在功能中心里的登记项。 */
object F049Module : FeatureModule {
    override val id: String = "F049"
    override val title: String = "热词与最近查询排序"
    override val description: String = "首页热词按点击次数排、最近查询按时间倒序去重，点一下就直接搜"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F049Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
