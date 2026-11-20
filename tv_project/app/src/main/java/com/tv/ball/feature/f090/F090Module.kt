package com.tv.ball.feature.f090

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「骨架屏与空态组件」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f090.xml` 的 f090_title / f090_desc。
 */
object F090Module : FeatureModule {

    override val id: String = "F090"

    override val title: String = "骨架屏与空态组件"

    override val description: String = "列表加载时先用占位块撑住版面，没数据、加载失败各有图标和主副文案，一套四态视图给全站列表复用"

    override val group: String = "界面"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F090Activity::class.java))
    }
}
