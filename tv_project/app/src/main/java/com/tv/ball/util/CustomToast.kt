package com.tv.ball.util

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.tv.ball.R

object CustomToast {

    /** 距底部 96dp：按密度换算，小屏不顶到列表、大屏也不会飘太高 */
    private const val BOTTOM_OFFSET_DP = 96f

    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.custom_toast, null)

        val textView = layout.findViewById<TextView>(R.id.toast_text)
        textView.text = message

        val toast = Toast(context)
        toast.duration = duration
        toast.view = layout
        val offsetY = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BOTTOM_OFFSET_DP,
            context.resources.displayMetrics
        ).toInt()
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, offsetY)
        toast.show()
    }

    fun showLong(context: Context, message: String) {
        show(context, message, Toast.LENGTH_LONG)
    }
}
