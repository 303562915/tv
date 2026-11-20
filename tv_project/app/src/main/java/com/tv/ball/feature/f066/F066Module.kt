package com.tv.ball.feature.f066

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「远程遥控通道」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 只给字符串、不传 Context，
 * 在列表绑定时取资源会引入 Application 尚未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f066.xml` 的 f066_title / f066_desc。
 */
object F066Module : FeatureModule {

    override val id: String = "F066"

    override val title: String = "远程遥控通道"

    override val description: String = "手机当遥控器：一条 WebSocket 指令通道直连电视上的播放器，播放 / 暂停 / 上下集 / 进度 / 音量都能发，断线按指数退避自动重连"

    override val group: String = "投屏"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F066Activity::class.java))
    }
}