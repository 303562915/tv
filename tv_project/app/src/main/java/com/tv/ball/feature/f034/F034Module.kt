package com.tv.ball.feature.f034

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「队列另存为播放列表」在功能中心里的登记项。 */
object F034Module : FeatureModule {
    override val id: String = "F034"
    override val title: String = "队列另存为播放列表"
    override val description: String = "临时凑的队列起个名字存成列表，同名时可选覆盖或自动加后缀另存"
    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F034Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
