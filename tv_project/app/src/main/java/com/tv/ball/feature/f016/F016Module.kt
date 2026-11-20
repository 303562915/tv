package com.tv.ball.feature.f016

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「双语双行字幕合成」在功能中心里的登记项。
 *
 * title / description 用字面量：FeatureModule 只给字符串、不传 Context，
 * 在列表绑定阶段再取资源会有 Application 未初始化的时序风险。
 * 界面上显示的同一份文案见 `res/values/strings_f016.xml` 的 f016_title / f016_desc。
 */
object F016Module : FeatureModule {

    override val id: String = "F016"

    override val title: String = "双语双行字幕合成"

    override val description: String = "把主副两条 SRT 按时间轴合成，重叠区间上下两行、缺失区间单行"

    override val group: String = "字幕"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F016Activity::class.java))
    }
}
