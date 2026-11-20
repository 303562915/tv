package com.tv.ball.feature.f056

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「代理配置」在功能中心里的登记项。 */
object F056Module : FeatureModule {
    override val id: String = "F056"
    override val title: String = "代理配置"
    override val description: String = "公司网、机房出口走不通时，填上 HTTP 或 SOCKS5 代理，先测通再一键启用"
    override val group: String = "网络"

    override fun open(context: Context) {
        val intent = Intent(context, F056Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
