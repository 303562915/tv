package com.tv.ball.feature.f042

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「焦点记忆」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f042.xml` 的 f042_title / f042_desc。
 */
object F042Module : FeatureModule {

    override val id: String = "F042"

    override val title: String = "焦点记忆"

    override val description: String = "记住每个列表最后停留的那一条，切页返回时焦点自动落回去"

    override val group: String = "界面"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F042Activity::class.java))
    }
}