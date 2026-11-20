package com.tv.ball.feature.f024

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「SAF 目录授权管理」在功能中心里的登记项。 */
object F024Module : FeatureModule {
    override val id: String = "F024"
    override val title: String = "SAF 目录授权管理"
    override val description: String = "用 SAF 选目录并持久化读写授权，重启后仍可直接访问，也能随时撤销"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F024Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}