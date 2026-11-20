package com.tv.ball.feature.f052

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「搜索范围过滤」在功能中心里的登记项。 */
object F052Module : FeatureModule {
    override val id: String = "F052"
    override val title: String = "搜索范围过滤"
    override val description: String = "关键字之外还能按类型多选和时长区间缩小搜索范围，三个条件 AND 叠加生效"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F052Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}