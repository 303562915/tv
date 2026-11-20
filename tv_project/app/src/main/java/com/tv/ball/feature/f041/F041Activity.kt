package com.tv.ball.feature.f041

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF041Binding

/**
 * F041「视图模式切换」页面。
 *
 * 8 张示例卡片，两种浏览模式即时切换：网格挂 4 列 [GridLayoutManager]，列表挂 1 列 [LinearLayoutManager]。
 *
 * 关键点在于「一页一种模式，各记一份滚动位置」：
 * 切页前先把当前页的可见位置写回 [F041Engine]，切过去再按目标页记住的位置还原，
 * 所以来回切不会把另一页的位置冲掉；离开页面时模式与两页位置一起落盘，重进照原样恢复。
 *
 * 规则都在 [F041Engine]（纯 Kotlin），这里只负责摆 LayoutManager、刷文案和存取 SharedPreferences。
 */
class F041Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF041Binding
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: F041Engine
    private lateinit var adapter: F041CardAdapter

    /** 8 条示例卡片：只在这里定义一次，切模式只换排布、不动数据。 */
    private val samples: List<F041Card> = listOf(
        F041Card("三体 第一季 01", "科幻 · 已看到第 12 分钟"),
        F041Card("流浪地球 2", "科幻 · 4K 原盘"),
        F041Card("漫长的季节 01", "悬疑 · 更新至 12 集"),
        F041Card("狂飙 第 12 集", "罪案 · 已缓存"),
        F041Card("宇宙时空之旅 01", "纪录片 · 1080P"),
        F041Card("变形金刚 5", "动作 · 杜比音效"),
        F041Card("白色巨塔 第 3 集", "剧情 · 弹幕热度高"),
        F041Card("地球脉动 第 2 季", "纪录片 · 已收藏")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF041Binding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        engine = F041Engine()
        engine.importJson(prefs.getString(KEY_STATE, null))

        adapter = F041CardAdapter(this, samples) { card, index ->
            toast(getString(R.string.f041_toast_pick, index + 1, card.title))
        }
        // 切模式时条目形态整体换一遍，关掉动画才不会看到旧条目的淡入淡出。
        binding.cardList.itemAnimator = null
        binding.cardList.adapter = adapter
        binding.cardList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // 滚动途中只刷文案不落盘：逐帧写盘没必要，位置在切页和离开页面时统一记一次。
                renderScrollStat()
            }
        })

        binding.modeGridRow.setOnClickListener { switchTo(ViewMode.GRID) }
        binding.modeListRow.setOnClickListener { switchTo(ViewMode.LIST) }

        // 进页面先按上次的模式摆好，并把那一页记住的位置还原，再刷选中态与位置说明。
        applyMode(engine.mode, restoreScroll = true)
        renderMode()
    }

    override fun onPause() {
        // 退出或进后台前落盘：当前页的实时位置 + 另一页已经记住的位置 + 当前模式。
        persistCurrentScroll()
        saveState()
        super.onPause()
    }

    // region 模式切换

    /**
     * 切到 [target] 这一页。
     *
     * 顺序不能反：先把当前页的位置写回 Engine，再换 LayoutManager；
     * 换完再读，读到的已经是新页的下标了。
     */
    private fun switchTo(target: ViewMode) {
        if (engine.mode == target) {
            toast(getString(R.string.f041_toast_same, modeName(target)))
            return
        }
        persistCurrentScroll()
        engine.setMode(target)
        applyMode(target, restoreScroll = true)
        renderMode()
        saveState()
        toast(getString(R.string.f041_toast_switch, modeName(target)))
    }

    /**
     * 按 [mode] 摆好列表：列数、条目形态、滚动位置。
     *
     * 网格挂 [GridLayoutManager]（4 列），列表挂 [LinearLayoutManager]（1 列）。
     * 两者都是 [LinearLayoutManager]，所以「按条目下标还原位置」这一步可以共用，
     * 不用为两种模式各写一套恢复逻辑。
     */
    private fun applyMode(mode: ViewMode, restoreScroll: Boolean) {
        val columns = engine.columns(mode)
        val manager: LinearLayoutManager = if (columns > F041Engine.LIST_COLUMNS) {
            GridLayoutManager(this, columns)
        } else {
            LinearLayoutManager(this)
        }

        // 先换条目形态再挂新 LayoutManager，避免旧形态的行高参与这一次布局。
        adapter.setMode(mode)
        binding.cardList.layoutManager = manager

        val saved = engine.restoreScroll(F041Engine.pageKeyOf(mode))
        if (restoreScroll && saved > 0) {
            manager.scrollToPositionWithOffset(saved, 0)
        } else {
            manager.scrollToPosition(0)
        }
    }

    /** 把当前页第一个可见条目写回 Engine；列表还没布局时按 0 记。 */
    private fun persistCurrentScroll() {
        engine.saveScroll(F041Engine.pageKeyOf(engine.mode), currentFirstVisible())
    }

    /** 当前页第一个可见条目的下标，没布局或拿不到时返回 0。 */
    private fun currentFirstVisible(): Int {
        val manager = binding.cardList.layoutManager as? LinearLayoutManager ?: return 0
        return manager.findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    // endregion

    // region 渲染

    /** 当前模式 + 两张模式开关的选中态。 */
    private fun renderMode() {
        val mode = engine.mode
        binding.modeStat.text = getString(R.string.f041_status_mode, modeName(mode), engine.columns(mode))

        // 选中态换底图（primary 描边卡片），未选中是普通卡片；焦点态由 foreground 的描边单独负责，
        // 两者叠在一起也不会互相盖掉，D-Pad 走焦点时看得出「焦点在哪、选中是哪个」。
        binding.modeGridRow.background = rowBackground(mode == ViewMode.GRID)
        binding.modeListRow.background = rowBackground(mode == ViewMode.LIST)
        renderScrollStat()
    }

    /**
     * 两页的位置说明：本页给实时可见位置，另一页给已记住的位置。
     *
     * 另一页还没记过时明说「还没记过」，比直接显示第 1 条更诚实——
     * 用户能分清「记住了第 1 条」和「压根没记过」。
     */
    private fun renderScrollStat() {
        val mode = engine.mode
        binding.scrollStat.text =
            getString(R.string.f041_scroll_current, modeName(mode), currentFirstVisible() + 1)

        val other = mode.toggle()
        val otherKey = F041Engine.pageKeyOf(other)
        binding.otherStat.text = if (engine.hasScroll(otherKey)) {
            getString(R.string.f041_scroll_other, modeName(other), engine.restoreScroll(otherKey) + 1)
        } else {
            getString(R.string.f041_scroll_other_none, modeName(other))
        }
    }

    private fun rowBackground(active: Boolean) = ContextCompat.getDrawable(
        this,
        if (active) R.drawable.bg_mode_card_selected else R.drawable.bg_card_surface
    )

    private fun modeName(mode: ViewMode): String = getString(
        if (mode == ViewMode.GRID) R.string.f041_mode_grid else R.string.f041_mode_list
    )

    // endregion

    /** 模式与两页的滚动位置一起落盘，下次进来照着恢复。 */
    private fun saveState() {
        prefs.edit().putString(KEY_STATE, engine.exportJson()).apply()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS_NAME = "feature_f041"
        const val KEY_STATE = "state"
    }
}

/** 一张示例卡片的数据：卡片形态由模式决定，数据本身与模式无关。 */
private data class F041Card(val title: String, val meta: String)

/**
 * 卡片列表：网格与列表共用同一份数据，只换条目结构。
 *
 * 条目视图在代码里搭而不是单开 item 布局，是因为两种形态的差别只有排布
 * （网格竖排 + 大封面，列表横排 + 小封面 + 序号），一份构建代码按模式分支就够，
 * 也免得为此新增布局文件。
 */
private class F041CardAdapter(
    private val context: Context,
    private val items: List<F041Card>,
    private val onPick: (F041Card, Int) -> Unit
) : RecyclerView.Adapter<F041CardAdapter.CardHolder>() {

    var mode: ViewMode = ViewMode.GRID
        private set

    /** 换模式要连条目结构一起换，所以整表重绑；8 条数据重绑一次的成本可以忽略。 */
    fun setMode(value: ViewMode) {
        if (mode == value) return
        mode = value
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (mode == ViewMode.GRID) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val grid = viewType == TYPE_GRID
        val gap = context.resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(CARD_PADDING_DP)

        val root = LinearLayout(context).apply {
            orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (grid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            minimumHeight = context.resources.getDimensionPixelSize(R.dimen.list_row_min_height)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_card_surface)
            // D-Pad 焦点靠 foreground 的 2dp 描边，卡片底本身不随焦点变化，避免和选中态混淆。
            foreground = ContextCompat.getDrawable(context, R.drawable.bg_focus_ring)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val half = gap / 2
                val side = if (grid) half else 0
                setMargins(side, half, side, half)
            }
        }

        // 封面位只是视觉占位，不承载任何状态，所以给一个不随焦点变化的中性底。
        val poster = View(context).apply {
            isFocusable = false
            background = ContextCompat.getDrawable(context, R.drawable.bg_icon_well)
            layoutParams = if (grid) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GRID_POSTER_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(dp(LIST_POSTER_WIDTH_DP), dp(LIST_POSTER_HEIGHT_DP))
            }
        }

        val title = TextView(context).apply {
            textSize = TITLE_SP
            setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
            maxLines = if (grid) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = false
        }

        val meta = TextView(context).apply {
            textSize = META_SP
            setTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = false
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            layoutParams = if (grid) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = gap }
            }
        }
        column.addView(title)
        column.addView(
            meta,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        )

        root.addView(poster)
        root.addView(column)

        // 序号只在列表形态出现：网格每格本来就窄，再塞序号会把标题挤没。
        val indexPath = if (grid) {
            null
        } else {
            TextView(context).apply {
                textSize = META_SP
                setTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))
                maxLines = 1
                isFocusable = false
                setPadding(gap, 0, 0, 0)
            }
        }
        if (indexPath != null) root.addView(indexPath)

        return CardHolder(root, title, meta, indexPath)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        val card = items[position]
        holder.title.text = card.title
        holder.meta.text = card.meta
        holder.index?.text = context.getString(R.string.f041_card_index, position + 1, items.size)
        holder.itemView.contentDescription =
            context.getString(R.string.f041_card_a11y, position + 1, card.title, card.meta)
        holder.itemView.setOnClickListener { onPick(card, position) }
    }

    class CardHolder(
        root: View,
        val title: TextView,
        val meta: TextView,
        val index: TextView?
    ) : RecyclerView.ViewHolder(root)

    private fun dp(value: Int): Int = (context.resources.displayMetrics.density * value).toInt()

    private companion object {
        const val TYPE_GRID = 0
        const val TYPE_LIST = 1

        const val CARD_PADDING_DP = 10

        /** 网格封面高度：4 列时这一块占满格宽，卡片整体比列表行高，正好撑出可滚动的内容。 */
        const val GRID_POSTER_HEIGHT_DP = 150

        const val LIST_POSTER_WIDTH_DP = 72
        const val LIST_POSTER_HEIGHT_DP = 44

        const val TITLE_SP = 15f
        const val META_SP = 12f
    }
}
