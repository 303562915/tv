package com.tv.ball.feature.f051

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「结果关键字高亮」在功能中心里的登记项。 */
object F051Module : FeatureModule {
    override val id: String = "F051"
    override val title: String = "结果关键字高亮"
    override val description: String = "多个关键词分别命中、重叠区间自动合并，命中片段用主题色加粗高亮，扫读结果更快"
    override val group: String = "搜索"

    override fun open(context: Context) {
        val intent = Intent(context, F051Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
