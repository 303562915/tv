package com.tv.ball.feature.f092

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「内存与线程探针」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：[FeatureModule] 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面上显示的同一份文案见 `res/values/strings_f092.xml` 的 f092_title / f092_desc。
 */
object F092Module : FeatureModule {

    override val id: String = "F092"

    override val title: String = "内存与线程探针"

    override val description: String = "按秒采样堆内存与线程数，用一条 5 分钟曲线看它是不是只增不减，异常增长当场提示并可导出文本"

    override val group: String = "性能"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F092Activity::class.java))
    }
}
