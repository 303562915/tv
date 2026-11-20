package com.tv.ball.feature.f007

import android.content.Context
import android.content.Intent
import com.tv.ball.R
import com.tv.ball.feature.FeatureModule

/** 「画面比例与裁剪模式」在功能中心里的登记项。 */
object F007Module : FeatureModule {
    override val id: String = "F007"
    override val title: String = "画面比例与裁剪模式"
    override val description: String = "适应 / 填充 / 拉伸 / 原始 四种模式循环切换，选完记住"
    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F007Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 供播放页取标题资源用，避免重复写字面量。 */
    val titleRes: Int = R.string.f007_title
}