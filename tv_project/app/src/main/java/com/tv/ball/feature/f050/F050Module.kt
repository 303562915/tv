package com.tv.ball.feature.f050

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「模糊匹配容错」在功能中心里的登记项。 */
object F050Module : FeatureModule {
    override val id: String = "F050"
    override val title: String = "模糊匹配容错"
    override val description: String = "错字、简繁、空格差异不该让结果消失：归一化后按容错级别找近似结果，并说清为什么命中"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F050Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}