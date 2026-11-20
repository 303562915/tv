package com.tv.ball.feature.f030

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「继续观看队列」在功能中心里的登记项。 */
object F030Module : FeatureModule {
    override val id: String = "F030"
    override val title: String = "继续观看队列"
    override val description: String = "没看完的集中成一条队列，按最近观看排序，点一下直接回到断点"
    override val group: String = "历史"

    override fun open(context: Context) {
        val intent = Intent(context, F030Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
