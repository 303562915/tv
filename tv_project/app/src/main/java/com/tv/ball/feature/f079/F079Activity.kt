package com.tv.ball.feature.f079

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF079Binding
import kotlin.math.roundToInt

/**
 * 「首启动引导」页。
 *
 * 一屏五页：无账号 → 只读权限 → 遥控器手势 → 功能中心 → 数据仅本机。
 * 页面内容全部来自 [F079Engine.pages]，排版是同一套模板，换的只有图、标题与正文——
 * 所以这里不写五个 layout，只写一个 `activity_f079.xml`，翻页就是换三行文本。
 *
 * 三种收尾方式，落点都不一样：
 *   · 最后一页点「开始使用」→ [OnboardingState.markDone]，finish；
 *   · 任何时候点「跳过」→ [OnboardingState.markSkipped]（同样不再自动弹），finish；
 *   · 按返回键 → 当作跳过。原因很直白：用户已经明确不想看了，若退出而不置位，
 *     下次启动还会弹，正是验收里要挡掉的「反复弹出」。
 *
 * 「重新查看引导」按钮：先 [OnboardingState.reset] 把状态清回出厂，再就地把页面拉回第一页——
 * 同一次点击里能同时看到「可以重来」与「重来长什么样」，不用退出再进。这里**不**自动 finish，
 * 因为本页本身就是从功能中心手动打开的，用户要看的就是引导，而不是回到上一个页面。
 */
class F079Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF079Binding

    /** 翻页游标：页码、前后可点性都从它读。 */
    private val pager = F079Pager()

    /** 页码圆点，数量跟着页数走，进页面时一次性建好。 */
    private val dots = mutableListOf<View>()

    /** 进页面时读一次：状态行要说明「上次是看完还是跳过」，翻页过程中它不会变。 */
    private var skippedBefore: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF079Binding.inflate(layoutInflater)
        setContentView(binding.root)

        skippedBefore = OnboardingState.isSkipped(this)

        buildDots()

        binding.btnBack.setOnClickListener { finishWithSkip() }
        binding.f079SkipButton.setOnClickListener { finishWithSkip() }
        binding.f079PrevButton.setOnClickListener {
            pager.prev()
            render()
        }
        binding.f079NextButton.setOnClickListener {
            if (pager.isLast) {
                finishWithDone()
            } else {
                pager.next()
                render()
            }
        }
        binding.f079ResetButton.setOnClickListener { onResetClicked() }

        // 焦点落点：先给「下一条」，遥控器按一下就能翻页。
        render()
        binding.f079NextButton.requestFocus()
    }

    // ===== 收尾 =====

    /** 看完 / 直接走完最后一步。 */
    private fun finishWithDone() {
        OnboardingState.markDone(this)
        finish()
    }

    /** 跳过（按钮或返回键）：同样置 done，只是把「跳过」标记留下。 */
    private fun finishWithSkip() {
        OnboardingState.markSkipped(this)
        finish()
    }

    /**
     * 重新查看：清状态 + 回到第一页，并把状态行说明改成「已重置」。
     *
     * 注意顺序——先 reset 再 render：render 的状态行会读到「未完成」，若反过来，
     * 画出来的还是重置前的旧文案。
     */
    private fun onResetClicked() {
        OnboardingState.reset(this)
        skippedBefore = false
        pager.reset()
        render()
        binding.f079Status.text = getString(R.string.f079_status_reset)
        binding.f079NextButton.requestFocus()
    }

    // ===== 渲染 =====

    /**
     * 按 [pager.currentIndex] 重画：图标、标题、正文、页码、圆点、按钮文案与可点性、状态行。
     *
     * 每次都全量重画而不做增量：一屏只有三个文本和一个图标，全量的成本比维护
     * 「哪次只变了哪一块」的判断低得多。
     */
    private fun render() {
        val page = F079Engine.page(pager.currentIndex)

        binding.f079Icon.setImageResource(
            resolve(page.iconKey, "drawable", R.drawable.ic_nav_profile)
        )
        binding.f079PageTitle.text =
            getString(resolve(page.titleKey, "string", R.string.f079_title))
        binding.f079PageBody.text =
            getString(resolve(page.bodyKey, "string", R.string.f079_desc))

        binding.f079PageIndex.text =
            getString(R.string.f079_page_index, pager.currentIndex + 1, F079Engine.PAGE_COUNT)

        paintDots()

        // 第一页没有「上一条」可回，按钮留在原地但置灰不可点，避免焦点跳出一个「已经没了」的按钮。
        binding.f079PrevButton.isEnabled = !pager.isFirst
        binding.f079PrevButton.alpha = if (pager.isFirst) ALPHA_DISABLED else 1f
        // 最后一页把「下一条」变成「开始使用」：同一个按钮，用户不用挪手。
        binding.f079NextButton.setText(
            if (pager.isLast) R.string.f079_action_start else R.string.f079_action_next
        )

        // 最后一页不再提示「可跳过」——已经能开始使用了，这时再劝退没意义。
        binding.f079SkipButton.visibility =
            if (pager.isLast) View.INVISIBLE else View.VISIBLE

        binding.f079Status.text = statusText()
        binding.f079Status.setTextColor(color(R.color.theme_text_secondary))
    }

    /**
     * 状态行三种：
     *   · 本次从功能中心打开且状态已过完 → 说明「再看一遍不会影响已完成的判断」；
     *   · 上次是跳过的 → 说清这次是补看；
     *   · 首次 → 说清五页大概讲什么。
     *
     * 刻意不在这里写「第几页 / 共几页」以外的进度百分比：小圆点已经把进度画出来了。
     */
    private fun statusText(): String = when {
        !OnboardingState.shouldShow(this) && skippedBefore ->
            getString(R.string.f079_status_skipped_before)
        !OnboardingState.shouldShow(this) ->
            getString(R.string.f079_status_done_before)
        else ->
            getString(R.string.f079_status_first, F079Engine.PAGE_COUNT)
    }

    /** 建立圆点：数量 = [F079Engine.PAGE_COUNT]，宽度按页数均分到圆点行里。 */
    private fun buildDots() {
        binding.f079Dots.removeAllViews()
        dots.clear()
        repeat(F079Engine.PAGE_COUNT) { index ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(DOT_SIZE), dp(DOT_SIZE)).apply {
                    marginStart = dp(DOT_GAP)
                    marginEnd = dp(DOT_GAP)
                }
                isFocusable = false
                isClickable = false
            }
            binding.f079Dots.addView(dot)
            dots += dot
        }
    }

    /**
     * 画圆点：当前页是主色实心圆，其余是 inset 灰圆。
     *
     * 除了颜色，还给当前圆点加一点缩放——在低对比度的暗色主题下，仅靠颜色分辨「我在第几页」
     * 对远处的电视屏不够。
     */
    private fun paintDots() {
        dots.forEachIndexed { index, dot ->
            val current = index == pager.currentIndex
            dot.background = ContextCompat.getDrawable(
                this,
                if (current) R.drawable.bg_dot_primary else R.drawable.bg_dot_card
            )
            dot.scaleX = if (current) SCALE_CURRENT else 1f
            dot.scaleY = if (current) SCALE_CURRENT else 1f
            dot.contentDescription = getString(
                if (current) R.string.f079_dot_a11y_current else R.string.f079_dot_a11y,
                index + 1,
                F079Engine.PAGE_COUNT
            )
        }
    }

    // ===== 工具 =====

    /**
     * 把资源名换成资源 id。
     *
     * 找不到时回落到 [fallback]：引导页是为了让用户看懂 App，不该因为某个图标改名就白屏。
     * 用 `getIdentifier` 而不是 `R.xxx`，是因为 [Page] 里存的是字符串——这样 Engine 保持
     * 纯 JVM 可测，代价只是每帧多一次名字查找，而这里一屏只有三个资源。
     */
    private fun resolve(name: String, kind: String, fallback: Int): Int {
        val id = resources.getIdentifier(name, kind, packageName)
        return if (id != 0) id else fallback
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    private companion object {
        /** 圆点直径。 */
        const val DOT_SIZE = 8

        /** 圆点间距。 */
        const val DOT_GAP = 4

        /** 当前页圆点的放大倍数。 */
        const val SCALE_CURRENT = 1.25f

        /** 不可点按钮的不透明度。 */
        const val ALPHA_DISABLED = 0.38f
    }
}