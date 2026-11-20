package com.tv.ball.feature.f033

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「队列编辑」在功能中心里的登记项。 */
object F033Module : FeatureModule {
    override val id: String = "F033"
    override val title: String = "队列编辑"
    override val description: String = "用遥控器上移/下移搬动播放队列，删掉不要的那条，正在播放的那一项始终跟着原来那条走"
    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F033Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}