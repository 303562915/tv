package com.tv.ball.feature.f055

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「自定义 DoH 解析」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f055.xml` 的 f055_title / f055_desc。
 */
object F055Module : FeatureModule {

    override val id: String = "F055"

    override val title: String = "自定义 DoH 解析"

    override val description: String = "运营商 DNS 被污染时解析结果是错的，配一个可信 DoH 端点，启用后解析走 HTTPS"

    override val group: String = "网络"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F055Activity::class.java))
    }
}