package com.tv.ball.feature.f081

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「播放默认值中心」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f081.xml` 的 f081_title / f081_desc。
 */
object F081Module : FeatureModule {

    override val id: String = "F081"

    override val title: String = "播放默认值中心"

    override val description: String = "倍速 / 比例 / 自动连播 / 快进步长 / 长按倍速 / 字幕自动加载一次配好，只影响此后的新播放"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F081Activity::class.java))
    }
}
