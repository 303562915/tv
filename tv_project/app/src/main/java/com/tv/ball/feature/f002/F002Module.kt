package com.tv.ball.feature.f002

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「A-B 段落复读」在功能中心里的注册项。
 *
 * 只负责把入口交出去，功能本身的逻辑都在 [F002Activity] + [F002Engine] 里。
 */
object F002Module : FeatureModule {

    override val id: String = "F002"

    override val title: String = "A-B 段落复读"

    override val description: String = "标出 A、B 两点，播放到 B 点自动跳回 A 点反复听一段"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F002Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}