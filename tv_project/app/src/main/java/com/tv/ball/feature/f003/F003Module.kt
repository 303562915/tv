package com.tv.ball.feature.f003

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「精确定位跳转」在功能中心里的注册项。
 *
 * 只负责把入口交出去，功能本身的逻辑都在 [F003Activity] + [F003Engine] 里。
 */
object F003Module : FeatureModule {

    override val id: String = "F003"

    override val title: String = "精确定位跳转"

    override val description: String = "输入时间码直接跳到指定秒，不再拖着进度条找位置"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F003Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
