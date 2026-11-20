package com.tv.ball.feature.f067

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「内置媒体服务器」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 只给字符串、不传 Context，
 * 在列表绑定时取资源会引入 Application 尚未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f067.xml` 的 f067_title / f067_desc。
 */
object F067Module : FeatureModule {

    override val id: String = "F067"

    override val title: String = "内置媒体服务器"

    override val description: String =
        "把本机视频挂成 HTTP 目录页：NanoHTTPD 起一个端口可配的 Web 服务，同网的手机 / 平板 / 电脑在浏览器里点开就能播放，支持拖动进度"

    override val group: String = "媒体服务"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F067Activity::class.java))
    }
}
