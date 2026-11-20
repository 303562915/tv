package com.tv.ball.feature.f088

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「遥控器按键自定义」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f088.xml` 的 f088_title / f088_desc。
 */
object F088Module : FeatureModule {

    override val id: String = "F088"

    override val title: String = "遥控器按键自定义"

    override val description: String = "不同遥控器上同一个功能发出来的键值不一样，这里先把你的键认下来，再绑给播放/暂停、上下集这些动作"

    override val group: String = "界面"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F088Activity::class.java))
    }
}