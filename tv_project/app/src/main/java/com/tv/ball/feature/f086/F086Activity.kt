package com.tv.ball.feature.f086

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF086Binding
import com.tv.ball.util.CustomToast
import kotlin.math.roundToInt

/**
 * 「关于页与许可证」页。
 *
 * 四段：
 * 1. **应用信息**：版本行（[F086Engine.versionText]）、包名、更新日期；
 * 2. **第三方依赖**：每行一个库（名字 / 版本 / 许可证 / 上游地址），清单来自 [F086Engine.deps]，
 *    与 README 的表格同源，页面不另写一份；
 * 3. **源码与仓库**：「打开仓库」发 `ACTION_VIEW` 交给浏览器，没有浏览器时只提示、不崩；
 * 4. **许可证全文**：一个固定高度的滚动区长文本，逐条列出依赖与 Apache-2.0 要点。
 *
 * 这一页是纯只读的：不发请求、不写任何文件、不改任何设置。所以也就没有状态机，
 * `onCreate` 里把三块内容一次性画完即可。
 *
 * 电视遥控器操作：只有「返回」与「打开仓库」两个焦点，依赖行与长文本区都不抢焦点——
 * 十二行依赖如果每行都可聚焦，方向键要按十几次才能走到按钮；焦点顺序在布局里
 * 用 nextFocus 串成 返回 → 打开仓库 → 文本区。
 */
class F086Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF086Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF086Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f086RepoButton.setOnClickListener { openRepo() }

        renderAppInfo()
        renderDeps()
        renderLicense()
    }

    // ===== 应用信息 =====

    /**
     * 三行应用信息。
     *
     * 版本行已经含安装时间，第三行「更新日期」再单独给一次是因为两张卡要能分开扫：
     * 版本行是给人读的一整句，更新日期这一行只有日期，方便一眼对"手上这份是不是最新的"。
     * 两处都走 [F086Engine.formatDate]，同一个时间戳不会出现两种写法。
     */
    private fun renderAppInfo() {
        val info = F086Engine.appInfo(this)
        binding.f086VersionValue.text = F086Engine.versionText(this)
        binding.f086PackageValue.text = info.packageName
        binding.f086UpdatedValue.text = if (info.updatedAtMillis > 0L) {
            F086Engine.formatDate(info.updatedAtMillis)
        } else {
            getString(R.string.f086_value_unknown)
        }
    }

    // ===== 依赖列表 =====

    /** 依赖卡：先写"共 N 项"，再按 [F086Engine.deps] 的顺序逐行生成。 */
    private fun renderDeps() {
        val deps = F086Engine.deps()
        binding.f086DepsCount.text = getString(R.string.f086_deps_count, deps.size)

        binding.f086DepList.removeAllViews()
        deps.forEach { addDepRow(it) }
    }

    /**
     * 一行依赖：名字 / `版本 x · 许可证 y` / 上游地址。
     *
     * 行本身不可聚焦（见类注释）：它没有点击行为，可聚焦只会让遥控器的焦点停在死位置上。
     * 背景用 bg_card_surface，与外层卡片的 surface 层次一致，等于"卡里再嵌一层块"。
     */
    private fun addDepRow(dep: Dep) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@F086Activity, R.drawable.bg_card_surface)
        }

        row.addView(
            TextView(this).apply {
                text = dep.name
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
            }
        )

        row.addView(
            TextView(this).apply {
                text = getString(R.string.f086_dep_line, dep.version, dep.license)
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            }
        )

        row.addView(
            TextView(this).apply {
                text = dep.url
                setTextColor(color(R.color.theme_primary))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            }
        )

        binding.f086DepList.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    // ===== 许可证 =====

    /** 许可证区：一行统计 + 可滚动长文本，都由引擎生成，页面不做字符串拼接。 */
    private fun renderLicense() {
        binding.f086LicenseSummary.text = F086Engine.licenseSummary()
        binding.f086LicenseText.text = F086Engine.licenseText()
    }

    // ===== 仓库 =====

    /**
     * 打开仓库。
     *
     * 只发 `ACTION_VIEW`，不自己拼浏览器：用户已经选过默认浏览器，这里尊重那个选择。
     * 电视盒子上没装浏览器是很常见的情况，`startActivity` 会抛
     * [ActivityNotFoundException]，捕获后给一句"地址是什么"，让用户能抄到别处去开——
     * 关于页因为一个按钮把应用崩掉，比打不开链接难解释得多。
     *
     * 不用 `resolveActivity` 先探一遍：Android 11+ 的包可见性会让它在浏览器明明装着的
     * 情况下也返回 null，探完再决定反而会误报"没有浏览器"。
     */
    private fun openRepo() {
        val url = F086Engine.repoUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            CustomToast.show(this, getString(R.string.f086_toast_repo_opening, url))
        } catch (e: ActivityNotFoundException) {
            CustomToast.show(this, getString(R.string.f086_toast_repo_failed, url))
        } catch (e: SecurityException) {
            // 少数定制 ROM 会把 ACTION_VIEW 也拦下来，同样只提示、不崩
            CustomToast.show(this, getString(R.string.f086_toast_repo_failed, url))
        }
    }

    // ===== 小工具 =====

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}
