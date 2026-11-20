package com.tv.ball.feature.f035

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「M3U 播放列表导入导出」在功能中心里的登记项。 */
object F035Module : FeatureModule {
    override val id: String = "F035"
    override val title: String = "M3U 播放列表导入导出"
    override val description: String = "把播放队列导出成标准 M3U8 文本，也能粘贴外部 M3U 解析回条目"
    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F035Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}