package com.tv.ball.feature.f079

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「首启动引导」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定属性时再去取资源会引入 Application 未初始化的时序风险（与 F077Module 同一处理）。
 * 页面里显示的同一份文案见 `res/values/strings_f079.xml` 的 f079_title / f079_desc。
 *
 * 本页登记在「设置」分组：首启动已经自动弹过一次的用户，想再看一遍就该能从常规入口找到它。
 */
object F079Module : FeatureModule {

    override val id: String = "F079"

    override val title: String = "首启动引导"

    override val description: String =
        "第一次打开不知道权限要干什么、功能散在哪：五页讲清无账号、只读权限、遥控器手势、功能中心与本地数据"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F079Activity::class.java))
    }
}