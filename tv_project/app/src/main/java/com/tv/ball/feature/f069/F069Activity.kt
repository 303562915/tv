package com.tv.ball.feature.f069

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF069Binding
import com.tv.ball.util.CustomToast
import kotlin.random.Random

/**
 * 「访问日志与令牌鉴权」页。
 *
 * 这一页是 [F069Engine] 的**仪表盘**，四块内容各回答一个问题：
 *
 * 1. 日志列表 —— 刚才谁在访问？时间 / 来源 IP / 路径 / 状态码，状态码按档着色。
 * 2. 令牌开关与令牌本体 —— 现在锁没锁门？锁着的话钥匙是什么（可复制、可重置）。
 * 3. 带令牌的示例地址 —— 钥匙怎么用？直接给一条能粘进播放器的完整 URL。
 * 4. 清空日志 —— 现场看完了，收拾干净。
 *
 * 页面不含任何判断逻辑：环形缓冲、令牌生成、校验、脱敏、拼地址全在引擎里，
 * 页面只做两件事——**把动作喂给引擎**、**把引擎的输出翻成资源**（颜色、文案）。
 * 之所以坚持这样，是因为这几件事必须永远对得上：曾经出现过"开关显示已关闭、
 * 地址里却还带着旧令牌"的情况，用户对着两个都对的值无从判断谁错了。
 */
class F069Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF069Binding
    private lateinit var engine: F069Engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF069Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F069Engine.create(this)

        binding.btnBack.setOnClickListener { finish() }

        // 首次进来给一屏真实形状的记录，否则"状态码着色"这件事在空列表上根本演不出来
        seedIfEmpty()

        // 先设初值再挂监听：先挂监听再 setChecked 会白跑一次持久化
        binding.f069Switch.isChecked = engine.guard.enabled
        binding.f069Switch.setOnCheckedChangeListener { _, checked ->
            engine.guard.enabled = checked
            renderToken()
        }

        binding.f069BtnCopy.setOnClickListener { copyToken() }
        binding.f069BtnRotate.setOnClickListener { rotateToken() }

        // 模拟一次外部访问：开关开着时有概率不带令牌，正好演 401 那一档
        binding.f069BtnDemo.setOnClickListener { demoHit() }
        binding.f069BtnClear.setOnClickListener { clearLogs() }

        renderToken()
        renderLogs()
    }

    // ===== 首次样例 =====

    /**
     * 空日志时补几条样例记录。
     *
     * 六条刻意覆盖四个色档：206/200 正常取流、304 客户端有缓存、403/404 被拒或写错、
     * 500 服务端自己出错。断点时刻往前错开，时间列看起来像真的在发生。
     */
    private fun seedIfEmpty() {
        if (engine.size > 0) return
        val now = System.currentTimeMillis()
        SEED.forEachIndexed { index, row ->
            engine.record(row.first, row.second, row.third, now - (SEED.size - index) * 41_000L)
        }
    }

    // ===== 令牌区 =====

    /**
     * 渲染开关状态、令牌本体、示例地址。
     *
     * 开关关着时令牌**照旧显示**但压成灰色：它没失效，只是暂时不校验，
     * 用户重新打开时必须还是同一把钥匙，否则"关一下再开"就会把已发出去的地址全废掉。
     */
    private fun renderToken() {
        val enabled = engine.guard.enabled

        binding.f069Token.text = engine.guard.token
        binding.f069Token.setTextColor(
            color(if (enabled) R.color.theme_primary else R.color.theme_disabled)
        )

        binding.f069TokenState.text = getString(
            if (enabled) R.string.f069_token_on else R.string.f069_token_off
        )
        binding.f069TokenState.setTextColor(
            color(if (enabled) R.color.theme_success else R.color.theme_text_secondary)
        )

        binding.f069SampleUrl.text = engine.guard.urlWithToken(SAMPLE_BASE, SAMPLE_PATH)
    }

    /** 复制令牌。剪贴板标签写死成固定串，系统"最近复制"里一眼能认出这段是哪来的。 */
    private fun copyToken() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            CustomToast.show(this, getString(R.string.f069_toast_copy_failed))
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, engine.guard.token))
        CustomToast.show(this, getString(R.string.f069_toast_copied))
    }

    /** 重置令牌：旧地址全部失效，示例地址跟着换成新的。 */
    private fun rotateToken() {
        val fresh = engine.guard.rotate()
        renderToken()
        CustomToast.show(this, getString(R.string.f069_toast_rotated, fresh))
    }

    // ===== 日志区 =====

    /**
     * 渲染日志列表。
     *
     * 最多画 [MAX_ROWS] 行：缓冲里可能有 200 条，一次性 200 个 View 会明显卡一下，
     * 而人真正看的是最新那几十条。条数统计仍然报总量，不虚报。
     */
    private fun renderLogs() {
        val rows = engine.recent(MAX_ROWS)
        val total = engine.size

        binding.f069LogList.removeAllViews()
        binding.f069Empty.visibility = if (total == 0) View.VISIBLE else View.GONE
        binding.f069Count.text = getString(R.string.f069_log_count, total, F069Engine.CAPACITY)

        rows.forEach { binding.f069LogList.addView(buildRow(it)) }
    }

    /**
     * 一行访问记录：时间、来源、路径、状态码。
     *
     * 用 `LinearLayout` 而不是 `ListView`：行数上限就几十，复用机制省下的那点内存
     * 换来的是"滚动位置与实时插入打架"的一堆状态，不划算。
     */
    private fun buildRow(entry: AccessLog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            setBackgroundResource(R.drawable.bg_card_surface)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.card_gap) }
        }

        val time = mono(engine.formatClock(entry.atMs), R.color.theme_text_secondary)
            .apply {
                layoutParams = LinearLayout.LayoutParams(dp(64f), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        val ip = mono(entry.ip, R.color.theme_text_primary).apply {
            layoutParams = LinearLayout.LayoutParams(dp(108f), ViewGroup.LayoutParams.WRAP_CONTENT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val path = mono(entry.path, R.color.theme_text_primary).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }

        val status = mono(entry.status.toString(), colorResOf(engine.toneOf(entry.status))).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        row.addView(time)
        row.addView(ip)
        row.addView(path)
        row.addView(status)
        return row
    }

    private fun mono(text: String, colorRes: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(color(colorRes))
    }

    /** 状态档 → 主题色。四档四色，一眼能分出"正常取流"和"被拒/出错"。 */
    private fun colorResOf(tone: StatusTone): Int = when (tone) {
        StatusTone.OK -> R.color.theme_success
        StatusTone.REDIRECT -> R.color.theme_info
        StatusTone.CLIENT_ERROR -> R.color.theme_warning
        StatusTone.SERVER_ERROR -> R.color.theme_error
    }

    /**
     * 模拟一次外部访问。
     *
     * 开关开着时一半概率不带令牌——这不是随机玩具，是让 401 这一档能被看见：
     * 只有亲眼见过"没带令牌 = 橙色 401"，才会明白为什么陌生设备连不上。
     */
    private fun demoHit() {
        val hit = DEMO_HITS[Random.nextInt(DEMO_HITS.size)]
        val guard = engine.guard
        val params = if (guard.enabled && Random.nextBoolean()) {
            mapOf(TokenGuard.PARAM to guard.token)
        } else {
            emptyMap()
        }

        val allowed = engine.authorize(hit.first, hit.second, params)
        renderLogs()
        CustomToast.show(
            this,
            getString(
                if (allowed) R.string.f069_toast_demo_allowed else R.string.f069_toast_demo_denied,
                hit.first
            )
        )
    }

    /** 清空日志：只清访问记录，令牌与开关维持现状。 */
    private fun clearLogs() {
        engine.clear()
        renderLogs()
        CustomToast.show(this, getString(R.string.f069_toast_cleared))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 剪贴板标签。 */
        const val CLIP_LABEL = "F069 token"

        /** 列表最多画多少行（缓冲容量仍是 200）。 */
        const val MAX_ROWS = 40

        /** 示例地址的服务根：局域网内电视/播放器实际连的那台。 */
        const val SAMPLE_BASE = "http://192.168.1.10:8090"

        /** 示例媒体路径。 */
        const val SAMPLE_PATH = "/media/EP01.mp4"

        /** 首次进来铺的一屏记录：六条覆盖四个色档。 */
        val SEED: List<Triple<String, String, Int>> = listOf(
            Triple("192.168.1.40", "/media/EP02.mp4", 200),
            Triple("192.168.1.23", "/media/EP01.mp4", 206),
            Triple("192.168.1.57", "/api/list", 304),
            Triple("192.168.1.88", "/media/EP03.mp4", 403),
            Triple("192.168.1.99", "/subtitle/EP01.vtt", 404),
            Triple("10.0.0.12", "/admin/debug", 500)
        )

        /** 模拟访问用的地址池。 */
        val DEMO_HITS: List<Pair<String, String>> = listOf(
            "192.168.1.23" to "/media/EP01.mp4",
            "192.168.1.57" to "/media/EP02.mp4",
            "192.168.1.99" to "/subtitle/EP01.vtt",
            "10.0.0.8" to "/api/list"
        )
    }
}