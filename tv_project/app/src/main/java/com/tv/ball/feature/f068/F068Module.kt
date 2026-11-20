package com.tv.ball.feature.f068

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「MIME 映射与响应头」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f068.xml` 的 f068_title / f068_desc。
 */
object F068Module : FeatureModule {

    override val id: String = "F068"

    override val title: String = "MIME 映射与响应头"

    override val description: String =
        "同一个 mp4 换个错的 Content-Type，浏览器就从播放变成下载：扩展名到 MIME 的整张表、" +
            "长度/Range/CORS/缓存这几条响应头、以及浏览器到底能不能直接播，一次算清"

    override val group: String = "媒体服务"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F068Activity::class.java))
    }
}
