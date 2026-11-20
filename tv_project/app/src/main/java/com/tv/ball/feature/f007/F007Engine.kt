package com.tv.ball.feature.f007

import android.content.Context
import android.content.SharedPreferences
import com.tv.ball.R

/**
 * 画面比例与裁剪模式的中心逻辑。
 *
 * resizeMode 直接复用 ExoPlayer `AspectRatioFrameLayout` 的常量整数值
 * （FIT=0 / FIXED_WIDTH=1 / FIXED_HEIGHT=2 / FILL=3 / ZOOM=4）。
 * 这里刻意不 import 播放内核：功能模块只吐数字，由播放页把它喂给播放器，
 * 这样改播放内核不影响这里，也方便单测。
 *
 * 四种模式的语义：
 * - FIT      适应：整幅画面可见，多出来的区域留背景色
 * - CROP     填充：等比放大到铺满，超出部分裁掉（画面不形变）
 * - STRETCH  拉伸：宽高都铺满，比例被拉变形
 * - ORIGINAL 原始：宽度铺满，高度按片源比例推（4:3 片源会很高）
 */
enum class AspectMode(val labelRes: Int, val resizeMode: Int) {
    FIT(R.string.f007_mode_fit, 0),
    CROP(R.string.f007_mode_crop, 4),
    STRETCH(R.string.f007_mode_stretch, 3),
    ORIGINAL(R.string.f007_mode_original, 1);

    fun next(): AspectMode = values()[(ordinal + 1) % values().size]
}

/** 预览用的矩形，纯整数，方便直接喂给 LayoutParams。 */
data class PreviewRect(val width: Int, val height: Int)

class F007Engine(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_MODE = "aspect_mode"
        const val PREFS_NAME = "f007_aspect"

        fun create(context: Context): F007Engine =
            F007Engine(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    var mode: AspectMode
        get() = AspectMode.values().getOrNull(prefs.getInt(KEY_MODE, AspectMode.FIT.ordinal))
            ?: AspectMode.FIT
        set(value) {
            prefs.edit().putInt(KEY_MODE, value.ordinal).apply()
        }

    fun cycle(): AspectMode = mode.next().also { mode = it }

    fun reset() {
        mode = AspectMode.FIT
    }

    /**
     * 算出某种模式下画面在容器里的实际占位。
     * STRETCH/CROP 都会占满容器，差别在于前者形变、后者裁切；
     * FIT 缩小到装得下，ORIGINAL 宽度铺满后按片源比例推高度（可溢出容器）。
     */
    fun displaySize(mode: AspectMode, containerWidth: Int, containerHeight: Int, sourceAspect: Float): PreviewRect {
        if (containerWidth <= 0 || containerHeight <= 0 || sourceAspect <= 0f) {
            return PreviewRect(0, 0)
        }
        return when (mode) {
            AspectMode.FIT, AspectMode.CROP -> {
                val containerAspect = containerWidth.toFloat() / containerHeight
                if (containerAspect > sourceAspect) {
                    val h = containerHeight
                    val w = (h * sourceAspect).toInt()
                    if (mode == AspectMode.FIT) PreviewRect(w, h)
                    else PreviewRect(containerWidth, containerHeight)
                } else {
                    val w = containerWidth
                    val h = (w / sourceAspect).toInt()
                    if (mode == AspectMode.FIT) PreviewRect(w, h)
                    else PreviewRect(containerWidth, containerHeight)
                }
            }
            AspectMode.STRETCH -> PreviewRect(containerWidth, containerHeight)
            AspectMode.ORIGINAL -> {
                val w = containerWidth
                PreviewRect(w, (w / sourceAspect).toInt())
            }
        }
    }

    /** 该模式下会不会出现黑边（FIT 才会，其余铺满容器）。 */
    fun hasLetterbox(mode: AspectMode, containerWidth: Int, containerHeight: Int, sourceAspect: Float): Boolean {
        if (mode != AspectMode.FIT) return false
        val r = displaySize(mode, containerWidth, containerHeight, sourceAspect)
        return r.width != containerWidth || r.height != containerHeight
    }
}