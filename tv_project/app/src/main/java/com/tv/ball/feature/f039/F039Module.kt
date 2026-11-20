package com.tv.ball.feature.f039

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「播放列表回收站」在功能中心里的登记项。 */
object F039Module : FeatureModule {
    override val id: String = "F039"
    override val title: String = "播放列表回收站"
    override val description: String = "删掉的列表先进回收站，7 天内可恢复，过期自动清理"
    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F039Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
