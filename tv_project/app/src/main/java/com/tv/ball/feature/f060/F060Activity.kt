package com.tv.ball.feature.f060

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF060Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 「地址连通性预检」页。
 *
 * 三段：输入（播放地址 + 示例地址）、动作（预检 / 重新预检）、
 * 结果（可达性 / 状态码 / 耗时 / 是否命中缓存 / 错误原因）。
 *
 * 数据流：点「预检」→ 先问引擎有没有 5 分钟内的缓存结论（[F060Engine.cached]），
 * 有就直接渲染并标注"命中缓存"；没有就把输入框内容交给 [F060Engine.preflight]，
 * 在 Dispatchers.IO 上跑完回主线程渲染。点「重新预检」走 `force = true`，
 * 跳过缓存强制发请求并把新结论写回缓存——"刚才超时的地址现在通了吗"这类复测才有意义。
 *
 * 命中缓存时状态行用警告色写明几分钟前的结论，「重新预检」那一行的提示改成主题色，
 * 保证用户看到旧结论时手上始终有一个显式的刷新入口。
 *
 * 电视没有触摸屏：返回、地址输入、示例块、两个动作行都可聚焦，
 * 焦点顺序在 XML 里串成 返回 → 地址输入 → 示例 → 预检 → 重新预检。
 */
class F060Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF060Binding

    /** 引擎实例随页面存活：5 分钟缓存挂在引擎上，页面内多次预检才能互相复用。 */
    private val engine by lazy { F060Engine() }

    /** 当前在跑的预检任务；重复点击先取消旧的，避免两次结果互相覆盖。 */
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF060Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f060UrlInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        buildSamples()

        binding.f060CheckButton.setOnClickListener { startPreflight(force = false) }
        binding.f060RecheckButton.setOnClickListener { startPreflight(force = true) }
        // 遥控器用户在输入框里按确认 = 预检，省一次往下走焦点的按键
        binding.f060UrlInput.setOnEditorActionListener { _, _, _ ->
            startPreflight(force = false)
            true
        }

        renderIdle()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 示例地址 =====

    /**
     * 示例地址：一排可聚焦的块，点一下填入输入框。
     *
     * 清单来自 [F060Engine.SAMPLES]，XML 里只留空容器——哪些地址有代表性属于引擎的认知，
     * 界面上不该再抄一份。
     */
    private fun buildSamples() {
        binding.f060SampleRow.removeAllViews()
        F060Engine.SAMPLES.forEachIndexed { index, (label, url) ->
            binding.f060SampleRow.addView(sampleChip(label, url).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { if (index > 0) marginStart = dp(8) }
            })
        }
    }

    private fun sampleChip(label: String, url: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        minHeight = dp(40)
        setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
        background = ContextCompat.getDrawable(context, R.drawable.bg_card_surface)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener {
            binding.f060UrlInput.setText(url)
            binding.f060UrlInput.setSelection(url.length)
        }
    }

    // ===== 预检 =====

    /**
     * 发起预检。
     *
     * @param force true = 点的是「重新预检」，跳过 5 分钟缓存强制发请求
     */
    private fun startPreflight(force: Boolean) {
        val url = binding.f060UrlInput.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            CustomToast.show(this, getString(R.string.f060_url_empty))
            return
        }
        job?.cancel()

        // 非强制时先看缓存：命中就不发请求，界面照常渲染并标注来源
        if (!force) {
            val hit = engine.cached(url)
            if (hit != null) {
                setBusy(false)
                renderResult(hit, fromCache = true)
                return
            }
        }

        setBusy(true)
        renderBusy(url, force)
        job = lifecycleScope.launch {
            // 网络是阻塞调用，一律挪去 IO
            val result = withContext(Dispatchers.IO) { engine.preflight(url, force) }
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            renderResult(result, fromCache = false)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.f060CheckButton.isEnabled = !busy
        binding.f060RecheckButton.isEnabled = !busy
        val alpha = if (busy) 0.5f else 1f
        binding.f060CheckButton.alpha = alpha
        binding.f060RecheckButton.alpha = alpha
    }

    // ===== 渲染 =====

    private fun renderIdle() {
        val none = getString(R.string.f060_value_none)
        binding.f060ResultVerdict.text = none
        binding.f060ResultVerdict.setTextColor(
            ContextCompat.getColor(this, R.color.theme_text_secondary)
        )
        binding.f060ResultCode.text = none
        binding.f060ResultElapsed.text = none
        binding.f060ResultCached.text = none
        binding.f060ResultError.text = getString(R.string.f060_result_idle)
        binding.f060RecheckHint.setTextColor(
            ContextCompat.getColor(this, R.color.theme_text_secondary)
        )
        setStatus(getString(R.string.f060_result_idle_hint), R.color.theme_text_secondary)
    }

    private fun renderBusy(url: String, force: Boolean) {
        val none = getString(R.string.f060_value_none)
        binding.f060ResultVerdict.text = none
        binding.f060ResultCode.text = none
        binding.f060ResultElapsed.text = none
        binding.f060ResultCached.text = none
        binding.f060ResultError.text = none
        setStatus(
            getString(if (force) R.string.f060_rechecking else R.string.f060_checking, url),
            R.color.theme_text_secondary
        )
    }

    /**
     * 渲染一次结论。
     *
     * @param fromCache 这条结论是不是 5 分钟缓存里翻出来的（界面必须说出来，否则用户会以为刚测过）
     */
    private fun renderResult(result: PreflightResult, fromCache: Boolean) {
        val ageMs = (System.currentTimeMillis() - result.cachedAtMs).coerceAtLeast(0L)

        val verdict = when {
            result.ok && fromCache -> getString(R.string.f060_verdict_reachable_cached)
            result.ok -> getString(R.string.f060_verdict_reachable)
            result.error == EMPTY_INPUT || result.error == BAD_SCHEME ->
                getString(R.string.f060_verdict_invalid)
            else -> getString(R.string.f060_verdict_unreachable)
        }
        binding.f060ResultVerdict.text = verdict
        // 命中缓存用警告色：结论是可达，但它是旧结论
        binding.f060ResultVerdict.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    !result.ok -> R.color.theme_error
                    fromCache -> R.color.theme_warning
                    else -> R.color.theme_success
                }
            )
        )

        binding.f060ResultCode.text = result.statusCode
            ?.let { getString(R.string.f060_code_value, it) }
            ?: getString(R.string.f060_value_none)
        binding.f060ResultElapsed.text = getString(R.string.f060_elapsed_value, result.ms)
        binding.f060ResultCached.text = when {
            fromCache -> getString(R.string.f060_cached_value, minutes(ageMs))
            result.ms == 0L && !result.ok -> getString(R.string.f060_not_requested)
            else -> getString(R.string.f060_cached_no)
        }
        binding.f060ResultError.text = result.error?.takeIf { it.isNotBlank() }
            ?: getString(R.string.f060_value_none)

        when {
            result.ok && fromCache -> setStatus(
                getString(R.string.f060_status_cached, minutes(ageMs)),
                R.color.theme_warning
            )
            result.ok -> setStatus(
                getString(R.string.f060_status_reachable, engine.formatSummary(result)),
                R.color.theme_success
            )
            else -> setStatus(
                getString(R.string.f060_status_unreachable, engine.formatSummary(result)),
                R.color.theme_error
            )
        }

        // 命中缓存时把「重新预检」的提示提成主题色：旧结论永远有个显式刷新入口
        binding.f060RecheckHint.setTextColor(
            ContextCompat.getColor(
                this,
                if (fromCache) R.color.theme_primary else R.color.theme_text_secondary
            )
        )
        if (fromCache) CustomToast.show(this, getString(R.string.f060_toast_cached))
    }

    /** 缓存年龄（分钟），只在提示文案里用，向下取整。 */
    private fun minutes(ageMs: Long): Int = (ageMs / 60_000L).toInt().coerceAtLeast(0)

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f060ResultStatus.text = message
        binding.f060ResultStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.f060ResultStatus.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    companion object {
        /** 引擎里"输入不合法"的固定文案，用来把这类失败和网络失败区分开。 */
        private const val EMPTY_INPUT = "地址为空"
        private const val BAD_SCHEME = "地址不合法（只支持 http / https）"
    }
}