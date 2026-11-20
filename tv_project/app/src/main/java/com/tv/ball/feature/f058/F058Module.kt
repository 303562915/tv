package com.tv.ball.feature.f058

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「流量统计」在功能中心里的登记项。 */
object F058Module : FeatureModule {
    override val id: String = "F058"
    override val title: String = "流量统计"
    override val description: String = "这一场看了多少、今天一共花了多少、近七天是不是超量，按日期分桶存下来一目了然"
    override val group: String = "网络"

    override fun open(context: Context) {
        val intent = Intent(context, F058Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}