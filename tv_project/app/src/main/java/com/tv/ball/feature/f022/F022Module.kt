package com.tv.ball.feature.f022

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「本地视频筛选」在功能中心里的登记项。 */
object F022Module : FeatureModule {
    override val id: String = "F022"
    override val title: String = "本地视频筛选"
    override val description: String = "按扩展名、时长与体积区间过滤本地视频，并实时给出当前生效条件"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F022Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
