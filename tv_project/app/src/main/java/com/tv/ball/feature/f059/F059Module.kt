package com.tv.ball.feature.f059

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「请求头配置」在功能中心里的登记项。 */
object F059Module : FeatureModule {
    override val id: String = "F059"
    override val title: String = "请求头配置"
    override val description: String = "按域名给直链配 UA 与 Referer，防盗链的源也能正常起播"
    override val group: String = "网络"

    override fun open(context: Context) {
        val intent = Intent(context, F059Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}