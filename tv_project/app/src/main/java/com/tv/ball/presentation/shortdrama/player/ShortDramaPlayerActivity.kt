package com.tv.ball.presentation.shortdrama.player

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.ball.databinding.ActivityShortDramaPlayerBinding
import com.tv.ball.presentation.shortdrama.ShortDramaProgressStore

/**
 * 竖屏短剧播放器（抖音 / 红果风）。
 *
 * 分工
 * ----
 * - 这一层只管「跨集」的东西：队列、当前第几页、倍速、线路、点赞数、全集播完；
 * - 单集页面里的画面/手势/进度条在 [ShortDramaPlayerPageHolder]；
 * - 播放器实例的生命周期由 [ShortDramaPlaybackEngine] 的池子决定，界面只借还。
 *
 * 为什么用 ViewPager2 而不是上下两层 View + 手势
 * ----------------------------------------------
 * 竖屏连播的本质就是「一屏一集的分页」，ViewPager2 自带惯性、回弹、边界不越界、
 * 以及页面回收（滑走的页把手机关掉），自己实现要重写一遍这些细节还容易出手势冲突。
 *
 * 进度双写
 * --------
 * [ShortDramaPositionStore] 记「这一集看到第几秒」（切页回来接着看），
 * [ShortDramaProgressStore] 记「这部剧看到第几集」（列表页 / 详情页的继续观看）。
 * 两者都在 [recordProgress] 里一起写，避免出现「列表显示第 12 集、点进去却是第 1 集」。
 */
class ShortDramaPlayerActivity : AppCompatActivity(), ShortDramaPlayerHost {

    companion object {
        private const val TAG = "ShortDramaPlayer"

        /** 点击循环的倍速档位。0.75x 放最后：慢放是少数需求，别挡住常用的加速 */
        private val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
        private val SPEED_LABELS = arrayOf("1.0x", "1.25x", "1.5x", "2.0x", "0.75x")
    }

    private lateinit var binding: ActivityShortDramaPlayerBinding
    private lateinit var engine: ShortDramaPlaybackEngine
    private lateinit var queue: ShortDramaQueue

    private var adapter: ShortDramaPlayerAdapter? = null
    private var pagerCallbackRegistered = false

    // 名字避开 host 接口的 bookId()：属性和同名函数放在一个类里读起来容易走眼，加个后缀区分
    private var dramaBookId = ""
    private var title = ""
    private var cover = ""
    private var providerKey = ""

    private var speedIndex = 0

    /** 点赞只做本地交互占位（不落库、不上报），跨页共享，滑走再滑回来数字不归零 */
    private var likes = 0

    /** 前台标记：切页 + 前后台共同决定「这一页要不要播」 */
    private var hostResumed = false

    // -----------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式必须在 super.onCreate 之前应用，否则主题已经解析完了，切了也不会变
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)

        // 竖屏写死在代码里：入口页漏配 manifest 也不会横成一半黑一半画面
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        binding = ActivityShortDramaPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 主题的 windowBackground 是浅色的，启动瞬间会闪一下白
        window.setBackgroundDrawableResource(android.R.color.black)
        setupImmersive()
        applyBackButtonInsets()

        readIntent()
        if (queue.isEmpty) {
            // 空队列不是崩溃场景：给一句话就退出，别停在纯黑页面上让用户以为卡死
            Toast.makeText(this, "这一集没有可用的播放地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        engine = ShortDramaPlayback.create(this)
        setupPager()
        setupControls()
    }

    private fun readIntent() {
        dramaBookId = intent.getStringExtra(ShortDramaPlayerExtras.BOOK_ID)?.trim().orEmpty()
        title = intent.getStringExtra(ShortDramaPlayerExtras.TITLE)?.trim().orEmpty()
        cover = intent.getStringExtra(ShortDramaPlayerExtras.COVER)?.trim().orEmpty()
        // providerKey 就是 bookId 里 ":" 之前那段（如 "360zy"），跟详情页的口径一致
        providerKey = if (dramaBookId.isEmpty()) "" else dramaBookId.substringBefore(":")

        val lines = ShortDramaLinesCodec.decode(
            intent.getStringExtra(ShortDramaPlayerExtras.LINES_JSON)
        )
        queue = ShortDramaQueue(
            lines = lines,
            startLine = intent.getIntExtra(ShortDramaPlayerExtras.START_LINE, 0),
            startIndex = intent.getIntExtra(ShortDramaPlayerExtras.START_INDEX, 0)
        )
    }

    override fun onResume() {
        super.onResume()
        hostResumed = true
        // 从后台回来：把当前页重新激活（可能刚被 onPause 停掉）
        if (::queue.isInitialized) applyActivePage(binding.viewPager.currentItem)
    }

    override fun onPause() {
        // 先落进度再暂停：有些机型 pause 之后 player 的 currentPosition 会提前归零
        saveCurrentProgress()
        hostResumed = false
        adapter?.forEachHolder { it.setActive(false) }
        super.onPause()
    }

    override fun onStop() {
        // 兜底再写一次 + 再停一次：返回上一层时声音不能残留
        saveCurrentProgress()
        adapter?.forEachHolder { it.setActive(false) }
        super.onStop()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            if (pagerCallbackRegistered) {
                binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
                pagerCallbackRegistered = false
            }
            saveCurrentProgress()
            // 顺序很重要：先把每个页面的实例还给池子，再让池子统一释放
            adapter?.releaseAll()
            adapter = null
        }
        if (::engine.isInitialized) engine.shutdown()
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // 外壳：沉浸式 / 翻页 / 按钮
    // -----------------------------------------------------------------

    private fun setupImmersive() {
        // 边到边 + 隐状态栏导航栏：竖屏视频页上多一条状态栏就是浪费画面
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val controller = WindowCompat.getInsetsController(window, binding.root)
        // 下滑可以临时唤出系统栏，但不会把页面布局顶动
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    /**
     * 返回按钮在左上角，得躲开刘海和（临时唤出的）状态栏。
     * 只给这一个控件加顶部间距，而不是给整页加 padding：整页加会把视频画面挤小。
     */
    private fun applyBackButtonInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.backBtn) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            val target = dp(12) + bars.top
            if (params.topMargin != target) {
                params.topMargin = target
                view.layoutParams = params
            }
            insets
        }
    }

    private fun setupPager() {
        val pagerAdapter = ShortDramaPlayerAdapter(this, engine, queue.items)
        adapter = pagerAdapter

        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        // 1 页就够：下一集提前 prepare，滑过去不用等黑屏；再多纯属浪费内存
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.setCurrentItem(queue.index, false)

        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
        pagerCallbackRegistered = true
    }

    private fun setupControls() {
        binding.backBtn.setOnClickListener { finish() }

        binding.btnReplay.setOnClickListener {
            hideFinishOverlay()
            currentHolder()?.replayCurrent()
        }
        // 「返回详情」在本页面就是 finish：播放器的唯一入口是详情页
        binding.btnBackDetail.setOnClickListener { finish() }

        // 浮层整层可点 = 直接回到播放；面板吃掉点击，避免点按钮时穿透把浮层关掉
        binding.finishOverlay.setOnClickListener { hideFinishOverlay() }
        binding.finishPanel.setOnClickListener { }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            // 队列的当前集跟着页面走，换线对齐、进度写入都依赖它
            queue.moveTo(position)
            hideFinishOverlay()
            applyActivePage(position)
        }
    }

    /** 只让一页在播：其它页暂停并落进度 */
    private fun applyActivePage(position: Int) {
        adapter?.forEachHolder { holder ->
            holder.setActive(hostResumed && holder.pagePosition == position)
        }
    }

    private fun currentHolder(): ShortDramaPlayerPageHolder? {
        if (!::binding.isInitialized) return null
        return adapter?.holderAt(binding.viewPager.currentItem)
    }

    private fun saveCurrentProgress() {
        // 页面还没布局完成时没有 holder，此时位置一定是 0，
        // 硬写会把用户之前的断点清成「从头播」，所以直接跳过
        currentHolder()?.savePosition()
    }

    private fun showFinishOverlay() {
        binding.tvFinishTitle.text = "已看完全集"
        binding.finishOverlay.visibility = View.VISIBLE
    }

    private fun hideFinishOverlay() {
        binding.finishOverlay.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -----------------------------------------------------------------
    // 倍速 / 换线路 / 选集
    // -----------------------------------------------------------------

    /** 循环切换：1.0x -> 1.25x -> 1.5x -> 2.0x -> 0.75x -> 1.0x */
    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val speed = SPEEDS[speedIndex]
        adapter?.forEachHolder { holder ->
            holder.setSpeed(speed)
            holder.refreshLabels()
        }
    }

    private fun switchLineTo(target: Int) {
        if (target == queue.lineIndex) return
        saveCurrentProgress()

        // switchLine 内部按集号对齐，返回线路内的新下标；-1 表示这条线路没内容，保持原样
        val newIndex = queue.switchLine(target)
        if (newIndex < 0) {
            Toast.makeText(this, "这条线路暂时不可用", Toast.LENGTH_SHORT).show()
            return
        }

        // 换线等于整条分集列表都换了。ViewPager2 对「item 数变化」很敏感，
        // 直接换一个 adapter 比 notifyDataSetChanged 更不容易把旧页面的实例留在播放状态。
        val newAdapter = ShortDramaPlayerAdapter(this, engine, queue.items)
        adapter?.releaseAll()
        adapter = newAdapter
        binding.viewPager.adapter = newAdapter
        binding.viewPager.setCurrentItem(newIndex, false)
        hideFinishOverlay()
        // 换 adapter 时 ViewPager2 可能补派一次 onPageSelected(0)，把队列的当前集打回第 0。
        // 那个补派是下一帧才来的，所以对齐也必须放到下一帧——switchLine 已经把 index 设成
        // newIndex，这里直接 moveTo 只会是空操作，等于没对齐。
        binding.viewPager.post {
            val settled = binding.viewPager.currentItem
            queue.moveTo(settled)
            applyActivePage(settled)
        }
    }

    private fun showLinePicker() {
        val names = queue.lineNames()
        if (names.size <= 1) return
        val current = queue.lineIndex
        val items = names.mapIndexed { index, name ->
            if (index == current) "$name（当前）" else name
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("选择线路")
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                switchLineTo(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openEpisodeSheet(position: Int) {
        // 面板还开着就别再叠一个（连点两次选集按钮时会发生）
        if (supportFragmentManager.findFragmentByTag(ShortDramaEpisodeSheet.TAG) != null) return

        val nums = queue.items.map { it.episodeNum }.toIntArray()
        val sheet = ShortDramaEpisodeSheet.newInstance(
            lineName = queue.lineName,
            episodeNums = nums,
            currentIndex = position
        )
        sheet.onEpisodeClick = { index ->
            binding.viewPager.setCurrentItem(index, true)
        }
        sheet.show(supportFragmentManager, ShortDramaEpisodeSheet.TAG)
    }

    // -----------------------------------------------------------------
    // ShortDramaPlayerHost
    // -----------------------------------------------------------------

    override fun bookId(): String = dramaBookId

    override fun dramaTitle(): String = title

    // 「共 M 集」按全剧集数（各线路里最多的那条）算，不用当前线路的集数：
    // 换到集数更少的线路时这个词不该跟着变小，否则跟详情页、进度记录的口径就打架了
    override fun totalEpisodes(): Int =
        if (::queue.isInitialized) (queue.lines.maxOfOrNull { it.items.size } ?: 0) else 0

    override fun lineNameText(): String = if (::queue.isInitialized) queue.lineName else ""

    override fun hasOtherLines(): Boolean = ::queue.isInitialized && queue.lines.size > 1

    override fun playbackSpeed(): Float = SPEEDS[speedIndex]

    override fun speedLabel(): String = SPEED_LABELS[speedIndex]

    override fun likeCount(): Int = likes

    override fun isActivePage(position: Int): Boolean =
        hostResumed && ::binding.isInitialized && position == binding.viewPager.currentItem

    override fun onOpenEpisodes(position: Int) = openEpisodeSheet(position)

    override fun onSpeedClick(position: Int) = cycleSpeed()

    override fun onSwitchLineClick(position: Int) = showLinePicker()

    override fun onLikeTapped(position: Int): Int {
        likes += 1
        return likes
    }

    override fun onEpisodeEnded(position: Int) {
        if (isFinishing || isDestroyed) return
        // 播完的页可能已经不是当前页了（用户在这集收尾的瞬间滑走了）：
        // 这时候再 setCurrentItem(position + 1) 会把用户从正在看的那一集强行拉回下一集
        if (binding.viewPager.currentItem != position) return

        if (position < queue.size - 1) {
            // 连播：短剧一集一两分钟，播完自动滑到下一集比让用户每次手动滑更顺
            binding.viewPager.setCurrentItem(position + 1, true)
        } else {
            // 最后一集播完：给「已看完全集」，而不是把画面停在最后一帧上让人猜
            showFinishOverlay()
        }
    }

    override fun recordProgress(item: ShortDramaQueueItem, positionMs: Long, durationMs: Long) {
        // bookId 为空说明入口页没传（比如从别的列表直接点进来），
        // 这时候写进度只会在 prefs 里留下一堆空 key，宁愿不记
        if (dramaBookId.isBlank() || !::queue.isInitialized) return
        runCatching {
            ShortDramaPositionStore.save(dramaBookId, item.episodeNum, positionMs, durationMs)
            ShortDramaProgressStore.record(
                bookId = dramaBookId,
                title = title,
                cover = cover,
                episodeNum = item.episodeNum,
                totalEpisodes = queue.size,
                providerKey = providerKey
            )
        }.onFailure {
            Log.w(TAG, "记录播放进度失败: ${it.message}")
        }
    }
}
