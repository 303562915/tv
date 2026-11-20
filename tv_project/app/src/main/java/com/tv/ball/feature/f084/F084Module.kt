package com.tv.ball.feature.f084

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「日志级别与导出」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f084.xml` 的 f084_title / f084_desc。
 */
object F084Module : FeatureModule {

    override val id: String = "F084"

    override val title: String = "日志级别与导出"

    override val description: String = "五级日志级别随便切，内存里留最近 500 条，一键导出带设备信息的 txt"

    override val group: String = "诊断"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F084Activity::class.java))
    }
}
