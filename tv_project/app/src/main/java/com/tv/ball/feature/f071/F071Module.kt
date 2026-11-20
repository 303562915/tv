package com.tv.ball.feature.f071

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「全量数据备份」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心的列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f071.xml` 的 f071_title / f071_desc。
 */
object F071Module : FeatureModule {

    override val id: String = "F071"

    override val title: String = "全量数据备份"

    override val description: String = "把设置、收藏、播放历史和应用数据打成一份 ZIP，换机重装前一键带走"

    override val group: String = "备份"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F071Activity::class.java))
    }
}
