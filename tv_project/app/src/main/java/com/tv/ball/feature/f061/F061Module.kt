package com.tv.ball.feature.f061

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「DLNA 设备发现」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f061.xml` 的 f061_title / f061_desc。
 */
object F061Module : FeatureModule {

    override val id: String = "F061"

    override val title: String = "DLNA 设备发现"

    override val description: String = "局域网里的电视、盒子要先能用 SSDP 找出来，投屏才有目标可选"

    override val group: String = "投屏"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F061Activity::class.java))
    }
}
