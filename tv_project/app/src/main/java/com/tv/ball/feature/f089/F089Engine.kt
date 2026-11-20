package com.tv.ball.feature.f089

/**
 * 长按一个列表条目能做的事。
 *
 * 四个动作覆盖"看一眼就要动手"的全部场景：播放、收藏、看详情、删掉。
 * 顺序就是菜单里的顺序，从最常用的播放往下排。
 */
enum class ContextAction {
    /** 从头播放。 */
    PLAY,

    /** 收藏 / 取消收藏：同一个动作，方向由条目当前状态决定（收藏过一次再选就是取消）。 */
    FAVORITE,

    /** 看信息：把标题、位置、时长、状态、简介一次展开。 */
    INFO,

    /** 标记删除 / 撤销删除：同样是双向动作，删除是"标记"而不是真删，随时能点回来。 */
    DELETE
}

/**
 * 列表里的一条媒体。
 *
 * 刻意做成不可变对象：每次操作都 [copy] 出一条新记录，页面整体替换。
 * 好处是"列表里显示的状态"永远等于"某一时刻的完整快照"，
 * 不会出现收藏写完一半、星标跟标题对不上的中间态。
 *
 * [deleted] 是**标记**不是物理删除：客厅电视上误触一下"删除"很常见，
 * 真要删文件是另一套流程，这里只负责把它从列表里置灰、标记出来。
 */
data class MediaItem(
    val id: String,
    val title: String,
    /** 副标题，一般是「第 N 集 · 分组名」。 */
    val subtitle: String,
    /** 时长展示文案，例如 `00:42:15`；保留成字符串，免得引擎替 UI 决定格式。 */
    val durationText: String,
    /** 简介，INFO 动作把它展开到页面上。 */
    val summary: String,
    val favorite: Boolean = false,
    val deleted: Boolean = false
)

/**
 * 一次操作造成的效果。页面据此决定 toast 文案，引擎自己不出文案。
 *
 * 为什么不直接在引擎里拼 toast 字符串：引擎要能脱离 Android 单测（纯 Kotlin），
 * 文案属于资源，放在 `strings_f089.xml` 里才能跟界面其它文字一起维护。
 */
enum class ChangeEffect {
    PLAY_REQUESTED,
    FAVORITE_ADDED,
    FAVORITE_REMOVED,
    INFO_READ,
    DELETE_MARKED,
    DELETE_UNDONE
}

/**
 * [F089Engine.apply] 的返回值：新条目 + 效果 + 是否真的改了状态 + 可选的详情文本。
 *
 * [changed] 单独给出来而不是让页面自己比 `item != old`：
 * 播放、看信息都不改状态，页面不用重新刷新行；收藏、删除改了，页面就得重画。
 */
data class ItemChange(
    val item: MediaItem,
    val effect: ChangeEffect,
    val changed: Boolean,
    val detail: String = ""
)

/**
 * F089「长按上下文菜单」的能力层。
 *
 * 只回答两件事：**这一条能出哪些菜单项**、**选了某一项之后条目变成什么样**。
 * 纯 Kotlin，不碰 Context / 资源 / 存储，可以脱离设备单测。
 *
 * 删除用"标记"而不是真删，收藏用"翻转"而不是两个动作，是这一层的两个核心约定：
 * 菜单项数量固定（四个），语义随条目状态变化，用户不用记"现在是收藏还是取消收藏"。
 */
object F089Engine {

    /**
     * 这一条当前能出哪些菜单项。
     *
     * 已标记删除的条目只剩「信息 / 撤销删除」：删除的东西还能播放、还能收藏，
     * 会让"删除"这个状态变得毫无意义。页面的点按（直接播放）同样按这条规则拦。
     */
    fun actionsFor(item: MediaItem): List<ContextAction> =
        if (item.deleted) {
            listOf(ContextAction.INFO, ContextAction.DELETE)
        } else {
            listOf(ContextAction.PLAY, ContextAction.FAVORITE, ContextAction.INFO, ContextAction.DELETE)
        }

    /**
     * 执行一个动作，返回新条目。
     *
     * 对任何 (动作, 条目) 组合都有确定结果、不抛异常——菜单是页面按 [actionsFor] 生成的，
     * 但遥控器上的重复按键、菜单打开期间列表被改过等边缘情况都可能送进一个"不该出现"的组合，
     * 这时候宁可按语义硬算一次，也不要让应用在这一屏崩掉。
     */
    fun apply(action: ContextAction, item: MediaItem): ItemChange = when (action) {
        ContextAction.PLAY -> ItemChange(item, ChangeEffect.PLAY_REQUESTED, changed = false)
        ContextAction.FAVORITE -> toggleFavorite(item)
        ContextAction.INFO -> ItemChange(
            item,
            ChangeEffect.INFO_READ,
            changed = false,
            detail = summaryOf(item)
        )
        ContextAction.DELETE -> toggleDeleted(item)
    }

    /** 收藏翻转。 */
    private fun toggleFavorite(item: MediaItem): ItemChange {
        val next = item.copy(favorite = !item.favorite)
        return ItemChange(
            item = next,
            effect = if (next.favorite) ChangeEffect.FAVORITE_ADDED else ChangeEffect.FAVORITE_REMOVED,
            changed = true
        )
    }

    /** 删除标记翻转（删除 ↔ 撤销删除）。 */
    private fun toggleDeleted(item: MediaItem): ItemChange {
        val next = item.copy(deleted = !item.deleted)
        return ItemChange(
            item = next,
            effect = if (next.deleted) ChangeEffect.DELETE_MARKED else ChangeEffect.DELETE_UNDONE,
            changed = true
        )
    }

    /**
     * INFO 动作展开的信息摘要。
     *
     * 用 `字段：值` 一行一条的朴素格式：电视上远看，对齐的短行比一段散文好扫；
     * 也方便用户截图发给别人对暗号。
     */
    fun summaryOf(item: MediaItem): String = buildString {
        append("标题：").append(item.title).append('\n')
        append("位置：").append(item.subtitle).append('\n')
        append("时长：").append(item.durationText).append('\n')
        append("收藏：").append(if (item.favorite) "已收藏" else "未收藏").append('\n')
        append("状态：").append(if (item.deleted) "已标记删除" else "可播放").append('\n')
        append("简介：").append(item.summary)
    }

    /**
     * 页面初始的 5 条示例数据。
     *
     * 故意混了三种状态（普通 / 已收藏 / 已标记删除），这样一进页面就能看到
     * 星标和置灰长什么样，不用先自己点两下才知道这个功能干了什么。
     */
    fun sampleItems(): List<MediaItem> = listOf(
        MediaItem(
            id = "f089-01",
            title = "海边的曼彻斯特",
            subtitle = "第 1 集 · 剧情",
            durationText = "02:17:30",
            summary = "中年维修工回到故乡处理哥哥的后事，被迫重新面对自己逃离多年的过去。",
            favorite = false,
            deleted = false
        ),
        MediaItem(
            id = "f089-02",
            title = "蓝色星球 · 深海",
            subtitle = "第 3 集 · 纪录片",
            durationText = "00:49:12",
            summary = "镜头下潜到一千米以下，记录极慢代谢与生物荧光构成的深海生态。",
            favorite = true,
            deleted = false
        ),
        MediaItem(
            id = "f089-03",
            title = "城市之光",
            subtitle = "第 1 集 · 喜剧",
            durationText = "01:32:08",
            summary = "默片时代的流浪汉与卖花女，一个关于善意如何被误读的故事。",
            favorite = false,
            deleted = false
        ),
        MediaItem(
            id = "f089-04",
            title = "未命名工程文件",
            subtitle = "第 2 集 · 缓存",
            durationText = "00:00:00",
            summary = "上次播放中断留下的空壳条目，没有可用的时长与简介。",
            favorite = false,
            deleted = true
        ),
        MediaItem(
            id = "f089-05",
            title = "四季 · 夏",
            subtitle = "第 4 集 · 音乐",
            durationText = "00:58:44",
            summary = "四把小提琴与一台古钢琴，把一场雷雨写成四个声部的对话。",
            favorite = true,
            deleted = false
        )
    )
}