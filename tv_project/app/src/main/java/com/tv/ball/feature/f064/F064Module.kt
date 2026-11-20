package com.tv.ball.feature.f064

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「二维码分享地址」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定时再去取资源会引入 Application 尚未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f064.xml` 的 f064_title / f064_desc。
 */
object F064Module : FeatureModule {

    override val id: String = "F064"

    override val title: String = "二维码分享地址"

    override val description: String = "把播放地址画成二维码，手机扫一下就打开；编码在本机完成，不需要联网"

    override val group: String = "投屏"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F064Activity::class.java))
    }
}
