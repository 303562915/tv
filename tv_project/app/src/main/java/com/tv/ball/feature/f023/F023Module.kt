package com.tv.ball.feature.f023

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「忽略目录与扩展名规则」在功能中心里的登记项。 */
object F023Module : FeatureModule {
    override val id: String = "F023"
    override val title: String = "忽略目录与扩展名规则"
    override val description: String = "按目录前缀与扩展名黑名单跳过不该扫的路径，还能当场试算一条路径是否被忽略"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F023Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}