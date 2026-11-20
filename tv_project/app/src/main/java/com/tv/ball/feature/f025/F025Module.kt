package com.tv.ball.feature.f025

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「媒体信息面板」在功能中心里的登记项。 */
object F025Module : FeatureModule {
    override val id: String = "F025"
    override val title: String = "媒体信息面板"
    override val description: String = "真读所选视频，展示容器、音视频编码、码率、分辨率、帧率与 HDR 信息，取不到的字段显示未知"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F025Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
