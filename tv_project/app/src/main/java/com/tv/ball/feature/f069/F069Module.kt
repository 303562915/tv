package com.tv.ball.feature.f069

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「访问日志与令牌鉴权」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f069.xml` 的 f069_title / f069_desc。
 */
object F069Module : FeatureModule {

    override val id: String = "F069"

    override val title: String = "访问日志与令牌鉴权"

    override val description: String =
        "局域网服务开了端口就等于对整层楼开放：谁在什么时候取了哪个文件、状态码是多少，" +
            "以及要不要给地址加一枚 16 位令牌——开着就只认带令牌的请求，怀疑被人连过就一键换掉"

    override val group: String = "媒体服务"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F069Activity::class.java))
    }
}