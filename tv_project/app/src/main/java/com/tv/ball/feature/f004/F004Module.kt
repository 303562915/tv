package com.tv.ball.feature.f004

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「倍速曲线与长按倍速」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f004.xml` 的 f004_title / f004_desc。
 */
object F004Module : FeatureModule {

    override val id: String = "F004"

    override val title: String = "倍速曲线与长按倍速"

    override val description: String = "档位曲线可增删，长按临时加速、松手回到常驻倍速"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F004Activity::class.java))
    }
}
