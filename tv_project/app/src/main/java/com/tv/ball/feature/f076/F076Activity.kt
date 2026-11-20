package com.tv.ball.feature.f076

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF076Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 「存储占用分析」页。
 *
 * 两段：总览（五类合计 + 一条总占比条 + 重新统计）与分类占用（每类一行：名字 / 大小 / 真实路径 /
 * 占比条 / 百分比 / 「去清理」）。
 *
 * 数据流：进页面就统计一次 → [F076Engine.analyze] 在 [Dispatchers.IO] 上真实递归五个目录 →
 * 结果回到主线程渲染。占比条不用第三方图表库：顶部总条是一个横向 LinearLayout，
 * 每段的宽度由 `layout_weight` 按字节比例分配；每行细分条同理，两段 View（填充 + 留白）就够。
 * 没有用 ProgressBar 的原因是它的 `max` 是 Int，几个 GB 的目录按字节算会溢出。
 *
 * 「去清理」只把这一类的清理入口在哪一页说出来（[Bucket.actionHint]），**不删任何文件**：
 * 删除不可逆，应当由对应功能页在用户明确操作后执行，统计页只负责让人看清"该去哪一页"。
 *
 * 三个状态：统计中（「重新统计」变灰）、有数据（合计 + 条 + 五行）、全空（合计 0，条留空，
 * 状态行说明五类目录都没东西）。
 */
class F076Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF076Binding

    /** 当前这一轮统计；重扫前先取消旧的，避免两轮结果前后脚盖上去。 */
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF076Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f076RefreshButton.setOnClickListener { refresh() }

        renderIdle()
        // 进页面先量一次：用户点进来就是要看数字，不该再点一下才给
        refresh()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 统计 =====

    /**
     * 跑一轮统计。
     *
     * 目录递归放在 IO 线程，主线程只等结果；已在统计中时不重入，
     * 免得用户连按确认键堆出几轮并发的目录遍历。
     */
    private fun refresh() {
        if (job?.isActive == true) return
        setBusy(true)
        setStatus(getString(R.string.f076_status_loading), R.color.theme_tertiary)

        job = lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val buckets = withContext(Dispatchers.IO) {
                    F076Engine.analyze(this@F076Activity)
                }
                if (isFinishing || isDestroyed) return@launch
                renderBuckets(buckets, System.currentTimeMillis() - startedAt)
            } catch (cancel: CancellationException) {
                // 取消是用户动作（返回键 / 页面销毁），别吞掉：scope 还要靠它收子任务
                throw cancel
            } catch (e: Exception) {
                setStatus(
                    getString(R.string.f076_status_error, e.message ?: e.javaClass.simpleName),
                    R.color.theme_error
                )
            } finally {
                setBusy(false)
            }
        }
    }

    // ===== 渲染 =====

    /** 还没开始统计时的样子：合计一个破折号，条和列表都空着。 */
    private fun renderIdle() {
        binding.f076TotalValue.text = getString(R.string.f076_total_empty)
        binding.f076UsageBar.removeAllViews()
        binding.f076BucketList.removeAllViews()
        setStatus(getString(R.string.f076_status_idle), R.color.theme_text_secondary)
    }

    /**
     * 把一轮结果画出来：合计、总占比条、五个分类行、状态行。
     *
     * 合计取 [F076Engine.total]，与五行的 bytes 同源，所以「逐行相加」一定等于顶部那个数字。
     */
    private fun renderBuckets(buckets: List<Bucket>, elapsedMs: Long) {
        val total = F076Engine.total(buckets)
        binding.f076TotalValue.text = F076Engine.formatBytes(total)
        renderUsageBar(buckets, total)

        binding.f076BucketList.removeAllViews()
        buckets.forEachIndexed { index, bucket -> addBucketRow(bucket, total, index) }

        if (total <= 0L) {
            setStatus(getString(R.string.f076_status_empty), R.color.theme_warning)
        } else {
            setStatus(
                getString(R.string.f076_status_done, buckets.size, elapsedMs.toInt()),
                R.color.theme_success
            )
        }
    }

    /**
     * 顶部那条总占比条：五段，宽度按 layout_weight 分配。
     *
     * 段颜色按 [BUCKET_COLORS] 的顺序取，与下面每一行同色——
     * 不看文字也知道哪一块是缓存、哪一块是数据库。
     * 全为 0 时一段都不加，容器自己的 surface_inset 底色就是"空条"。
     */
    private fun renderUsageBar(buckets: List<Bucket>, total: Long) {
        val bar = binding.f076UsageBar
        bar.removeAllViews()
        buckets.forEachIndexed { index, bucket ->
            val weight = F076Engine.share(bucket.bytes, total)
            if (weight <= 0f) return@forEachIndexed
            val segment = View(this)
            segment.setBackgroundColor(color(BUCKET_COLORS[index % BUCKET_COLORS.size]))
            bar.addView(
                segment,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            )
        }
    }

    /** 一行分类：名字 + 大小 / 真实路径 / 占比条 / 百分比 +「去清理」。 */
    private fun addBucketRow(bucket: Bucket, total: Long, index: Int) {
        val accent = color(BUCKET_COLORS[index % BUCKET_COLORS.size])

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@F076Activity, R.drawable.bg_card_surface)
        }
        row.addView(headRow(bucket, accent))
        row.addView(pathRow(bucket))
        row.addView(shareBar(bucket.bytes, total, accent))
        row.addView(footerRow(bucket, total))

        binding.f076BucketList.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }

    /** 第一行：左边名字，右边大小（等宽字体，五行的数字才对得齐）。 */
    private fun headRow(bucket: Bucket, accent: Int): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(this).apply {
                text = bucket.name
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )
        head.addView(
            TextView(this).apply {
                text = bucket.sizeText()
                setTextColor(if (bucket.bytes > 0L) accent else color(R.color.theme_text_secondary))
                typeface = Typeface.MONOSPACE
                textSize = 14f
            }
        )
        return head
    }

    /** 第二行：真实路径，说明这个数字是从哪个目录量出来的。 */
    private fun pathRow(bucket: Bucket): TextView = TextView(this).apply {
        text = bucket.pathDesc
        setTextColor(color(R.color.theme_text_secondary))
        textSize = 11f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    /**
     * 这一类的占比条：一段填充 + 一段留白，宽度用 layout_weight 按字节比例分配。
     *
     * 再小的一块也给 [MIN_VISIBLE_SHARE] 的宽度，否则"只有几十 KB"的目录在条上完全看不见，
     * 用户会以为那一类没量到。
     */
    private fun shareBar(bytes: Long, total: Long, accent: Int): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.surface_inset))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { topMargin = dp(8) }
        }
        val share = F076Engine.share(bytes, total)
        if (share <= 0f) return bar

        val filled = share.coerceAtLeast(MIN_VISIBLE_SHARE).coerceAtMost(1f)
        bar.addView(
            View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, filled)
        )
        bar.addView(
            View(this),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1f - filled).coerceAtLeast(0f)
            )
        )
        return bar
    }

    /** 第四行：左边百分比，右边「去清理」。 */
    private fun footerRow(bucket: Bucket, total: Long): LinearLayout {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        footer.addView(
            TextView(this).apply {
                text = getString(R.string.f076_percent, F076Engine.percent(bucket.bytes, total))
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )
        footer.addView(cleanButton(bucket))
        return footer
    }

    /**
     * 「去清理」按钮。
     *
     * 只提示入口，不执行删除（[Bucket.actionHint] 说的是"哪一页能清"）：
     * 状态行给整句说明，Toast 给一句短提示，两条都指向同一个结论——
     * 真正删东西的动作留在对应功能页，由用户在看得见上下文的地方自己按下去。
     */
    private fun cleanButton(bucket: Bucket): TextView = TextView(this).apply {
        text = getString(R.string.f076_action_clean)
        setTextColor(color(R.color.theme_primary))
        textSize = 13f
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(this@F076Activity, R.drawable.bg_secondary_button)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener {
            setStatus(bucket.actionHint, R.color.theme_primary)
            CustomToast.show(
                this@F076Activity,
                getString(R.string.f076_toast_hint, bucket.name)
            )
        }
    }

    // ===== 状态 =====

    /** 统计中把「重新统计」按住：连按会堆出多轮目录遍历。 */
    private fun setBusy(busy: Boolean) {
        binding.f076RefreshButton.isEnabled = !busy
        binding.f076RefreshButton.isFocusable = !busy
        binding.f076RefreshButton.alpha = if (busy) 0.4f else 1f
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f076Status.text = message
        binding.f076Status.setTextColor(color(colorRes))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}

/**
 * 五段占比条的颜色，下标与 [F076Engine.analyze] 的返回顺序一一对应：
 * 缓存 / 应用文件 / 数据库 / 下载 / 缩略图。
 */
private val BUCKET_COLORS = intArrayOf(
    R.color.theme_primary,
    R.color.theme_tertiary,
    R.color.theme_warning,
    R.color.theme_success,
    R.color.theme_secondary
)

/** 占比条里最小的可见宽度：2%。比这还窄的类目在条上就看不见了。 */
private const val MIN_VISIBLE_SHARE = 0.02f
