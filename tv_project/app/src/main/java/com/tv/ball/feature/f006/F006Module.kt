package com.tv.ball.feature.f006

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「播放失败自动重试与线路降级」在功能中心里的注册项。
 *
 * 只负责把入口交出去，功能本身的逻辑都在 [F006Activity] + [F006Engine] 里。
 */
object F006Module : FeatureModule {

    override val id: String = "F006"

    override val title: String = "播放失败自动重试与线路降级"

    override val description: String = "播放失败按 1s/2s/4s 自动重试 3 次，仍失败就建议切换线路"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F006Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}