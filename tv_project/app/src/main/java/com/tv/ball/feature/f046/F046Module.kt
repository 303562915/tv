package com.tv.ball.feature.f046

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「拼音首字母匹配」在功能中心里的登记项。 */
object F046Module : FeatureModule {
    override val id: String = "F046"
    override val title: String = "拼音首字母匹配"
    override val description: String =
        "遥控器打字慢，用首字母就能命中：lsh 命中「蓝色海洋」，全拼 lanse 也能命中，结果按匹配度排序"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F046Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
