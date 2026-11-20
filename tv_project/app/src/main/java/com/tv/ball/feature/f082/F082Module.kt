package com.tv.ball.feature.f082

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「界面缩放与字号」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f082.xml` 的 f082_title / f082_desc。
 */
object F082Module : FeatureModule {

    override val id: String = "F082"

    override val title: String = "界面缩放与字号"

    override val description: String = "整体字号与间距按 90% ~ 130% 五档缩放，选完立即预览，重启应用后全站生效"

    override val group: String = "设置"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F082Activity::class.java))
    }
}
