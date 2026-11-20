package com.tv.ball.feature.f060

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「地址连通性预检」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f060.xml` 的 f060_title / f060_desc。
 */
object F060Module : FeatureModule {

    override val id: String = "F060"

    override val title: String = "地址连通性预检"

    override val description: String = "点播前先 HEAD 探一下地址通不通，5 秒超时直接判不可达，结果缓存 5 分钟"

    override val group: String = "网络"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F060Activity::class.java))
    }
}
