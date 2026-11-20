package com.tv.ball.feature.f008

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「音画同步微调」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在列表绑定里再取一次资源会引入 Application 未初始化的时序风险。
 * 界面上显示的同一份文案见 `res/values/strings_f008.xml` 的 f008_title / f008_desc。
 */
object F008Module : FeatureModule {

    override val id: String = "F008"

    override val title: String = "音画同步微调"

    override val description: String = "音轨和画面错位时按 50 毫秒步进校正，范围 -2000 ~ +2000 毫秒"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F008Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}