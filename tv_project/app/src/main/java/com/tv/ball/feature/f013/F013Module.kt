package com.tv.ball.feature.f013

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「字幕轨道切换与延迟」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f013.xml` 的 f013_title / f013_desc。
 */
object F013Module : FeatureModule {

    override val id: String = "F013"

    override val title: String = "字幕轨道切换与延迟"

    override val description: String = "在多条内嵌字幕轨之间切换，并对字幕时间轴做 ±100ms 校正"

    override val group: String = "字幕"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F013Activity::class.java))
    }
}
