package com.tv.ball.feature.f087

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「无障碍描述审计」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f087.xml` 的 f087_title / f087_desc。
 */
object F087Module : FeatureModule {

    override val id: String = "F087"

    override val title: String = "无障碍描述审计"

    override val description: String = "扫描本页可点控件，把读屏念不出来的图标按钮列成清单，并一键补上描述文案"

    override val group: String = "无障碍"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F087Activity::class.java))
    }
}