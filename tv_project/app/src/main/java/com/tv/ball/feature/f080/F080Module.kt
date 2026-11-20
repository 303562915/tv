package com.tv.ball.feature.f080

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「隐私模式」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f080.xml` 的 f080_title / f080_desc。
 */
object F080Module : FeatureModule {

    override val id: String = "F080"

    override val title: String = "隐私模式"

    override val description: String = "公共场合看片不留痕：开启后不写观看历史与续播进度，还能把标题打码"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F080Activity::class.java))
    }
}