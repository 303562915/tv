package com.tv.ball.feature.f017

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** F017 字幕时间轴平移 · 功能中心入口。 */
object F017Module : FeatureModule {

    override val id: String = "F017"

    override val title: String = "字幕时间轴平移"

    override val description: String = "整体前后平移字幕时间轴，支持 0.1 秒与 1 秒步进。"

    override val group: String = "字幕"

    override fun open(context: Context) {
        val intent = Intent(context, F017Activity::class.java)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
