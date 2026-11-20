package com.tv.ball.feature.f019

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「媒体库增量索引」在功能中心里的登记项。 */
object F019Module : FeatureModule {
    override val id: String = "F019"
    override val title: String = "媒体库增量索引"
    override val description: String = "对比新旧快照，只列出新增 / 变更 / 消失的条目，跳过没动过的文件"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F019Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
