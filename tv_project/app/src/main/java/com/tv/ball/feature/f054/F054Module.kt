package com.tv.ball.feature.f054

import android.content.Context
import android.content.Intent
import com.tv.ball.feature.FeatureModule

/** 「线路测速缓存」在功能中心里的登记项。 */
object F054Module : FeatureModule {
    override val id: String = "F054"
    override val title: String = "线路测速缓存"
    override val description: String = "测完的速度带时间戳缓存下来，按速度排序展示，有效期过了再重测，不用每次都从头测一遍"
    override val group: String = "网络"

    override fun open(context: Context) {
        val intent = Intent(context, F054Activity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
