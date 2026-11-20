package com.tv.ball.feature.f089

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「长按上下文菜单」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f089.xml` 的 f089_title / f089_desc。
 */
object F089Module : FeatureModule {

    override val id: String = "F089"

    override val title: String = "长按上下文菜单"

    override val description: String = "列表里长按一条直接出菜单：播放、收藏、看信息、删除，不用先点进详情再找按钮"

    override val group: String = "界面"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F089Activity::class.java))
    }
}