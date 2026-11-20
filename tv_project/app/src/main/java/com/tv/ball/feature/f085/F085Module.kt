package com.tv.ball.feature.f085

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「崩溃捕获与本地报告」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 功能中心的列表绑定读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f085.xml` 的 f085_title / f085_desc。
 */
object F085Module : FeatureModule {

    override val id: String = "F085"

    override val title: String = "崩溃捕获与本地报告"

    override val description: String = "挂上全局未捕获异常处理器，崩溃时把设备信息与完整堆栈写成本地报告，下次启动逐条查看、删除或清空"

    override val group: String = "诊断"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F085Activity::class.java))
    }
}