package com.tv.ball.feature.f083

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF083Binding
import com.tv.ball.util.CustomToast

/**
 * 「减弱动效」页。
 *
 * 三段：动效档位（标准 / 减弱两行）、当前生效值（实际时长 + 是否启用动画）、演示。
 *
 * 关键设计：页面**不缓存**档位，每次操作后都从 [F083Engine] 重新取一遍再整体重画
 * （[render]）。这样"界面上显示的时长"与"演示按钮真正用的时长"永远同源，
 * 不会出现切换后状态行还写着 300 ms 的旧值。
 *
 * 演示按钮做的事就是本功能承诺的事：减弱档时方块直接到位（时长 0，连 ValueAnimator 都不启），
 * 标准档时按 [F083Engine.BASE_DURATION_MS] 从 0.92 缩放到 1.0 并淡入。
 * 用 [ValueAnimator] 而不是 ViewPropertyAnimator，是因为要同时改 scale 与 alpha、
 * 并且必须能在减弱档下"零时长"完成——两者都在同一个回调里设值最省事。
 *
 * 演示动画只改视图属性、不落任何数据；离开页面时 [onDestroy] 取消，避免动画回调打到已销毁的视图。
 */
class F083Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF083Binding

    /** 演示用的当前动画；连按演示按钮前先取消上一轮，免得多轮回调互相盖值。 */
    private var demoAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF083Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f083ModeNormal.setOnClickListener { select(MotionMode.NORMAL) }
        binding.f083ModeReduced.setOnClickListener { select(MotionMode.REDUCED) }
        binding.f083DemoButton.setOnClickListener { playDemo() }

        // 每次打开页面都按"现在实际生效的档位"重画一遍：上一次的选择（或系统设置）要能看见
        render()
    }

    override fun onDestroy() {
        demoAnimator?.cancel()
        demoAnimator = null
        super.onDestroy()
    }

    // ===== 档位 =====

    /**
     * 切换档位并落盘，然后整页重画。
     *
     * 点了同一档也照样写一次：写是幂等的 apply()，代价可以忽略，
     * 换来的是"用户明确选过"这一事实被记下来（此后不再跟随系统建议）。
     */
    private fun select(mode: MotionMode) {
        F083Engine.write(this, mode)
        demoAnimator?.cancel()
        resetDemoBlock()
        render()
        CustomToast.show(this, getString(R.string.f083_toast_switched, getString(F083Engine.labelRes(mode))))
    }

    /** 把当前生效档位画到界面上：两行的选中标记、状态行、演示时长。 */
    private fun render() {
        val mode = F083Engine.effective(this)
        val animating = F083Engine.shouldAnimate(mode)

        renderModeRow(
            row = binding.f083ModeNormal,
            label = binding.f083NormalLabel,
            check = binding.f083NormalCheck,
            selected = mode == MotionMode.NORMAL
        )
        renderModeRow(
            row = binding.f083ModeReduced,
            label = binding.f083ReducedLabel,
            check = binding.f083ReducedCheck,
            selected = mode == MotionMode.REDUCED
        )

        binding.f083Summary.text = F083Engine.summary(this, mode)
        binding.f083Status.text = getString(
            R.string.f083_status_mode,
            getString(F083Engine.labelRes(mode)),
            F083Engine.demoDurationMs(mode).toInt(),
            if (animating) getString(R.string.f083_switch_on) else getString(R.string.f083_switch_off)
        )
        binding.f083Status.setTextColor(
            color(if (animating) R.color.theme_primary else R.color.theme_text_secondary)
        )
        binding.f083DemoDuration.text =
            getString(R.string.f083_demo_duration, F083Engine.demoDurationMs(mode).toInt())
    }

    /**
     * 一行档位：选中时行底色换成选中态、文字主题色，右侧「当前」标记显示出来。
     *
     * 用底色 + 文字颜色双通道表达选中，而不是只让一个勾出现：
     * 电视上坐得远，单靠一个小勾看不清；底色变化在全屏亮度下也认得出。
     */
    private fun renderModeRow(
        row: LinearLayout,
        label: TextView,
        check: TextView,
        selected: Boolean
    ) {
        row.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_card_surface
        )
        label.setTextColor(color(if (selected) R.color.theme_primary else R.color.theme_text_primary))
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    // ===== 演示 =====

    /**
     * 播放一次转场演示。
     *
     * - 减弱档：方块直接到最终态，不启动画，状态行写明"瞬时完成"；
     * - 标准档：0.92 → 1.0 + 淡入，时长取 [F083Engine.demoDurationMs]，
     *   与页面顶部显示的数字是同一个函数算出来的。
     *
     * 先复位再播，否则第二次点下去是从 1.0 缩放到 1.0，看起来像"没反应"。
     */
    private fun playDemo() {
        demoAnimator?.cancel()
        val mode = F083Engine.effective(this)
        val durationMs = F083Engine.demoDurationMs(mode)

        resetDemoBlock()

        if (!F083Engine.shouldAnimate(mode) || durationMs <= 0L) {
            // 减弱档：没有任何过渡，直接落到终态
            binding.f083DemoBlock.scaleX = 1f
            binding.f083DemoBlock.scaleY = 1f
            binding.f083DemoBlock.alpha = 1f
            binding.f083Status.text = getString(R.string.f083_status_demo_instant)
            binding.f083Status.setTextColor(color(R.color.theme_text_secondary))
            return
        }

        val from = F083Engine.scale(mode)
        // 起点先压到动画的第一帧，免得 reset 的可见态与第一帧之间闪一下
        binding.f083DemoBlock.scaleX = from
        binding.f083DemoBlock.scaleY = from
        binding.f083DemoBlock.alpha = 0f
        // cancel() 与正常播完都会进 onAnimationEnd：用这个标记区分，取消时不要回写"演示完成"
        var cancelled = false
        val animator = ValueAnimator.ofFloat(from, 1f).apply {
            this.duration = durationMs
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                binding.f083DemoBlock.scaleX = value
                binding.f083DemoBlock.scaleY = value
                // 缩放越小越透明，两段进度都是线性的，看起来是同一次"进场"
                binding.f083DemoBlock.alpha = ((value - from) / (1f - from)).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    binding.f083Status.text =
                        getString(R.string.f083_status_demo_running, durationMs.toInt())
                    binding.f083Status.setTextColor(color(R.color.theme_primary))
                }

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // 取消（连按演示 / 切档 / 页面销毁）时不动视图，下一轮会自己复位
                    if (cancelled) return
                    binding.f083DemoBlock.scaleX = 1f
                    binding.f083DemoBlock.scaleY = 1f
                    binding.f083DemoBlock.alpha = 1f
                    binding.f083Status.text =
                        getString(R.string.f083_status_demo_done, durationMs.toInt())
                    binding.f083Status.setTextColor(color(R.color.theme_success))
                }
            })
        }
        demoAnimator = animator
        animator.start()
    }

    /** 演示方块复位到静止态：缩放取当前档位的起点，透明度回到 1（静止时方块要看得见）。 */
    private fun resetDemoBlock() {
        val mode = F083Engine.effective(this)
        val from = F083Engine.scale(mode)
        binding.f083DemoBlock.scaleX = from
        binding.f083DemoBlock.scaleY = from
        binding.f083DemoBlock.alpha = 1f
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
}
