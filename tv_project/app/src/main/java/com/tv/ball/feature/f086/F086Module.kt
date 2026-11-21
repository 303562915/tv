package com.tv.ball.feature.f086

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「关于」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f086.xml` 的 f086_title / f086_tagline。
 */
object F086Module : FeatureModule {

    override val id: String = "F086"

    override val title: String = "关于"

    override val description: String = "版本信息、更新渠道（稳定版 / 开发版）、检查更新与自动安装、下载页与源码仓库集中一页"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F086Activity::class.java))
    }
}
