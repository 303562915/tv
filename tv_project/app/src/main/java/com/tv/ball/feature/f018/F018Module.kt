package com.tv.ball.feature.f018

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「字幕文本检索与跳转」在功能中心里的登记项。 */
object F018Module : FeatureModule {
    override val id: String = "F018"
    override val title: String = "字幕文本检索与跳转"
    override val description: String = "全文检索当前字幕，命中处高亮，点结果直接跳到那句台词的时间点"
    override val group: String = "字幕"

    override fun open(context: Context) {
        val intent = Intent(context, F018Activity::class.java)
        // 从非 Activity 的 context（功能中心的入口包装）拉起时要补 NEW_TASK，否则会抛异常
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}