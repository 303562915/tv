package com.tv.ball.update

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tv.ball.R
import java.lang.ref.WeakReference

/**
 * 主题化轻提示：底部一条胶囊卡片，颜色跟随当前主题（`theme_primary` 实底 +
 * `theme_on_primary` 文字），可以挂两个动作。
 *
 * 形态照 Canta 的 `ThemeToastHost`：那边是把 Snackbar 换成 `primary` 上色，让更新提示读起来
 * 属于这个应用而不是系统条。这里是纯代码 [PopupWindow]（不新增布局文件），行为对齐——
 * 一次只显示一条、到点自己消失、来新消息时替换文案而不是叠加。
 *
 * 为什么不用 [android.widget.Toast]：Toast 挂不了可点的按钮，也给不出「立即更新 / 稍后」
 * 这种两动作形态；项目里的 `CustomToast` 继续负责纯文本提示，这里只补带动作的那一种。
 */
object UpdatePrompt {

    /** 到点自己消失（等同用户选了「稍后」）。 */
    private const val AUTO_DISMISS_MILLIS = 12_000L

    private const val CORNER_RADIUS_DP = 20f
    private const val HORIZONTAL_PADDING_DP = 16f
    private const val BOTTOM_PADDING_DP = 96f
    private const val ELEVATION_DP = 8f
    private const val TEXT_SIZE_SP = 14f

    private val handler = Handler(Looper.getMainLooper())

    private var popup: PopupWindow? = null
    private var messageView: TextView? = null
    private var hostActivity: WeakReference<Activity>? = null
    private var autoDismiss: Runnable? = null

    /** 正在显示的这一条挂的动作；下载进度刷新时要跟着换，否则点到的是旧数据。 */
    private var currentAction: (() -> Unit)? = null
    private var currentLater: (() -> Unit)? = null

    /** 当前这条是否带动作：带动作的在刷新文案时不能退化成纯文本形态。 */
    private var currentHasAction = false

    /**
     * 显示一条提示；已有同类提示时只换文案，不重建窗口（下载进度就是这么刷的）。
     *
     * @param actionText 主动作文案，null 表示这是一条只读提示
     * @param onAction 主动作回调：先关提示再执行，便于它自己弹对话框
     * @param laterText 次动作文案（通常是「稍后」），null 表示只有一个动作
     * @param onLater 次动作回调；到点自动消失时也走它
     */
    fun show(
        activity: Activity,
        message: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        laterText: String? = null,
        onLater: (() -> Unit)? = null
    ) {
        // 调用方都该在主线程；万一不是，先挪回主线程再碰界面
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(activity, message, actionText, onAction, laterText, onLater) }
            return
        }
        if (!activity.alive()) return

        val hasAction = actionText != null
        val showing = popup
        if (showing != null && showing.isShowing && hostActivity?.get() === activity &&
            currentHasAction == hasAction
        ) {
            // 同一条提示在刷新：换文案、换回调、重排自动消失，不重建窗口（否则进度会闪）
            messageView?.text = message
            currentAction = onAction
            currentLater = onLater
            scheduleAutoDismiss()
            return
        }

        dismiss()
        runCatching {
            val primary = ContextCompat.getColor(activity, R.color.theme_primary)
            val onPrimary = ContextCompat.getColor(activity, R.color.theme_on_primary)

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 18f), dp(activity, 12f), dp(activity, 8f), dp(activity, 12f))
                background = roundedBackground(activity, primary)
                elevation = dp(activity, ELEVATION_DP).toFloat()
            }

            val text = TextView(activity).apply {
                this.text = message
                setTextColor(onPrimary)
                textSize = TEXT_SIZE_SP
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(text)

            var actionView: TextView? = null
            if (actionText != null) {
                val view = actionButton(activity, actionText, onPrimary, bold = true) {
                    val callback = currentAction
                    dismiss()
                    runCatching { callback?.invoke() }
                }
                actionView = view
                card.addView(view)
            }
            if (laterText != null) {
                card.addView(
                    actionButton(activity, laterText, withAlpha(onPrimary, 0.75f), bold = false) {
                        val callback = currentLater
                        dismiss()
                        runCatching { callback?.invoke() }
                    }
                )
            }

            // 外圈留白：左右各 16dp，底部让开悬浮胶囊底栏
            val container = FrameLayout(activity).apply {
                setPadding(
                    dp(activity, HORIZONTAL_PADDING_DP),
                    dp(activity, 8f),
                    dp(activity, HORIZONTAL_PADDING_DP),
                    dp(activity, BOTTOM_PADDING_DP)
                )
            }
            container.addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val window = PopupWindow(
                container,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                // 点窗口外面 = 稍后：透明背景是「外面能点到、点了会关」的前提
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                isFocusable = true
                elevation = dp(activity, ELEVATION_DP).toFloat()
            }
            window.showAtLocation(
                activity.window.decorView,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                0
            )

            popup = window
            messageView = text
            hostActivity = WeakReference(activity)
            currentAction = onAction
            currentLater = onLater
            currentHasAction = hasAction
            // 遥控器：默认焦点落在主动作上，按确认键就是「立即更新」
            actionView?.requestFocus()
            scheduleAutoDismiss()
        }.onFailure {
            // 提示本身失败就静默：更新不是主流程，不能因为它崩
            dismiss()
        }
    }

    /** 只读提示（下载进度、失败原因这类），没有动作。 */
    fun show(activity: Activity, message: String) {
        show(activity, message, null, null, null, null)
    }

    /** 关掉当前提示；没有显示时是空操作。 */
    fun dismiss() {
        cancelAutoDismiss()
        val showing = popup
        popup = null
        messageView = null
        hostActivity = null
        currentAction = null
        currentLater = null
        currentHasAction = false
        runCatching { if (showing?.isShowing == true) showing.dismiss() }
    }

    // ---------------------------------------------------------------- 内部

    private fun actionButton(
        activity: Activity,
        label: String,
        color: Int,
        bold: Boolean,
        onTap: () -> Unit
    ): TextView = TextView(activity).apply {
        text = label
        setTextColor(color)
        textSize = TEXT_SIZE_SP
        if (bold) typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        gravity = Gravity.CENTER
        setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
        setOnClickListener { onTap() }
    }

    private fun roundedBackground(activity: Activity, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, CORNER_RADIUS_DP).toFloat()
            setColor(color)
        }

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        val runnable = Runnable {
            val callback = currentLater
            dismiss()
            runCatching { callback?.invoke() }
        }
        autoDismiss = runnable
        handler.postDelayed(runnable, AUTO_DISMISS_MILLIS)
    }

    private fun cancelAutoDismiss() {
        autoDismiss?.let { handler.removeCallbacks(it) }
        autoDismiss = null
    }

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun Activity.alive(): Boolean = !isFinishing && !isDestroyed
}
