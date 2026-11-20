package com.tv.ball.feature.f015

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「字幕编码探测」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f015.xml` 的 f015_title / f015_desc。
 */
object F015Module : FeatureModule {

    override val id: String = "F015"

    override val title: String = "字幕编码探测"

    override val description: String = "GBK / Big5 字幕按 UTF-8 读会乱码，先探测编码再解码"

    override val group: String = "字幕"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F015Activity::class.java))
    }
}