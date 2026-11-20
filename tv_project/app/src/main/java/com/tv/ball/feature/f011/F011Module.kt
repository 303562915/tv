package com.tv.ball.feature.f011

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「外挂字幕加载」在功能中心里的登记项。 */
object F011Module : FeatureModule {
    override val id: String = "F011"
    override val title: String = "外挂字幕加载"
    override val description: String = "选 srt / ass / vtt 文件解析出时间轴，失败会说清哪一行"
    override val group: String = "字幕"

    override fun open(context: Context) {
        val intent = Intent(context, F011Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
