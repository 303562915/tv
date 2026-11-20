package com.tv.ball.feature.f026

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF026Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils

/**
 * F026 最近添加与最近播放。
 *
 * 页面只有一层交互：顶上两个按钮切数据源，中间三个按钮切上限档位，底下一条横向轨道放卡片。
 * 排序、过滤、上限归一全在 [F026Engine] 里，这里只做三件事——拿数据、铺卡片、把上限落盘。
 *
 * 数据是 8 条示例库（真实条目来自扫描/同步，属于别的任务），但页面上的行为都是真的：
 * 点卡片会把该条目标成「刚播放」，「最近播放」立刻把它推到最前；清空后空态立刻出现。
 */
class F026Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF026Binding

    private lateinit var limitPrefs: F026LimitPrefs

    /** 当前库；两个数据源共用同一份，改播放时间只需要替换列表内容。 */
    private val library = ArrayList<F026Engine.MediaRef>()

    private var source: F026Engine.Source = F026Engine.Source.RECENTLY_ADDED

    private var limit: Int = F026Engine.DEFAULT_LIMIT

    /** 相对时间的参照点：进页面定一次，渲染整条轨道时不会因为跨分钟而前半段「5 分钟前」后半段「6 分钟前」。 */
    private var timeAnchorMs: Long = 0L

    /** 刷新后要还回去焦点的卡片 id；遥控器操作时焦点不能掉出轨道。 */
    private var focusedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF026Binding.inflate(layoutInflater)
        setContentView(binding.root)

        limitPrefs = F026LimitPrefs(this)
        timeAnchorMs = System.currentTimeMillis()

        // 数据源与上限优先从 bundle 恢复（旋转前用户选的那一档），没存过才回落到偏好
        source = savedInstanceState?.getString(STATE_SOURCE)
            ?.let { name -> F026Engine.Source.values().firstOrNull { it.name == name } }
            ?: F026Engine.Source.RECENTLY_ADDED
        val restoredLimit = if (savedInstanceState != null && savedInstanceState.containsKey(STATE_LIMIT)) {
            savedInstanceState.getInt(STATE_LIMIT)
        } else {
            limitPrefs.load()
        }
        limit = F026Engine.normalizeLimit(restoredLimit)

        // bundle 里是空串 = 用户清空过库，不能拿它当「没存过」再灌一遍示例
        val savedLibrary = savedInstanceState?.getString(STATE_LIBRARY)
        library.addAll(
            if (savedLibrary == null) F026Engine.sampleLibrary(timeAnchorMs) else F026Engine.decode(savedLibrary)
        )

        binding.btnSourceAdded.setOnClickListener { switchSource(F026Engine.Source.RECENTLY_ADDED) }
        binding.btnSourcePlayed.setOnClickListener { switchSource(F026Engine.Source.RECENTLY_PLAYED) }
        binding.btnLimit5.setOnClickListener { applyLimit(5) }
        binding.btnLimit10.setOnClickListener { applyLimit(10) }
        binding.btnLimit20.setOnClickListener { applyLimit(20) }
        binding.btnLoadSamples.setOnClickListener { loadSamples() }
        binding.btnClearLibrary.setOnClickListener { clearLibrary() }

        render()
        // 遥控器进页面先落在当前数据源上，方向键才有起点
        binding.root.post { sourceButton(source).requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SOURCE, source.name)
        outState.putInt(STATE_LIMIT, limit)
        // 播放标记是用户点出来的，不可再生，连库一起带走
        outState.putString(STATE_LIBRARY, F026Engine.encode(library))
    }

    // region 交互

    private fun switchSource(next: F026Engine.Source) {
        if (source == next) return
        source = next
        focusedId = null
        render()
    }

    private fun applyLimit(value: Int) {
        val next = F026Engine.normalizeLimit(value)
        if (next == limit) return
        limit = next
        // 立即落盘：退出页面再进还是这一档
        limitPrefs.save(next)
        focusedId = null
        render()
        CustomToast.show(this, getString(R.string.f026_toast_limit, next))
    }

    private fun loadSamples() {
        val fresh = F026Engine.sampleLibrary(System.currentTimeMillis())
        timeAnchorMs = System.currentTimeMillis()
        library.clear()
        library.addAll(fresh)
        focusedId = null
        render()
        CustomToast.show(this, getString(R.string.f026_toast_samples, fresh.size))
    }

    private fun clearLibrary() {
        if (library.isEmpty()) {
            CustomToast.show(this, getString(R.string.f026_toast_already_empty))
            return
        }
        library.clear()
        focusedId = null
        render()
        CustomToast.show(this, getString(R.string.f026_toast_cleared))
    }

    /** 点卡片 = 标成刚播放；「最近播放」靠这个时间戳排序，点完立刻换位。 */
    private fun markPlayed(item: F026Engine.MediaRef) {
        val updated = F026Engine.markPlayed(library, item.id, System.currentTimeMillis())
        library.clear()
        library.addAll(updated)
        focusedId = item.id
        render()
        CustomToast.show(this, getString(R.string.f026_toast_marked, item.title))
    }

    // endregion

    // region 渲染

    private fun render() {
        // 先记旧焦点：重建卡片后旧 View 已经被移除，只有按钮还有机会原样还回去
        val previousFocus = currentFocus

        binding.railContainer.removeAllViews()
        val items = F026Engine.select(library, source, limit)
        items.forEachIndexed { index, item ->
            binding.railContainer.addView(buildCard(item, index))
        }
        chainFocus()

        val hasItems = items.isNotEmpty()
        binding.railScroll.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.emptyHint.visibility = if (hasItems) View.GONE else View.VISIBLE
        binding.emptyHint.text = getString(
            if (source == F026Engine.Source.RECENTLY_ADDED) {
                R.string.f026_empty_added
            } else {
                R.string.f026_empty_played
            }
        )

        binding.tvSummary.text = if (library.isEmpty()) {
            getString(R.string.f026_summary_library_empty)
        } else {
            getString(
                if (source == F026Engine.Source.RECENTLY_ADDED) {
                    R.string.f026_summary_added
                } else {
                    R.string.f026_summary_played
                },
                items.size,
                F026Engine.count(library, source),
                limit
            )
        }

        styleSourceButtons()
        styleLimitButtons()

        restoreFocus(previousFocus)
    }

    /**
     * 一张卡片：序号 + 标题、路径、时间、播放状态。
     *
     * 宽度固定、高度只给下限：卡片靠左排成一条横向轨道，条数变了轨道长度变，卡片本身不抽动。
     */
    private fun buildCard(item: F026Engine.MediaRef, index: Int): View {
        val pad = dp(12)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            minimumWidth = dp(CARD_WIDTH_DP)
            minimumHeight = dp(CARD_MIN_HEIGHT_DP)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(this@F026Activity, R.drawable.bg_mode_card)
            foreground = ContextCompat.getDrawable(this@F026Activity, R.drawable.bg_focus_ring)
            layoutParams = LinearLayout.LayoutParams(
                dp(CARD_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = if (index == 0) 0 else dp(8) }
            // 用 tag 记条目 id：刷新后靠它把焦点还回原来那一张
            tag = item.id
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focusedId = item.id }
            setOnClickListener { markPlayed(item) }
        }

        card.addView(TextView(this).apply {
            text = getString(R.string.f026_item_title, index + 1, item.title)
            // 卡片里的排版用主题里 TextAppearance.TVPlayer.Body 的两个指标：15sp + 主文字色
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@F026Activity, R.color.theme_text_primary))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        card.addView(TextView(this).apply {
            text = item.path
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@F026Activity, R.color.theme_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })

        // 时间行跟着当前数据源走：最近添加看入库时间，最近播放看最后播放时间
        val timeText = when {
            source == F026Engine.Source.RECENTLY_ADDED ->
                getString(R.string.f026_item_added_ago, agoLabel(item.addedAtMs))
            item.isPlayed ->
                getString(R.string.f026_item_played_ago, agoLabel(item.lastPlayedAtMs))
            else -> getString(R.string.f026_never_played)
        }
        card.addView(TextView(this).apply {
            text = timeText
            // 与 TextAppearance.TVPlayer.Caption 对齐：12sp + 次要文字色
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@F026Activity, R.color.theme_text_secondary))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        card.addView(TextView(this).apply {
            text = getString(
                if (item.isPlayed) R.string.f026_status_played else R.string.f026_status_unplayed
            )
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(
                    this@F026Activity,
                    if (item.isPlayed) R.color.theme_primary else R.color.theme_text_secondary
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        return card
    }

    /**
     * 把相对时间翻成文案。
     *
     * 超过一周的跨度 [F026Engine.ago] 会给 DATE，这里直接显示绝对日期——
     * 「37 天前」这种读不出信息量的文案不如给个日期。
     */
    private fun agoLabel(thenMs: Long): String {
        val ago = F026Engine.ago(timeAnchorMs, thenMs)
        return when (ago.unit) {
            F026Engine.AgoUnit.NEVER -> getString(R.string.f026_never_played)
            F026Engine.AgoUnit.JUST_NOW -> getString(R.string.f026_ago_just_now)
            F026Engine.AgoUnit.MINUTE -> getString(R.string.f026_ago_minutes, ago.amount)
            F026Engine.AgoUnit.HOUR -> getString(R.string.f026_ago_hours, ago.amount)
            F026Engine.AgoUnit.DAY -> getString(R.string.f026_ago_days, ago.amount)
            F026Engine.AgoUnit.DATE -> F026Engine.formatDate(thenMs)
        }
    }

    /**
     * 卡片之间显式串左右邻居。
     *
     * 默认的焦点搜索会按坐标去找，轨道滚起来之后容易跳过一张或者跳到页面外的按钮上；
     * 按渲染顺序钉死之后，遥控器一路按右键就是轨道原来的顺序。
     */
    private fun chainFocus() {
        val cards = (0 until binding.railContainer.childCount)
            .map { binding.railContainer.getChildAt(it) }
        cards.forEachIndexed { index, card ->
            if (card.id == View.NO_ID) card.id = View.generateViewId()
            if (index > 0) card.nextFocusLeftId = cards[index - 1].id
            if (index < cards.lastIndex) card.nextFocusRightId = cards[index + 1].id
        }
    }

    /**
     * 刷新后还原焦点：旧焦点还在（按的是按钮）就还给它，
     * 否则回到上次聚焦的卡片，连卡片也没了才落到数据源按钮上。
     */
    private fun restoreFocus(previous: View?) {
        if (previous != null && previous.parent != null && previous.isFocusable && previous.isEnabled) {
            previous.requestFocus()
            return
        }
        val card = focusedId?.let { id -> findCard(id) }
        if (card != null) {
            card.requestFocus()
        } else {
            sourceButton(source).requestFocus()
        }
    }

    private fun findCard(id: String): View? {
        for (index in 0 until binding.railContainer.childCount) {
            val child = binding.railContainer.getChildAt(index)
            if (child.tag == id) return child
        }
        return null
    }

    // endregion

    // region 按钮状态

    /**
     * 切换类按钮的选中态：选中 = primary 描边底 + primary 文字，未选中 = 卡片底 + 主文字色。
     * 背景用 bg_mode_card_selected / bg_mode_card，与外观主题页的「选中卡片」同一套视觉。
     */
    private fun styleToggle(button: Button, selected: Boolean) {
        button.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.theme_primary else R.color.theme_text_primary
            )
        )
        button.isSelected = selected
    }

    private fun styleSourceButtons() {
        styleToggle(binding.btnSourceAdded, source == F026Engine.Source.RECENTLY_ADDED)
        styleToggle(binding.btnSourcePlayed, source == F026Engine.Source.RECENTLY_PLAYED)
    }

    private fun styleLimitButtons() {
        // 文案带数字，只能在这里拼；档位从常量取，避免和布局里的 id 各写一遍数
        listOf(
            binding.btnLimit5 to 5,
            binding.btnLimit10 to 10,
            binding.btnLimit20 to 20
        ).forEach { (button, value) ->
            button.text = getString(R.string.f026_limit_option, value)
            styleToggle(button, limit == value)
        }
    }

    private fun sourceButton(value: F026Engine.Source): Button = when (value) {
        F026Engine.Source.RECENTLY_ADDED -> binding.btnSourceAdded
        F026Engine.Source.RECENTLY_PLAYED -> binding.btnSourcePlayed
    }

    // endregion

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val CARD_WIDTH_DP = 180
        private const val CARD_MIN_HEIGHT_DP = 116
        private const val STATE_SOURCE = "f026_state_source"
        private const val STATE_LIMIT = "f026_state_limit"
        private const val STATE_LIBRARY = "f026_state_library"
    }
}

/**
 * 上限的落盘口。
 *
 * 只存一个整数档位，SharedPreferences 首次加载之后都是内存读，放在主线程不会卡页面；
 * 读出来先过一遍 [F026Engine.normalizeLimit]，配置文件被手改坏也不会出现 0 条或者几千条。
 */
private class F026LimitPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Int = F026Engine.normalizeLimit(
        prefs.getInt(KEY_LIMIT, F026Engine.DEFAULT_LIMIT)
    )

    fun save(limit: Int) {
        prefs.edit().putInt(KEY_LIMIT, F026Engine.normalizeLimit(limit)).apply()
    }

    private companion object {
        const val PREFS_NAME = "f026_prefs"
        const val KEY_LIMIT = "f026_limit"
    }
}
