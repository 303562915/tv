package com.tv.ball.feature.f083

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「减弱动效」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f083.xml` 的 f083_title / f083_desc。
 */
object F083Module : FeatureModule {

    override val id: String = "F083"

    override val title: String = "减弱动效"

    override val description: String = "低性能盒子上转场和缩放会掉帧，这里一档切到瞬时切换，选择会记下来，下次打开还是这一档"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F083Activity::class.java))
    }
}