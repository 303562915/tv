package com.tv.ball.feature.f090

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF090Binding
import com.tv.ball.util.CustomToast

/**
 * 「骨架屏与空态组件」页。
 *
 * 这一页是组件的**活样例**，不是业务页：四个按钮对应 [ListState] 的四种状态，
 * 点一下就直接切过去，方便对着看骨架屏、空态、错误态、内容态长什么样、怎么切。
 *
 * 状态判定全部走 [F090Engine.decide]，页面里没有一处 `if (list.isEmpty())`：
 * 页面只做两件事——**把状态翻成资源**、**把视图显示/隐藏**。
 * 之所以坚持这样，是因为一旦页面自己判状态，骨态和空态就会在不同页面里
 * 出现不一样的优先级（典型的 bug：请求失败且列表为空时先闪一下"还没有内容"）。
 *
 * 骨架屏的呼吸动画：5 个占位块各自一个 [ObjectAnimator]，动的是 `alpha`（0.4 ↔ 1.0），
 * 每块错开 90ms 起播，看上去像一条从左往右的微光。这里不用 View 的
 * `animation`/`postDelayed` 递归：`ObjectAnimator` 能被 `cancel()` 一次收干净，
 * 页面切走再切回来不会留下跑在已销毁视图上的动画。
 *
 * 加载态 1.5 秒后自动落到内容态（[F090Engine.LOADING_HOLD_MS]）：真实列表由请求回调推动，
 * 演示页没有网络，就用一个定时器把"骨架屏 → 内容"这条路走完。
 */
class F090Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF090Binding

    /** 当前状态；初值给 LOADING，与 [onCreate] 里第一帧演示一致。 */
    private var state: ListState = ListState.LOADING

    /** 当前这一轮的数据条数，喂给 [F090Engine.decide]。 */
    private var itemCount: Int = DEMO_ITEM_COUNT

    /** 当前这一轮的失败原因；null / 空串表示没有错误。 */
    private var error: String? = null

    /** 正在跑的呼吸动画，一次收全部，别留野动画。 */
    private val breathing = mutableListOf<ObjectAnimator>()

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 加载态的收尾：1.5 秒后按"请求成功、有数据"重新判一次状态。
     *
     * 走 [F090Engine.decide] 而不是直接 `applyState(CONTENT)`：
     * 这样即便将来这里接了真实请求（可能超时 / 可能空），状态判定也还是同一处。
     */
    private val settleToContent = Runnable {
        if (state == ListState.LOADING) {
            applyState(F090Engine.decide(itemCount, isLoading = false, error = error))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF090Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f090BtnLoading.setOnClickListener { demoLoading() }
        binding.f090BtnEmpty.setOnClickListener { demoEmpty() }
        binding.f090BtnError.setOnClickListener { demoError() }
        binding.f090BtnContent.setOnClickListener { demoContent() }

        // 空态/错误态面板上的那个按钮：两种状态都回到骨架屏，再重新判一次，行为统一
        binding.f090StateAction.setOnClickListener { demoLoading() }

        // 进来先演一轮完整生命周期：骨架屏 → 1.5 秒 → 内容
        demoLoading()
        binding.f090BtnLoading.requestFocus()
    }

    // ===== 四个演示入口 =====

    /**
     * 演示加载态：清掉上一轮的错误与条数，让 [F090Engine.decide] 判出 LOADING，
     * 然后定时收尾。清空是必要的——否则上一次演示的 error 会留在字段里，
     * 定时器收尾时判出来的就是 ERROR，看着像"骨架屏自己失败了"。
     */
    private fun demoLoading() {
        itemCount = DEMO_ITEM_COUNT
        error = null
        applyState(F090Engine.decide(itemCount = 0, isLoading = true, error = null))
        scheduleSettle()
    }

    /** 演示空态：请求成功但一条都没有。 */
    private fun demoEmpty() {
        itemCount = 0
        error = null
        applyState(F090Engine.decide(itemCount = 0, isLoading = false, error = null))
    }

    /** 演示错误态：请求失败，列表为空。 */
    private fun demoError() {
        itemCount = 0
        error = getString(R.string.f090_demo_error)
        applyState(F090Engine.decide(itemCount = 0, isLoading = false, error = error))
    }

    /** 演示内容态：请求成功且有数据。 */
    private fun demoContent() {
        itemCount = DEMO_ITEM_COUNT
        error = null
        applyState(F090Engine.decide(itemCount, isLoading = false, error = null))
    }

    /** 排一次加载收尾；重复点加载态不会叠出两个定时器。 */
    private fun scheduleSettle() {
        handler.removeCallbacks(settleToContent)
        handler.postDelayed(settleToContent, F090Engine.LOADING_HOLD_MS)
    }

    /**
     * 落到一个新状态：先掐掉所有还没触发的定时器，再记状态、重画。
     *
     * 掐定时器放在最前面：从加载态切到别的状态时，那个"1.5 秒后变内容"的尾巴
     * 如果还在队列里，就会在用户已经切到错误态之后突然把页面翻成内容态。
     */
    private fun applyState(next: ListState) {
        handler.removeCallbacks(settleToContent)
        val changed = next != state
        state = next
        render()
        if (changed) {
            CustomToast.show(this, getString(R.string.f090_toast_state, getString(labelResOf(next))))
        }
    }

    // ===== 渲染 =====

    /**
     * 状态 → 视图。
     *
     * 三块视图互斥：骨架屏、图标面板（空态 / 错误态共用）、内容块。
     * 文案统一从 [F090Engine.copyKey] 拿键再翻资源，页面里不写任何一句中文。
     */
    private fun render() {
        val key = F090Engine.copyKey(state)

        binding.f090StateLabel.text = getString(
            R.string.f090_state_now,
            getString(labelResOf(state)),
            key
        )

        // 骨架屏
        val skeleton = F090Engine.showsSkeleton(state)
        binding.f090Skeleton.visibility = if (skeleton) View.VISIBLE else View.GONE
        if (skeleton) {
            binding.f090SkeletonTitle.text = getString(copyTitleResOf(key))
            binding.f090SkeletonDesc.text = getString(copyBodyResOf(key))
            startBreathing()
        } else {
            stopBreathing()
        }

        // 空态 / 错误态：同一块面板，换图标、换文案、换主色
        val panel = F090Engine.usesIconPanel(state)
        binding.f090StatePanel.visibility = if (panel) View.VISIBLE else View.GONE
        if (panel) {
            renderPanel(key)
        }

        // 内容态
        val content = F090Engine.showsContent(state)
        binding.f090ContentPanel.visibility = if (content) View.VISIBLE else View.GONE
        if (content) {
            binding.f090ContentTitle.text = getString(copyTitleResOf(key))
            binding.f090ContentText.text = getString(copyBodyResOf(key), itemCount)
        }

        highlightSwitches()
    }

    /**
     * 空态 / 错误态的图标 + 主副文案 + 动作按钮。
     *
     * 错误态的副文案把 [error] 原文拼进去：用户拿这句话去跟客服/论坛描述问题，
     * 比"加载失败"有用得多。空态则是一句"换个条件试试"，指向用户可以做的动作。
     */
    private fun renderPanel(key: String) {
        val failed = state == ListState.ERROR

        binding.f090StateIcon.setImageResource(
            if (failed) R.drawable.ic_error else R.drawable.ic_placeholder
        )
        binding.f090StateIcon.imageTintList = ColorStateList.valueOf(
            color(if (failed) R.color.theme_error else R.color.theme_text_secondary)
        )

        binding.f090StateTitle.text = getString(copyTitleResOf(key))
        binding.f090StateTitle.setTextColor(
            color(if (failed) R.color.theme_error else R.color.theme_text_primary)
        )

        binding.f090StateDesc.text = if (failed) {
            getString(
                copyBodyResOf(key),
                error?.takeIf { it.isNotBlank() } ?: getString(R.string.f090_error_unknown)
            )
        } else {
            getString(copyBodyResOf(key))
        }

        binding.f090StateAction.text = getString(
            if (failed) R.string.f090_action_retry else R.string.f090_action_reload
        )
        binding.f090StateAction.setTextColor(color(R.color.theme_primary))
    }

    /** 当前状态对应的按钮高亮成主题色，其余回正文色。 */
    private fun highlightSwitches() {
        setSwitchActive(binding.f090BtnLoading, state == ListState.LOADING)
        setSwitchActive(binding.f090BtnEmpty, state == ListState.EMPTY)
        setSwitchActive(binding.f090BtnError, state == ListState.ERROR)
        setSwitchActive(binding.f090BtnContent, state == ListState.CONTENT)
    }

    private fun setSwitchActive(view: TextView, active: Boolean) {
        view.setTextColor(
            color(if (active) R.color.theme_primary else R.color.theme_text_primary)
        )
    }

    // ===== 骨架屏呼吸动画 =====

    /**
     * 5 个占位块的 alpha 呼吸：0.4 → 1.0 → 0.4，无限循环，每块错开 90ms。
     *
     * 反复调用是安全的（已经在跑就直接返回）：`render` 在每次切状态时都会调它，
     * 没有这个判断，同一块占位块上会叠出好几层动画，alpha 互相打架，看着反而像卡顿。
     */
    private fun startBreathing() {
        if (breathing.isNotEmpty()) return
        blocks().forEachIndexed { index, block ->
            block.alpha = BREATH_MIN_ALPHA
            val animator = ObjectAnimator.ofFloat(block, View.ALPHA, BREATH_MIN_ALPHA, 1f)
            animator.duration = BREATH_DURATION_MS
            animator.startDelay = index * BREATH_STAGGER_MS
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE
            animator.interpolator = AccelerateDecelerateInterpolator()
            animator.start()
            breathing += animator
        }
    }

    /** 停掉呼吸动画，并把 alpha 复位——否则占位块会停在半透明上，切回来像没刷新。 */
    private fun stopBreathing() {
        breathing.forEach { it.cancel() }
        breathing.clear()
        blocks().forEach { it.alpha = 1f }
    }

    /** 5 个占位块按 XML 顺序排好；个数与 [F090Engine.SKELETON_BLOCK_COUNT] 对账。 */
    private fun blocks(): List<View> = listOf(
        binding.f090SkeletonBlock1,
        binding.f090SkeletonBlock2,
        binding.f090SkeletonBlock3,
        binding.f090SkeletonBlock4,
        binding.f090SkeletonBlock5
    )

    // ===== 生命周期 =====

    /**
     * 页面看不见时把所有动画和定时器停掉：电视盒子上退到后台的页面仍占着
     * 60fps 的动画线程，白耗电、还会跟播放器抢帧。
     */
    override fun onPause() {
        super.onPause()
        stopBreathing()
        handler.removeCallbacks(settleToContent)
    }

    /**
     * 回前台：骨架屏还在的话把呼吸动画接上，并把没走完的那次收尾重新排一遍
     * （[onPause] 顺手把定时器掐了，这里不重排的话，用户切走再回来会永远停在骨架屏）。
     */
    override fun onResume() {
        super.onResume()
        if (F090Engine.showsSkeleton(state)) {
            startBreathing()
            scheduleSettle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(settleToContent)
        stopBreathing()
    }

    // ===== 资源映射（键 → R.string） =====

    /** 状态名标签。 */
    private fun labelResOf(state: ListState): Int = when (F090Engine.labelKey(state)) {
        LABEL_LOADING -> R.string.f090_state_loading
        LABEL_EMPTY -> R.string.f090_state_empty
        LABEL_ERROR -> R.string.f090_state_error
        else -> R.string.f090_state_content
    }

    /** 主文案：一段话里最上面那句。 */
    private fun copyTitleResOf(key: String): Int = when (key) {
        F090Engine.KEY_LOADING -> R.string.f090_copy_loading_title
        F090Engine.KEY_EMPTY -> R.string.f090_copy_empty_title
        F090Engine.KEY_ERROR -> R.string.f090_copy_error_title
        else -> R.string.f090_copy_content_title
    }

    /** 副文案：主文案下面那句解释。 */
    private fun copyBodyResOf(key: String): Int = when (key) {
        F090Engine.KEY_LOADING -> R.string.f090_copy_loading_desc
        F090Engine.KEY_EMPTY -> R.string.f090_copy_empty_desc
        F090Engine.KEY_ERROR -> R.string.f090_copy_error_desc
        else -> R.string.f090_copy_content_desc
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private companion object {
        /** 演示用条数：够看出"有内容"，又不至于真的排一屏假数据。 */
        const val DEMO_ITEM_COUNT = 12

        /** 呼吸动画的最低不透明度；太高看不出呼吸，太低像占位块坏了。 */
        const val BREATH_MIN_ALPHA = 0.4f
        const val BREATH_DURATION_MS = 640L
        const val BREATH_STAGGER_MS = 90L

        /** 状态名资源键的副本，只用于 [labelResOf] 的分支匹配。 */
        const val LABEL_LOADING = "f090_state_loading"
        const val LABEL_EMPTY = "f090_state_empty"
        const val LABEL_ERROR = "f090_state_error"
    }
}
