package com.tv.ball.feature.f032

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「播放偏好学习」在功能中心里的登记项。 */
object F032Module : FeatureModule {
    override val id: String = "F032"
    override val title: String = "播放偏好学习"
    override val description: String = "记住常选的线路与清晰度，下次进入自动选中次数最多的那一项"
    override val group: String = "统计"

    override fun open(context: Context) {
        val intent = Intent(context, F032Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}