package com.tv.ball.feature.f078

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「设置分组重置」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心绑定列表属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面上显示的同一份文案见 `res/values/strings_f078.xml` 的 f078_title / f078_desc。
 */
object F078Module : FeatureModule {

    override val id: String = "F078"

    override val title: String = "设置分组重置"

    override val description: String = "某项调乱了需要恢复默认：按分组把设置一键重置回默认值，重置前二次确认"

    override val group: String = "设置"

    override fun open(context: Context) {
        val intent = Intent(context, F078Activity::class.java)
        // 从功能中心（Application Context）拉起时必须有 NEW_TASK，否则会抛 AndroidRuntimeException
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
