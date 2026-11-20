package com.tv.ball.feature.f009

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「快进快退步长策略」在功能中心里的注册项。
 *
 * 只负责把入口交出去，步长判定都在 [F009Engine]，页面在 [F009Activity]。
 */
object F009Module : FeatureModule {

    override val id: String = "F009"

    override val title: String = "快进快退步长策略"

    override val description: String = "5/10/30/60 秒步长可选，智能模式按一次直接跳过片头片尾"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F009Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}