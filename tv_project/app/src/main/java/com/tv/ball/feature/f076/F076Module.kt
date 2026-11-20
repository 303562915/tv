package com.tv.ball.feature.f076

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「存储占用分析」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心的列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f076.xml` 的 f076_title / f076_desc。
 */
object F076Module : FeatureModule {

    override val id: String = "F076"

    override val title: String = "存储占用分析"

    override val description: String = "把缓存、应用文件、数据库、下载、缩略图各占多少量出来，占比条一眼对比，再去对应功能页清理"

    override val group: String = "存储"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F076Activity::class.java))
    }
}
