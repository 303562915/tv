package com.tv.ball.feature.f001

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「播放器锁定」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f001.xml` 的 f001_title / f001_desc。
 */
object F001Module : FeatureModule {

    override val id: String = "F001"

    override val title: String = "播放器锁定"

    override val description: String = "锁住播放页，挡住遥控器误触，长按返回键解锁"

    override val group: String = "播放核心"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F001Activity::class.java))
    }
}
