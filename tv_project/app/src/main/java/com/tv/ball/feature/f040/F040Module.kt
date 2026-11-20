package com.tv.ball.feature.f040

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「循环策略中心」在功能中心里的注册项。
 *
 * 只负责把入口交出去，功能本身的逻辑都在 [F040Activity] + [F040Engine] 里。
 */
object F040Module : FeatureModule {

    override val id: String = "F040"

    override val title: String = "循环策略中心"

    override val description: String = "顺序 / 单曲 / 列表循环一个入口切完，策略记住不用每次重设"

    override val group: String = "播放队列"

    override fun open(context: Context) {
        val intent = Intent(context, F040Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}