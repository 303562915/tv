package com.tv.ball.feature.f094

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「缓存分类清理」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f094.xml` 的 f094_title / f094_desc。
 */
object F094Module : FeatureModule {

    override val id: String = "F094"

    override val title: String = "缓存分类清理"

    override val description: String = "把缓存按图片、视频、日志、其他分开统计，可以只清某一类，也可以一次清空"

    override val group: String = "存储"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F094Activity::class.java))
    }
}
