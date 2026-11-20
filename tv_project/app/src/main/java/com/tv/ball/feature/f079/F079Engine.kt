package com.tv.ball.feature.f079

import android.content.Context
import android.content.SharedPreferences

/**
 * 引导里的一页。
 *
 * 三个字段都是**资源名的字符串**，不是 id：
 *   · [titleKey] 是 `res/values/strings_f079.xml` 里的 string 名，如 `f079_page_account_title`；
 *   · [bodyKey] 是同一份 strings 里的正文名；
 *   · [iconKey] 是 `res/drawable/` 里的图标名，如 `ic_nav_profile`。
 *
 * 为什么不直接存 `R.string.xxx`：本文件是要能在 JVM 单测里跑的纯逻辑（页序、翻页边界），
 * 一旦引用 `R` 就必须带上 Android 的生成类。界面层用 [F079Engine.page] 拿到 key，
 * 再用 `resources.getIdentifier(key, kind, packageName)` 换成真实资源 id——这条路径在
 * Activity 里只有一处，见 `F079Activity.resolve`。
 */
data class Page(
    val titleKey: String,
    val bodyKey: String,
    val iconKey: String
)

/**
 * 首启动引导的内容与翻页状态。
 *
 * 这一层只回答两件事：
 *   1. 一共有哪几页、每页讲什么（[pages] / [page]）；
 *   2. 现在停在第几页、能不能往前/往后（[F079Pager]）。
 *
 * 不碰 Activity、不碰 View，也不做「该不该弹」的判断——那是 [OnboardingState] 的活。
 */
object F079Engine {

    /** 页数。界面层的圆点数量、页码范围都从这里取，改文案加页时只改 [PAGES] 与本常量。 */
    const val PAGE_COUNT: Int = 5

    /**
     * 五页的顺序是刻意的：
     * 账号 → 权限 → 操作 → 功能在哪 → 数据在哪去。
     *
     * 先说「不用注册」，用户才愿意继续看；接着讲「只读权限」，把第一次启动最容易被吓到的
     * 系统弹窗提前解释掉；再教遥控器怎么按；然后告诉他功能散在功能中心；最后交代数据不出本机。
     * 反过来（先讲数据合规）就会在第一页流失。
     */
    private val PAGES: List<Page> = listOf(
        Page(
            titleKey = "f079_page1_title",
            bodyKey = "f079_page1_body",
            iconKey = "ic_nav_profile"
        ),
        Page(
            titleKey = "f079_page2_title",
            bodyKey = "f079_page2_body",
            iconKey = "ic_check_primary"
        ),
        Page(
            titleKey = "f079_page3_title",
            bodyKey = "f079_page3_body",
            iconKey = "ic_tv"
        ),
        Page(
            titleKey = "f079_page4_title",
            bodyKey = "f079_page4_body",
            iconKey = "ic_row_settings"
        ),
        Page(
            titleKey = "f079_page5_title",
            bodyKey = "f079_page5_body",
            iconKey = "ic_nav_local_video"
        )
    )

    /** 全部页面，顺序即展示顺序。 */
    fun pages(): List<Page> = PAGES

    /**
     * 取第 [index] 页，越界一律夹到合法范围。
     *
     * 夹取而不是抛异常：遥控器连按快进、配置里残留了旧页数都可能算出越界下标，
     * 这种事不该让页面崩，落到第一页或最后一页即可。
     */
    fun page(index: Int): Page = PAGES[index.coerceIn(0, PAGES.size - 1)]
}

/**
 * 翻页游标。
 *
 * 只有「当前页」一个可变状态，且只能通过 [next] / [prev] / [moveTo] / [reset] 改，
 * 所以界面层任何时候读 [currentIndex] 都是一个合法下标——不需要在 Activity 里再复制一份
 * `if (index < 0)` 的保护。
 *
 * [next] / [prev] 在边界上**不动**并返回原下标（不是循环翻）：引导页循环翻会让人分不清
 * 「已经看完一遍」和「又绕回第一页」，而代码只需要比较返回值决定要不要重画。
 */
class F079Pager(val pageCount: Int = F079Engine.PAGE_COUNT) {

    init {
        require(pageCount > 0) { "引导页至少要有一页" }
    }

    /** 当前页下标，0 起。 */
    var currentIndex: Int = 0
        private set

    /** 已经在第一页。 */
    val isFirst: Boolean
        get() = currentIndex == 0

    /** 已经在最后一页——此时「下一条」换成「开始使用」。 */
    val isLast: Boolean
        get() = currentIndex >= pageCount - 1

    /** 往后翻一页，已在最后一页则不动。返回翻完后的下标。 */
    fun next(): Int {
        if (!isLast) currentIndex++
        return currentIndex
    }

    /** 往前翻一页，已在第一页则不动。返回翻完后的下标。 */
    fun prev(): Int {
        if (!isFirst) currentIndex--
        return currentIndex
    }

    /** 跳到第 [index] 页（越界夹取），返回实际落点。 */
    fun moveTo(index: Int): Int {
        currentIndex = index.coerceIn(0, pageCount - 1)
        return currentIndex
    }

    /** 回到第一页，用于「重新查看引导」。 */
    fun reset(): Int {
        currentIndex = 0
        return currentIndex
    }
}

/**
 * 「引导是否已经过完」的本地状态。
 *
 * 只存一个权威键 [KEY_DONE]：`true` 就代表「不要再自动弹」。
 * 完成与跳过都写这个键——验收要的是「可跳过且不再重复弹出」，两者在「要不要再弹」
 * 这件事上没有区别；区别只体现在 [KEY_SKIPPED] 上，用来在页面里说清上次是「看完的」
 * 还是「跳过的」，以及在功能中心里把状态写成「已跳过」而不是「已完成」。
 *
 * 每个入口都收 [Context] 而不是自己持一个 Application 单例：
 * 首次启动的判定发生在主界面 onCreate 的早期，那时拿不到任何已初始化的静态字段，
 * 而 `applicationContext` 在任何时刻都可用，也不会有 Activity 泄漏。
 *
 * 存储用独立的 `f079_onboarding` 偏好文件，不写进全局 `video_player_prefs`：
 * 这一项是「一次性开关」，混进播放器设置里，将来做「恢复默认设置」时会被一起清掉，
 * 于是老用户升级后又看一遍引导——这不是我们想要的语义。
 */
object OnboardingState {

    /** 偏好文件名。 */
    const val PREFS_NAME: String = "f079_onboarding"

    /** 权威键：true = 引导已经过完（完成或跳过），不再自动弹出。 */
    const val KEY_DONE: String = "f079_onboarding_done"

    /** 辅助键：true = 上次是按「跳过」结束的。 */
    const val KEY_SKIPPED: String = "f079_onboarding_skipped"

    /** 还没过完 → 需要展示。首启动的入口读这一个方法就够。 */
    fun shouldShow(context: Context): Boolean = !isDone(context)

    /** 是否已经过完（完成或跳过）。 */
    fun isDone(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, false)

    /** 上次是不是跳过的。没过完时恒为 false。 */
    fun isSkipped(context: Context): Boolean =
        isDone(context) && prefs(context).getBoolean(KEY_SKIPPED, false)

    /** 看完了：置 done、清掉「跳过」标记。 */
    fun markDone(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_DONE, true)
            .putBoolean(KEY_SKIPPED, false)
            .apply()
    }

    /** 跳过了：同样置 done（不再自动弹），但留下跳过标记。 */
    fun markSkipped(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_DONE, true)
            .putBoolean(KEY_SKIPPED, true)
            .apply()
    }

    /**
     * 清空状态，让引导下次重新出现。
     *
     * 用 remove 而不是写 false：键不存在与 false 在读的时候等价，但「重置」的语义是
     * 回到出厂状态——文件里干干净净，将来排查「为什么又弹了」时一眼能看出是重置过。
     */
    fun reset(context: Context) {
        prefs(context).edit()
            .remove(KEY_DONE)
            .remove(KEY_SKIPPED)
            .apply()
    }

    /** 写用 apply()：引导页点完就 finish，主线程不做磁盘 IO。 */
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}