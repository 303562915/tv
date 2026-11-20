package com.tv.ball.feature.f037

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「播放列表封面与描述」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f037.xml` 的 f037_title / f037_desc。
 */
object F037Module : FeatureModule {

    override val id: String = "F037"

    override val title: String = "播放列表封面与描述"

    override val description: String = "给播放列表配一张本地封面、写一段简介，列表页一眼就能认出它是哪个列表"

    override val group: String = "播放队列"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F037Activity::class.java))
    }
}