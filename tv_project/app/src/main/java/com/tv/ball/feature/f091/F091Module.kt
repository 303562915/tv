package com.tv.ball.feature.f091

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「启动耗时打点」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定属性时再取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f091.xml` 的 f091_title / f091_desc。
 */
object F091Module : FeatureModule {

    override val id: String = "F091"

    override val title: String = "启动耗时打点"

    override val description: String =
        "冷启动慢要能指到具体一段：这里把 Application onCreate、Activity onCreate、首帧三段分别记时，留最近 10 次并可导出，用来对账到底哪一段吃掉了时间"

    override val group: String = "性能"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F091Activity::class.java))
    }
}
