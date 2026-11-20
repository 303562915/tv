package com.tv.ball.feature.f093

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「数据库迁移校验」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f093.xml` 的 f093_title / f093_desc。
 */
object F093Module : FeatureModule {

    override val id: String = "F093"

    override val title: String = "数据库迁移校验"

    override val description: String =
        "升级后表结构对不上，Room 一打开就崩、堆栈里还看不出是哪张表。这里按代码里的期望结构逐表逐列比对设备上那份库，列出缺的表/列，给出能照着跑的修复 SQL"

    override val group: String = "质量"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F093Activity::class.java))
    }
}