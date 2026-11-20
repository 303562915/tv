package com.tv.ball.feature.f065

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「局域网目录浏览」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定时再去取资源会引入 Application 尚未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f065.xml` 的 f065_title / f065_desc。
 */
object F065Module : FeatureModule {

    override val id: String = "F065"

    override val title: String = "局域网目录浏览"

    override val description: String = "把电视上的目录挂成网页，手机或电脑在同一局域网里点开就能一层层浏览、直接下载文件"

    override val group: String = "投屏"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F065Activity::class.java))
    }
}
