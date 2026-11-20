package com.tv.ball.feature.f028

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「历史搜索与筛选」在功能中心里的登记项。 */
object F028Module : FeatureModule {
    override val id: String = "F028"
    override val title: String = "历史搜索与筛选"
    override val description: String = "按标题关键字搜历史，再按最近 7/30 天收窄，两个条件可以叠加使用"
    override val group: String = "历史"

    override fun open(context: Context) {
        val intent = Intent(context, F028Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}