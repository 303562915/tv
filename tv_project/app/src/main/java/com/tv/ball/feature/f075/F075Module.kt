package com.tv.ball.feature.f075

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「CSV 数据导出」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 在功能中心列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f075.xml` 的 f075_title / f075_desc。
 */
object F075Module : FeatureModule {

    override val id: String = "F075"

    override val title: String = "CSV 数据导出"

    override val description: String = "把观看历史和收藏导成 CSV，用表格软件打开就能按时间、进度排序翻看"

    override val group: String = "备份"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F075Activity::class.java))
    }
}