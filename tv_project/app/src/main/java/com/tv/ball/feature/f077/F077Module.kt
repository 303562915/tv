package com.tv.ball.feature.f077

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「设置项搜索」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心的列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f077.xml` 的 f077_title / f077_desc。
 */
object F077Module : FeatureModule {

    override val id: String = "F077"

    override val title: String = "设置项搜索"

    override val description: String = "设置散在各功能页里，输入「倍速」「清缓存」「代理」这类词就直接定位到该进哪一页"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F077Activity::class.java))
    }
}
