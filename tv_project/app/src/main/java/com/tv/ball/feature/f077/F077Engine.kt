package com.tv.ball.feature.f077

import java.util.Locale

/**
 * 一条可被搜索的设置项。
 *
 * [id] 是设置项自己的稳定 id，[title] 是页面上的标题，[aliases] 是用户「嘴上会怎么说」的同义词
 * （口语、英文缩写、常见误称），[group] 用来在结果里标出它属于哪一块设置，
 * [pageKey] 是这一项目前落在哪个功能页上（如 `F009`）——本功能只提示「该进哪一页」，不自己跳转。
 *
 * 五个字段都是纯字符串，没有任何 Android 依赖：搜索逻辑可以在 JVM 单测里直接跑。
 */
data class SettingItem(
    val id: String,
    val title: String,
    val aliases: List<String>,
    val group: String,
    val pageKey: String
) {
    /** 别名拼成一行，结果行第二行用它说明「还可以这么搜」。 */
    fun aliasText(): String = aliases.joinToString(SEPARATOR)

    private companion object {
        /** 别名之间的分隔符：全站文案里的并列习惯用这个间隔号。 */
        const val SEPARATOR = " · "
    }
}

/**
 * F077「设置项搜索」的能力层。
 *
 * 设置项散在各个功能页里，用户记得的是「倍速」「清缓存」「代理」这些词，不是它挂在哪个页面标题下。
 * 这一层做两件事：
 *   1. [catalog] 给出一份索引：20 个本项目真实存在的设置项，每条带标题、别名、分组与所属功能 id；
 *   2. [search] 在一个查询串上给出排序后的结果。
 *
 * 匹配规则（与验收口径一致）：
 *   · 忽略大小写、忽略所有空白（含全角空格）与分隔符（`-` `_` `/` `·`），
 *     所以「U S E R agent」「user-agent」「useragent」等价；
 *   · 排序优先级：标题前缀 > 标题包含 > 别名前缀 > 别名包含；
 *   · 同一档内保持 [catalog] 的原始顺序（Kotlin 的 sortedWith 是稳定排序），
 *     也就是「分组 → 功能 id」的固定次序，同一档里不会每敲一个字就换位置；
 *   · 空查询返回整份索引：进页面先把全部设置项摊开，用户才知道能搜什么。
 *
 * 这里只做索引与匹配，不持有 Context、不读资源、不写任何状态；文案的字面量是有意为之——
 * 和 F076Module 的 title / description 一样，索引要能在没有 Application 初始化时被构造出来。
 */
object F077Engine {

    /** 索引规模：验收要求「索引全部设置项」，这里就是 [catalog] 的条数，测试直接比这个常量。 */
    const val CATALOG_SIZE: Int = 20

    /** 标题前缀命中的档位，最靠前。 */
    private const val SCORE_TITLE_PREFIX = 0

    /** 标题包含命中的档位。 */
    private const val SCORE_TITLE_CONTAINS = 1

    /** 别名前缀命中的档位：用户敲的缩写正好是某个别名的开头。 */
    private const val SCORE_ALIAS_PREFIX = 2

    /** 别名包含命中的档位，排在最后。 */
    private const val SCORE_ALIAS_CONTAINS = 3

    /** 没命中。 */
    private const val SCORE_NONE = -1

    /** 归一化时当作排版、不当语义的字符：空白之外的分隔符。 */
    private val SEPARATORS: Set<Char> = setOf('-', '_', '/', '·', '／')

    /**
     * 全部设置项索引。
     *
     * 每条都能在功能中心里找到对应页面，[pageKey] 就是那个页面的功能 id；
     * 顺序按「分组 → 功能 id」排，界面在空查询时原样展示。
     */
    fun catalog(): List<SettingItem> = CATALOG

    /**
     * 在 [items] 上搜 [query]。
     *
     * 返回的是新列表，不改动入参；空查询（或只有空白）返回入参本身，
     * 这样界面层「清空搜索框」就是简单地把整份索引交回来。
     */
    fun search(items: List<SettingItem>, query: String): List<SettingItem> {
        val needle = normalize(query)
        if (needle.isEmpty()) return items

        return items.withIndex()
            .mapNotNull { indexed ->
                val score = scoreOfNormalized(indexed.value, needle)
                if (score == SCORE_NONE) null else Ranked(indexed.value, score, indexed.index)
            }
            .sortedWith(compareBy({ it.score }, { it.index }))
            .map { it.item }
    }

    /**
     * 归一化：去掉空白与分隔符 + 转小写。
     *
     * 分隔符也要去，是因为索引里的别名按书写习惯带连字符（`user-agent`），
     * 而用户嘴上敲的是「user agent」——只去空格的话这两串永远对不上。
     * 同理 `_` / `/` / `·` 只当排版，不当语义。
     *
     * 只做这些，是有意的——再加「全角转半角」「拼音归一化」会让「为什么搜不到」变得难解释；
     * 拼音首字母是 F046 的活，这里不重复实现。
     */
    fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() || it in SEPARATORS }.lowercase(Locale.ROOT)

    /** 命中档位，供界面层做高亮或提示用；没命中返回 [SCORE_NONE]。 */
    fun scoreOf(item: SettingItem, needle: String): Int {
        val normalized = normalize(needle)
        if (normalized.isEmpty()) return SCORE_TITLE_PREFIX
        return scoreOfNormalized(item, normalized)
    }

    // ===== 内部 =====

    /** [needle] 必须是已归一化的查询串，这里只归一化被搜的标题与别名。 */
    private fun scoreOfNormalized(item: SettingItem, needle: String): Int {
        val title = normalize(item.title)
        if (title.startsWith(needle)) return SCORE_TITLE_PREFIX
        if (title.contains(needle)) return SCORE_TITLE_CONTAINS
        item.aliases.forEach { alias ->
            val normalizedAlias = normalize(alias)
            if (normalizedAlias.isEmpty()) return@forEach
            if (normalizedAlias.startsWith(needle)) return SCORE_ALIAS_PREFIX
            if (normalizedAlias.contains(needle)) return SCORE_ALIAS_CONTAINS
        }
        return SCORE_NONE
    }

    /** 排序中间态：档位 + 原始下标，保证同档稳定。 */
    private data class Ranked(val item: SettingItem, val score: Int, val index: Int)

    /**
     * 20 个设置项。分组名与功能中心的分组对齐（播放核心 / 字幕 / 界面 / 设置 / 网络 / 诊断 / 存储 / 无障碍），
     * 每条的 pageKey 都是本项目已注册的功能 id。
     */
    private val CATALOG: List<SettingItem> = listOf(
        // ===== 设置 =====
        SettingItem(
            id = "playback_defaults",
            title = "播放默认值中心",
            aliases = listOf("默认值", "默认设置", "初始倍速", "默认字幕", "恢复默认"),
            group = "设置",
            pageKey = "F081"
        ),
        SettingItem(
            id = "reduce_motion",
            title = "减弱动效",
            aliases = listOf("动画", "动效", "关动画", "转场", "性能优先"),
            group = "设置",
            pageKey = "F083"
        ),
        SettingItem(
            id = "privacy_mode",
            title = "隐私模式",
            aliases = listOf("隐私", "隐藏记录", "不记录历史", "本地密码"),
            group = "设置",
            pageKey = "F080"
        ),
        SettingItem(
            id = "about",
            title = "关于页与更新",
            aliases = listOf("关于", "版本号", "版本", "检查更新", "更新渠道", "下载页", "源码仓库"),
            group = "设置",
            pageKey = "F086"
        ),

        // ===== 播放核心 =====
        SettingItem(
            id = "seek_step",
            title = "快进快退步长策略",
            aliases = listOf("快进", "快退", "步长", "跳转秒数", "快进10秒"),
            group = "播放核心",
            pageKey = "F009"
        ),
        SettingItem(
            id = "speed_curve",
            title = "倍速曲线与长按倍速",
            aliases = listOf("倍速", "加速", "调速", "长按加速", "2倍速"),
            group = "播放核心",
            pageKey = "F004"
        ),
        SettingItem(
            id = "gesture_sensitivity",
            title = "手势灵敏度配置",
            aliases = listOf("手势", "滑动灵敏度", "双击", "手势调节"),
            group = "播放核心",
            pageKey = "F010"
        ),
        SettingItem(
            id = "av_sync",
            title = "音画同步微调",
            aliases = listOf("音画不同步", "唇音同步", "字幕延迟", "音轨延迟"),
            group = "播放核心",
            pageKey = "F008"
        ),

        // ===== 字幕 =====
        SettingItem(
            id = "subtitle_style",
            title = "字幕样式设置",
            aliases = listOf("字幕字体", "字号", "字幕颜色", "描边", "字幕位置"),
            group = "字幕",
            pageKey = "F012"
        ),

        // ===== 界面 =====
        SettingItem(
            id = "view_mode",
            title = "视图模式切换",
            aliases = listOf("列表视图", "网格", "海报墙", "视图"),
            group = "界面",
            pageKey = "F041"
        ),
        SettingItem(
            id = "focus_memory",
            title = "焦点记忆",
            aliases = listOf("焦点", "记忆焦点", "上次位置", "回到上次"),
            group = "界面",
            pageKey = "F042"
        ),
        SettingItem(
            id = "remote_keys",
            title = "遥控器按键自定义",
            aliases = listOf("按键映射", "遥控器", "快捷键", "自定义按键"),
            group = "界面",
            pageKey = "F088"
        ),

        // ===== 网络 =====
        SettingItem(
            id = "proxy",
            title = "代理配置",
            aliases = listOf("代理", "proxy", "http代理", "socks", "翻墙"),
            group = "网络",
            pageKey = "F056"
        ),
        SettingItem(
            id = "doh",
            title = "自定义 DoH 解析",
            aliases = listOf("doh", "dns", "加密dns", "域名解析"),
            group = "网络",
            pageKey = "F055"
        ),
        SettingItem(
            id = "request_headers",
            title = "请求头配置",
            aliases = listOf("user-agent", "ua", "请求头", "header", "referer"),
            group = "网络",
            pageKey = "F059"
        ),
        SettingItem(
            id = "buffer_policy",
            title = "缓冲与并发策略",
            aliases = listOf("缓冲", "并发", "预加载", "卡顿", "缓冲大小"),
            group = "网络",
            pageKey = "F057"
        ),

        // ===== 诊断 =====
        SettingItem(
            id = "log_level",
            title = "日志级别与导出",
            aliases = listOf("日志", "log", "debug", "导出日志", "日志级别"),
            group = "诊断",
            pageKey = "F084"
        ),

        // ===== 存储 =====
        SettingItem(
            id = "cache_clean",
            title = "缓存分类清理",
            aliases = listOf("清缓存", "清理", "缓存", "释放空间"),
            group = "存储",
            pageKey = "F094"
        ),
        SettingItem(
            id = "storage_usage",
            title = "存储占用分析",
            aliases = listOf("占用", "空间", "体积", "存储", "谁占了"),
            group = "存储",
            pageKey = "F076"
        ),

        // ===== 无障碍 =====
        SettingItem(
            id = "a11y_audit",
            title = "无障碍描述审计",
            aliases = listOf("无障碍", "读屏", "contentdescription", "语音播报"),
            group = "无障碍",
            pageKey = "F087"
        )
    )
}
