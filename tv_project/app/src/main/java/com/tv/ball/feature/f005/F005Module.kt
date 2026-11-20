package com.tv.ball.feature.f005

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「下集倒计时浮层」在功能中心里的注册项。
 *
 * 只负责把入口交出去，功能本身的逻辑都在 [F005Activity] + [F005Engine] 里。
 */
object F005Module : FeatureModule {

    override val id: String = "F005"

    override val title: String = "下集倒计时浮层"

    override val description: String = "接近片尾时提示下一集并在倒计时结束后自动切换，可取消"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F005Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}