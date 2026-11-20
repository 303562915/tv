package com.tv.ball.feature.f026

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F026「最近添加与最近播放」的能力层：纯 Kotlin，不碰 Android 框架。
 *
 * 只做四件事：按「文件最后修改时间」排出最近添加、按「最后播放时间」排出最近播放、
 * 维护可配置的上限档位、把时间点转成页面能直接显示的相对时间。
 *
 * 库本身从哪来（本地扫描 / 网络同步）由页面决定，这里只认 [MediaRef] 列表，
 * 所以排序、过滤、上限这些规则可以脱离 Android 单独验证；
 * 上限的落盘也在页面侧（SharedPreferences），Engine 不持有任何存储。
 */
object F026Engine {

    /**
     * 上限档位：只给三档。
     *
     * 遥控器一屏能点完，也顺手挡掉「1 条」这种没意义的配置和「9999 条」这种把首页拖垮的配置。
     */
    val LIMIT_OPTIONS: List<Int> = listOf(5, 10, 20)

    /** 没有任何已存偏好时的默认上限。 */
    const val DEFAULT_LIMIT: Int = 10

    private const val MINUTE_MS = 60L * 1000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS

    /** 超过这个跨度就不再报「几天前」，直接显示日期，免得「37 天前」这种读不出信息量的文案。 */
    private const val RELATIVE_DAY_LIMIT = 7L

    /** 序列化用的分隔符：挑不可见字符，标题或路径里出现竖线也不会把一条记录切成两半。 */
    private const val FIELD_SEP = "\u001F"
    private const val RECORD_SEP = "\u001E"

    /**
     * 库里的一项媒体。
     *
     * @param addedAtMs 文件最后修改时间（毫秒）。扫描入库时取的就是 mtime，「最近添加」按它降序。
     * @param lastPlayedAtMs 最后一次播放时间（毫秒）；0 表示从未播放。
     */
    data class MediaRef(
        val id: String,
        val title: String,
        val path: String,
        val addedAtMs: Long,
        val lastPlayedAtMs: Long
    ) {
        /** 是否播放过。最近播放要拿它把没播过的条目挡在外面。 */
        val isPlayed: Boolean get() = lastPlayedAtMs > 0L
    }

    /** 首页上的两个横向数据源。 */
    enum class Source { RECENTLY_ADDED, RECENTLY_PLAYED }

    // region 排序与上限

    /**
     * 把任意来源的上限值夹回档位内。
     *
     * 旧版本存过 0、手改过配置文件、或者以后档位改了，都会走到这里：
     * 小于等于 0 用默认值，档位之间取「够得着的最小档」，超上限取最大档。
     */
    fun normalizeLimit(limit: Int): Int = when {
        limit in LIMIT_OPTIONS -> limit
        limit <= 0 -> DEFAULT_LIMIT
        else -> LIMIT_OPTIONS.firstOrNull { it >= limit } ?: LIMIT_OPTIONS.last()
    }

    /**
     * 最近添加：按文件修改时间降序取前 [limit] 条。
     *
     * 同一毫秒入库的条目按标题升序兜底，保证同一份数据每次渲染出来的顺序都一样，
     * 否则遥控器焦点在两次刷新之间会跟着跳。
     */
    fun recentlyAdded(list: List<MediaRef>, limit: Int): List<MediaRef> = list
        .sortedWith(
            compareByDescending<MediaRef> { it.addedAtMs }
                .thenBy { it.title }
                .thenBy { it.id }
        )
        .take(normalizeLimit(limit))

    /** 最近播放：先滤掉从未播放（lastPlayedAtMs == 0）的条目，再按最后播放时间降序取前 [limit] 条。 */
    fun recentlyPlayed(list: List<MediaRef>, limit: Int): List<MediaRef> = list
        .asSequence()
        .filter { it.isPlayed }
        .sortedWith(
            compareByDescending<MediaRef> { it.lastPlayedAtMs }
                .thenBy { it.title }
                .thenBy { it.id }
        )
        .take(normalizeLimit(limit))
        .toList()

    /** 按数据源取一屏数据，页面只调这一个入口。 */
    fun select(list: List<MediaRef>, source: Source, limit: Int): List<MediaRef> = when (source) {
        Source.RECENTLY_ADDED -> recentlyAdded(list, limit)
        Source.RECENTLY_PLAYED -> recentlyPlayed(list, limit)
    }

    /** 该数据源在整库里有多少条（不受上限影响），摘要行要拿它和展示条数对照。 */
    fun count(list: List<MediaRef>, source: Source): Int = when (source) {
        Source.RECENTLY_ADDED -> list.size
        Source.RECENTLY_PLAYED -> list.count { it.isPlayed }
    }

    /** 把一条记录标成刚播放过，返回新列表；id 不在库里就原样返回，调用方不用自己判空。 */
    fun markPlayed(list: List<MediaRef>, id: String, playedAtMs: Long): List<MediaRef> =
        list.map { if (it.id == id) it.copy(lastPlayedAtMs = playedAtMs) else it }

    // endregion

    // region 示例库

    /**
     * 8 条示例库数据。
     *
     * 刻意留 2 条从未播放（lastPlayedAtMs = 0）：切到「最近播放」时它们会被过滤掉，
     * 两个数据源的差异一眼能看出来，而不是两份一模一样的列表。
     */
    fun sampleLibrary(nowMs: Long): List<MediaRef> = listOf(
        MediaRef("m01", "流浪地球 2", "/storage/emulated/0/Movies/liulangdiqiu2.mkv", nowMs - 2L * HOUR_MS, nowMs - 30L * MINUTE_MS),
        MediaRef("m02", "三体 S01E05", "/storage/emulated/0/Movies/santi_s01e05.mp4", nowMs - 5L * HOUR_MS, nowMs - 3L * HOUR_MS),
        MediaRef("m03", "蜘蛛侠：纵横宇宙", "/storage/emulated/0/Movies/spiderverse.mkv", nowMs - 6L * HOUR_MS, 0L),
        MediaRef("m04", "漫长的季节 S01E03", "/storage/emulated/0/Movies/manchang_s01e03.mp4", nowMs - 1L * DAY_MS, nowMs - 20L * HOUR_MS),
        MediaRef("m05", "狂飙 S01E12", "/storage/emulated/0/Movies/kuangbiao_s01e12.mp4", nowMs - 2L * DAY_MS, 0L),
        MediaRef("m06", "奥本海默", "/storage/emulated/0/Movies/oppenheimer.mp4", nowMs - 3L * DAY_MS, nowMs - 2L * DAY_MS),
        MediaRef("m07", "灌篮高手 THE FIRST", "/storage/emulated/0/Movies/slamdunk_the_first.mkv", nowMs - 5L * DAY_MS, nowMs - 4L * DAY_MS),
        MediaRef("m08", "熊出没·伴我熊芯", "/storage/emulated/0/Movies/xiongchumo.mp4", nowMs - 12L * DAY_MS, nowMs - 9L * DAY_MS)
    )

    // endregion

    // region 时间

    /** 相对时间的粒度。页面负责把 [Ago] 翻成文案，Engine 不碰资源字符串。 */
    enum class AgoUnit { NEVER, JUST_NOW, MINUTE, HOUR, DAY, DATE }

    /** 一个相对时间：[amount] 与 [unit] 组合成「N 分钟前」这类文案；DATE 用绝对日期。 */
    data class Ago(val amount: Long, val unit: AgoUnit)

    /**
     * 算出 thenMs 相对 nowMs 的相对时间。
     *
     * 未来时间（时钟被改过）按「刚刚」处理，不显示负数，
     * 超过一周的跨度交给 [formatDate]，页面直接显示日期。
     */
    fun ago(nowMs: Long, thenMs: Long): Ago {
        if (thenMs <= 0L) return Ago(0L, AgoUnit.NEVER)
        val delta = (nowMs - thenMs).coerceAtLeast(0L)
        return when {
            delta < MINUTE_MS -> Ago(0L, AgoUnit.JUST_NOW)
            delta < HOUR_MS -> Ago(delta / MINUTE_MS, AgoUnit.MINUTE)
            delta < DAY_MS -> Ago(delta / HOUR_MS, AgoUnit.HOUR)
            delta < RELATIVE_DAY_LIMIT * DAY_MS -> Ago(delta / DAY_MS, AgoUnit.DAY)
            else -> Ago(0L, AgoUnit.DATE)
        }
    }

    /** 绝对日期（本机时区）。每次现建 SimpleDateFormat：它不是线程安全的，共享实例会串结果。 */
    fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    // endregion

    // region 随 bundle 走

    /**
     * 把整库压成一行字符串，供页面在 onSaveInstanceState 里带走。
     *
     * 示例数据本身是可再生的，但用户在页面上改过的播放标记不可再生：旋转后回到原样会显得页面失忆，
     * 所以连库一起存；数据量是示例规模（个位数到几十条），放进 bundle 不会触发 TransactionTooLarge。
     */
    fun encode(list: List<MediaRef>): String = list.joinToString(RECORD_SEP) { item ->
        listOf(
            item.id,
            item.title,
            item.path,
            item.addedAtMs.toString(),
            item.lastPlayedAtMs.toString()
        ).joinToString(FIELD_SEP)
    }

    /** [encode] 的逆操作。字段数不对或时间戳解析失败的记录直接丢掉，不做半条记录还原。 */
    fun decode(text: String?): List<MediaRef> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(RECORD_SEP).mapNotNull { record ->
            val fields = record.split(FIELD_SEP)
            if (fields.size != 5) return@mapNotNull null
            val addedAt = fields[3].toLongOrNull() ?: return@mapNotNull null
            val lastPlayed = fields[4].toLongOrNull() ?: return@mapNotNull null
            MediaRef(
                id = fields[0],
                title = fields[1],
                path = fields[2],
                addedAtMs = addedAt,
                lastPlayedAtMs = lastPlayed
            )
        }
    }

    // endregion
}
