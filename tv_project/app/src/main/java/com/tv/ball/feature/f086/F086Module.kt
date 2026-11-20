package com.tv.ball.feature.f086

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「关于页与许可证」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f086.xml` 的 f086_title / f086_desc。
 */
object F086Module : FeatureModule {

    override val id: String = "F086"

    override val title: String = "关于页与许可证"

    override val description: String = "版本、包名、更新日期、依赖清单与各自许可证集中一页，一键打开源码仓库"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F086Activity::class.java))
    }
}
