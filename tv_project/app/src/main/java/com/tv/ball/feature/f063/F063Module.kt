package com.tv.ball.feature.f063

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「局域网设备扫描」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f063.xml` 的 f063_title / f063_desc。
 */
object F063Module : FeatureModule {

    override val id: String = "F063"

    override val title: String = "局域网设备扫描"

    override val description: String = "扫本机 /24 网段里哪些设备在线、常见端口开了几个，结果可一键导出成文本"

    override val group: String = "投屏"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F063Activity::class.java))
    }
}
