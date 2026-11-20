package com.tv.ball.feature.f010

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「手势灵敏度配置」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f010.xml` 的 f010_title / f010_desc。
 */
object F010Module : FeatureModule {

    override val id: String = "F010"

    override val title: String = "手势灵敏度配置"

    override val description: String = "调滑动阈值与增益，预览区实时看亮度/音量变化手感"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F010Activity::class.java))
    }
}