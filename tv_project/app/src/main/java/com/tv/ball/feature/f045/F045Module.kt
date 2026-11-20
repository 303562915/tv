package com.tv.ball.feature.f045

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「搜索历史管理」在功能中心里的登记项。 */
object F045Module : FeatureModule {
    override val id: String = "F045"
    override val title: String = "搜索历史管理"
    override val description: String =
        "搜过的词记下来：重复的只留最新一条，最多留 20 条，可以单条删除也可以一键清空"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F045Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
