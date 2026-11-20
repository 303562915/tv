package com.tv.ball.feature.f048

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「搜索防抖与竞态取消」在功能中心里的登记项。 */
object F048Module : FeatureModule {
    override val id: String = "F048"
    override val title: String = "搜索防抖与竞态取消"
    override val description: String = "连续输入只在停顿 300ms 后发一次请求，旧请求统一作废，只有最新结果能渲染到界面上"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F048Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
