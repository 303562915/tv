package com.tv.ball.feature.f038

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「多播放列表切换」在功能中心里的登记项。 */
object F038Module : FeatureModule {
    override val id: String = "F038"
    override val title: String = "多播放列表切换"
    override val description: String = "多个列表并存，一键切换当前列表，可重命名和新建"
    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F038Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
