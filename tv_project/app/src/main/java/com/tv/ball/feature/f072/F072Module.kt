package com.tv.ball.feature.f072

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/**
 * 「备份恢复与校验」在功能中心里的登记项。
 *
 * title / description 用字面量而不是 `getString`：FeatureModule 只给字符串、不传 Context，
 * 在列表绑定时取资源会引入 Application 尚未初始化的时序风险。
 * 界面里显示的同一份文案见 `res/values/strings_f072.xml` 的 f072_title / f072_desc。
 */
object F072Module : FeatureModule {

    override val id: String = "F072"

    override val title: String = "备份恢复与校验"

    override val description: String = "从备份 zip 恢复前先读 manifest 并逐条核对 sha256，版本不兼容直接拦下；恢复前把现有文件复制到 rollback-<时间> 目录，中途失败自动回滚"

    override val group: String = "备份"

    override fun open(context: Context) {
        context.startActivity(Intent(context, F072Activity::class.java))
    }
}
