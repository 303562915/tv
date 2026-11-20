package com.tv.ball.feature.f031

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「观看时长周报」在功能中心里的登记项。 */
object F031Module : FeatureModule {
    override val id: String = "F031"
    override val title: String = "观看时长周报"
    override val description: String = "按天统计本周看了多久，一根柱子一天，总量、日均、最长单日一眼看清"
    override val group: String = "统计"

    override fun open(context: Context) {
        val intent = Intent(context, F031Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
