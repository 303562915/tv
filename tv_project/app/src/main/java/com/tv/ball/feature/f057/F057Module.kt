package com.tv.ball.feature.f057

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「缓冲与并发策略」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f057.xml` 的 f057_title / f057_desc。
 */
object F057Module : FeatureModule {

    override val id: String = "F057"

    override val title: String = "缓冲与并发策略"

    override val description: String = "弱网下默认缓冲容易卡：把缓冲水位与并发连接数做成三档预设加自定义，改完直接给出等效的 DefaultLoadControl 参数"

    override val group: String = "网络"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F057Activity::class.java))
    }
}
