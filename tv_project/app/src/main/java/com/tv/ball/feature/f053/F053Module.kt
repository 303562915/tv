package com.tv.ball.feature.f053

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「网络诊断面板」在功能中心里的登记项。 */
object F053Module : FeatureModule {
    override val id: String = "F053"
    override val title: String = "网络诊断面板"
    override val description: String = "播放卡顿时分辨是 DNS 慢、端口连不上还是网络本身正常，三档建议直接给答案"
    override val group: String = "网络"

    override fun open(context: Context) {
        val intent = Intent(context, F053Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
