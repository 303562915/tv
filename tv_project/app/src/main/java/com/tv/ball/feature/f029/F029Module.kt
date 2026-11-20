package com.tv.ball.feature.f029

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「收藏标签与分组」在功能中心里的登记项。 */
object F029Module : FeatureModule {
    override val id: String = "F029"
    override val title: String = "收藏标签与分组"
    override val description: String = "给收藏打多个自定义标签，按标签筛选归档；标签可重命名、可删除，改动立即落盘，重进沿用"
    override val group: String = "收藏"

    override fun open(context: Context) {
        val intent = Intent(context, F029Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
