package com.tv.ball.feature.f090

/**
 * 列表页的四种状态。
 *
 * 全站列表（收藏、历史、片源、搜索结果）实际上只有这四个状态在循环：
 * 进来时加载、加载完可能一条都没有、可能失败、也可能有内容。把它们收成一个枚举之后，
 * 页面只需要回答「这个状态显示哪块视图」，不用每个列表页各自拼可见性判断。
 *
 * 顺序有意义：[LOADING] 永远最先被判定，见 [F090Engine.decide]。
 */
enum class ListState {
    /** 请求在飞：只显示骨架屏，不显示任何「空 / 错」文案。 */
    LOADING,

    /** 请求成功但一条都没有：空态（图标 + 主副文案 + 重新加载）。 */
    EMPTY,

    /** 请求失败：错误态（图标 + 主副文案 + 重试）。 */
    ERROR,

    /** 有数据：真实列表。 */
    CONTENT
}

/**
 * F090「骨架屏与空态组件」的能力层。
 *
 * 这一层只回答两个问题：**现在该是哪个状态**、**这个状态该用哪份文案**。
 *
 * 为什么值得单独一层：四态判断看起来只有几个 if，但每个列表页都自己写一遍的结果是
 * 判定顺序各不相同——有的先判空再判错，于是在"请求失败且列表为空"时先闪一下
 * 「还没有内容」再变成「加载失败」；有的把"正在重试"仍然当成失败，错误面板压着骨架屏不放。
 * 把顺序固定进 [decide]，全站列表就只剩一套行为。
 *
 * 引擎**不引用 R**：文案以字符串键（[copyKey] / [labelKey]）的形式给出，
 * 由页面翻成资源 id。好处有两个：这一层能脱离 Android 单测；
 * 组件被别的模块复用时，接一套自己的 strings 就行，不用改引擎。
 */
object F090Engine {

    /**
     * 骨架屏占位块个数。
     *
     * 布局里是逐块声明的（每块一个 id，方便错开动画的 startDelay），
     * 这里留一份权威计数给页面和注释对账：改布局时数一下，别悄悄少一块。
     */
    const val SKELETON_BLOCK_COUNT: Int = 5

    /**
     * 演示用：加载态停留时长。
     *
     * 真实列表页不需要这个常量——骨架屏什么时候撤，由请求回调决定。
     * 这里给 1.5 秒，是为了在功能演示页里把「骨架屏 → 内容」整条路径演完整。
     */
    const val LOADING_HOLD_MS: Long = 1_500L

    // ===== 文案键 =====

    /**
     * 状态文案键。
     *
     * 只承诺「同一个状态永远给出同一个键」，键本身怎么翻成资源由页面决定
     * （本页翻成 f090_copy_*，别的页面可以翻成自己的文案）。
     * 值是纯 ASCII 短词，方便日志和单测里直接比对，不要塞 UI 文案进来。
     */
    const val KEY_LOADING: String = "loading"

    /** 空态文案键。 */
    const val KEY_EMPTY: String = "empty"

    /** 错误态文案键。 */
    const val KEY_ERROR: String = "error"

    /** 内容态文案键。 */
    const val KEY_CONTENT: String = "content"

    // ===== 状态判定 =====

    /**
     * 由三个信号决定该显示哪个状态。
     *
     * 判定顺序刻意写成"加载 > 错误 > 空 > 有内容"，每条都有理由：
     *
     * 1. [isLoading] 最优先：用户点了重试、请求刚发出时，上一轮的错误字符串还在手上，
     *    这时候必须让骨架屏盖住错误面板，否则页面会一直停在「加载失败」上不动。
     * 2. 其次看 [error]：请求真的失败了，就该显示错误态，而不是"空列表"——
     *    后者会让用户以为是自己没有收藏，跑去别处找数据。
     *    空串与全空白串都按"没有错误"处理：上游经常用 `""` 表示正常。
     * 3. 再看 [itemCount]：负数、0 都是空。负数按空处理而不是抛异常，
     *    是因为个别接口的 total 字段会给出 -1 表示"未知"。
     * 4. 其余都是 [ListState.CONTENT]。
     *
     * 纯函数，不碰 Context / 不碰磁盘，可以直接单测这四个分支。
     */
    fun decide(itemCount: Int, isLoading: Boolean, error: String?): ListState = when {
        isLoading -> ListState.LOADING
        !error.isNullOrBlank() -> ListState.ERROR
        itemCount <= 0 -> ListState.EMPTY
        else -> ListState.CONTENT
    }

    /** 这个状态对应的文案键；页面拿它去查自己的资源表。 */
    fun copyKey(state: ListState): String = when (state) {
        ListState.LOADING -> KEY_LOADING
        ListState.EMPTY -> KEY_EMPTY
        ListState.ERROR -> KEY_ERROR
        ListState.CONTENT -> KEY_CONTENT
    }

    /**
     * 状态名的资源键（`f090_state_loading` 这种形状）。
     *
     * 和 [copyKey] 分开：一个给页头/日志用的短标签，一个给空态面板的主副文案用。
     * 合成一个键也能用，但列表页把状态名打到日志里时不该顺带把整段文案也拖进去。
     */
    fun labelKey(state: ListState): String = when (state) {
        ListState.LOADING -> "f090_state_loading"
        ListState.EMPTY -> "f090_state_empty"
        ListState.ERROR -> "f090_state_error"
        ListState.CONTENT -> "f090_state_content"
    }

    // ===== 视图分发 =====

    /** 这个状态要不要显示骨架屏。只有 [ListState.LOADING] 显示。 */
    fun showsSkeleton(state: ListState): Boolean = state == ListState.LOADING

    /**
     * 这个状态要不要显示「图标 + 主副文案」那块面板。
     *
     * 空态和错误态共用同一块面板、只换图标与文案，是为了保证两种"没有内容"的观感一致：
     * 用户在不同页面看到的是同一个位置、同一个按钮位置，不用重新找。
     */
    fun usesIconPanel(state: ListState): Boolean =
        state == ListState.EMPTY || state == ListState.ERROR

    /** 这个状态要不要显示真实列表（本页用一段占位文案代替真实 RecyclerView）。 */
    fun showsContent(state: ListState): Boolean = state == ListState.CONTENT
}
