package com.tv.ball.feature.f021

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「缩略图磁盘缓存」在功能中心里的登记项。 */
object F021Module : FeatureModule {
    override val id: String = "F021"
    override val title: String = "缩略图磁盘缓存"
    override val description: String = "缩略图落盘按 SHA-1 命名缓存，命中直接复用，超出上限按 LRU 淘汰最旧的"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F021Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}