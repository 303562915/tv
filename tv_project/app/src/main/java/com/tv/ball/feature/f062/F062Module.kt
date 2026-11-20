package com.tv.ball.feature.f062

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「投屏会话控制」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f062.xml` 的 f062_title / f062_desc。
 */
object F062Module : FeatureModule {

    override val id: String = "F062"

    override val title: String = "投屏会话控制"

    override val description: String = "投上大屏之后还能接着管：播放、暂停、进度跳转、音量，都直接发给设备的 AVTransport，回执原文可以展开看"

    override val group: String = "投屏"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F062Activity::class.java))
    }
}
