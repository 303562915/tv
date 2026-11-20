package com.tv.ball.feature.f043

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「全局搜索」在功能中心里的登记项。 */
object F043Module : FeatureModule {
    override val id: String = "F043"
    override val title: String = "全局搜索"
    override val description: String = "一个搜索框同时搜观看历史、收藏、本地视频和播放列表，结果按来源分组并按标题排序"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F043Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
