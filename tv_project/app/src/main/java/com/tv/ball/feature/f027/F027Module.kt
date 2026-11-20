package com.tv.ball.feature.f027

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「历史按日分组与批量清理」在功能中心里的登记项。 */
object F027Module : FeatureModule {
    override val id: String = "F027"
    override val title: String = "历史按日分组与批量清理"
    override val description: String = "观看历史按今天/昨天/本周/更早分组，支持多选后一次性批量删除"
    override val group: String = "历史"

    override fun open(context: Context) {
        val intent = Intent(context, F027Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
