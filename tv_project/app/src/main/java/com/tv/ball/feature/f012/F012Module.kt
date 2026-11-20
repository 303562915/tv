package com.tv.ball.feature.f012

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「字幕样式设置」在功能中心里的登记项。
 *
 * title / description 用字面量：FeatureModule 接口只给字符串、不传 Context，
 * 在列表绑定里读属性时再取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f012.xml` 的 f012_title / f012_desc。
 */
object F012Module : FeatureModule {

    override val id: String = "F012"

    override val title: String = "字幕样式设置"

    override val description: String = "字号、颜色、描边、背景四项独立可调，预览区实时看效果"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        val intent = Intent(context, F012Activity::class.java)
        // 功能中心可能从非 Activity 的 Context 调进来，此时必须开新任务栈
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}