package com.tv.ball.feature.f044

import android.content.Context
import android.content.Intent

/**
 * 「搜索建议与联想」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而功能中心绑定列表时读属性若再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f044.xml` 的 f044_title / f044_desc。
 */
object F044Module : com.tv.ball.feature.FeatureModule {

    override val id: String = "F044"

    override val title: String = "搜索建议与联想"

    override val description: String = "输入时给出候选，减少按键次数"

    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F044Activity::class.java)
        // 功能中心传进来的可能是 ApplicationContext，不加 NEW_TASK 会直接抛异常
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
