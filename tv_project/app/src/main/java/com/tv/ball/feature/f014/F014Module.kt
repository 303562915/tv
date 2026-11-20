package com.tv.ball.feature.f014

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「同名字幕自动匹配」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 getString：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f014.xml` 的 f014_title / f014_desc。
 */
object F014Module : FeatureModule {

    override val id: String = "F014"

    override val title: String = "同名字幕自动匹配"

    override val description: String = "视频旁边的同名 srt 自动认出来，按语言优先级挑最合适的一条"

    override val group: String = "字幕"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F014Activity::class.java))
    }
}
