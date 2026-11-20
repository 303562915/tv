package com.tv.ball.feature.f020

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「文件夹多维排序」在功能中心里的登记项。 */
object F020Module : FeatureModule {
    override val id: String = "F020"
    override val title: String = "文件夹多维排序"
    override val description: String = "按名称、视频数、总时长、最近修改排文件夹，可升可降序，选择会记住"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F020Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}