package com.tv.ball.feature.f070

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「Range 请求与流式传输」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f070.xml` 的 f070_title / f070_desc。
 */
object F070Module : FeatureModule {

    override val id: String = "F070"

    override val title: String = "Range 请求与流式传输"

    override val description: String = "解析 bytes 区间给出 206 / 200 / 416，并按 64 KB 分块往外发，大文件不整读内存"

    override val group: String = "媒体服务"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F070Activity::class.java))
    }
}