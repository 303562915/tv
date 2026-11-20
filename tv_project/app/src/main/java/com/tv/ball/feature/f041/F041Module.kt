package com.tv.ball.feature.f041

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「视图模式切换」在功能中心里的登记项。 */
object F041Module : FeatureModule {
    override val id: String = "F041"
    override val title: String = "视图模式切换"
    override val description: String = "网格与列表两种浏览模式即时切换，每种模式各自记住滚动位置，退出重进也回到原处"
    override val group: String = "界面"

    override fun open(context: Context) {
        val intent = Intent(context, F041Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
