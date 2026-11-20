package com.tv.ball.feature.f074

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「收藏导入导出」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f074.xml` 的 f074_title / f074_desc。
 */
object F074Module : FeatureModule {

    override val id: String = "F074"

    override val title: String = "收藏导入导出"

    override val description: String = "把收藏单独导出成 JSON，也能从文件或粘贴文本导回来，按标题+封面去重、标签取并集"

    override val group: String = "备份"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F074Activity::class.java))
    }
}
