package com.tv.ball.feature.f026

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「最近添加与最近播放」在功能中心里的登记项。 */
object F026Module : FeatureModule {
    override val id: String = "F026"
    override val title: String = "最近添加与最近播放"
    override val description: String = "两个横向数据源：按文件修改时间看最近添加，按最后播放时间看最近播放，展示条数上限可调"
    override val group: String = "媒体库"

    override fun open(context: Context) {
        val intent = Intent(context, F026Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 打开，缺了 NEW_TASK 会直接抛异常
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
