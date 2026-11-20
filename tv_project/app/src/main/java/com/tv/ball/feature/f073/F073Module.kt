package com.tv.ball.feature.f073

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「历史导入导出」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 接口只给字符串、不传 Context，
 * 而在功能中心的列表绑定里读属性时再去取资源会引入 Application 未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f073.xml` 的 f073_title / f073_desc。
 */
object F073Module : FeatureModule {

    override val id: String = "F073"

    override val title: String = "历史导入导出"

    override val description: String = "把播放历史导出成可读 JSON，也能从文件或粘贴文本导回来，按 id 去重"

    override val group: String = "备份"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F073Activity::class.java))
    }
}
