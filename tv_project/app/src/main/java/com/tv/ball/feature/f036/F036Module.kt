package com.tv.ball.feature.f036

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「随机播放与权重调度」在功能中心里的登记项。 */
object F036Module : FeatureModule {
    override val id: String = "F036"
    override val title: String = "随机播放与权重调度"
    override val description: String = "随机模式下按权重抽曲、同轮不重复、本轮播完才重置，还能直接调每首的权重"
    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F036Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}