package com.tv.ball.presentation.shortdrama.player

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 竖屏短剧播放器（抖音 / 红果风）的共享契约。
 *
 * 为什么单独放一个文件
 * --------------------
 * 新播放器和老的 [com.tv.ball.presentation.player.VideoPlayerActivity] 完全解耦：
 * 详情页 / 列表页只负责「把全部分集 + 起始位置装进 Intent」，播放器只负责「竖屏连播」。
 * 两边共用的东西（队列结构、JSON 编解码、Intent extra 名、进度存储）集中在这里，
 * 避免各写一份 JSON 拼装逻辑后对不上。
 *
 * 这个文件不包含任何播放器实现：ExoPlayer 实例池在 ShortDramaPlayerEngine 里，
 * 界面在 ShortDramaPlayerActivity 里。这里只有纯数据 + 纯逻辑，方便静态检查。
 */

// ---------------------------------------------------------------------------
// 数据模型
// ---------------------------------------------------------------------------

/** 一集。episodeNum 用于「第 N 集」展示与进度记录；url 是采集源给的直链（通常是 m3u8）。 */
data class ShortDramaQueueItem(
    val episodeNum: Int,
    val label: String,
    val url: String
)

/** 一条线路（采集源的一套分集）。不同线路同一集的直链不同，坏源时可以原地换线。 */
data class ShortDramaQueueLine(
    val name: String,
    val items: List<ShortDramaQueueItem>
)

// ---------------------------------------------------------------------------
// Intent 协议：短剧详情页 / 列表页 → 新播放器
// ---------------------------------------------------------------------------

/**
 * 新播放器的入参。
 *
 * 不再沿用老播放器的 `VIDEO_URL` + `EPISODES_JSON`（那是「单集 + 全集列表」的老模型），
 * 这里直接给「全部线路 + 每条线路的全部分集」，播放器自己就有换线能力，
 * 播放过程中失败可以原地切源，不用退回详情页。
 */
object ShortDramaPlayerExtras {

    /** 剧 id（形如 "360zy:12345"），只用于记录观看进度，可为空（为空则不记） */
    const val BOOK_ID = "SD_BOOK_ID"

    /** 剧名，用于顶栏与进度记录 */
    const val TITLE = "SD_TITLE"

    /** 封面，用于进度记录与选集面板 */
    const val COVER = "SD_COVER"

    /** 全部线路的 JSON，见 [ShortDramaLinesCodec.encode] */
    const val LINES_JSON = "SD_LINES_JSON"

    /** 起播线路下标，默认 0 */
    const val START_LINE = "SD_START_LINE"

    /** 起播集下标（线路内的下标，不是集号），默认 0 */
    const val START_INDEX = "SD_START_INDEX"

    /** 组装 Intent。入口页只调这一个方法，不自己拼 extra。 */
    fun newIntent(
        context: Context,
        bookId: String,
        title: String,
        cover: String,
        lines: List<ShortDramaQueueLine>,
        startLine: Int = 0,
        startIndex: Int = 0
    ): Intent = Intent(context, ShortDramaPlayerActivity::class.java).apply {
        putExtra(BOOK_ID, bookId)
        putExtra(TITLE, title)
        putExtra(COVER, cover)
        putExtra(LINES_JSON, ShortDramaLinesCodec.encode(lines))
        putExtra(START_LINE, startLine)
        putExtra(START_INDEX, startIndex)
    }
}

// ---------------------------------------------------------------------------
// JSON 编解码（入口页与播放器共用，保证格式一致）
// ---------------------------------------------------------------------------

object ShortDramaLinesCodec {

    private const val TAG = "ShortDramaLinesCodec"

    fun encode(lines: List<ShortDramaQueueLine>): String {
        val array = JSONArray()
        lines.forEach { line ->
            val episodes = JSONArray()
            line.items.forEach { item ->
                episodes.put(
                    JSONObject().apply {
                        put("num", item.episodeNum)
                        put("label", item.label)
                        put("url", item.url)
                    }
                )
            }
            array.put(
                JSONObject().apply {
                    put("name", line.name)
                    put("episodes", episodes)
                }
            )
        }
        return array.toString()
    }

    /** 解析失败返回空列表，调用方自己决定怎么提示，不要在这里抛。 */
    fun decode(raw: String?): List<ShortDramaQueueLine> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val out = ArrayList<ShortDramaQueueLine>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name").ifBlank { "线路${i + 1}" }
                val episodesArr = obj.optJSONArray("episodes") ?: JSONArray()
                val items = ArrayList<ShortDramaQueueItem>(episodesArr.length())
                for (j in 0 until episodesArr.length()) {
                    val ep = episodesArr.getJSONObject(j)
                    val url = ep.optString("url").trim()
                    if (url.isEmpty()) continue
                    val num = ep.optInt("num", j + 1)
                    items.add(
                        ShortDramaQueueItem(
                            episodeNum = num,
                            label = ep.optString("label").ifBlank { "第${num}集" },
                            url = url
                        )
                    )
                }
                if (items.isNotEmpty()) {
                    out.add(ShortDramaQueueLine(name = name, items = items))
                }
            }
            out
        }.onFailure {
            Log.w(TAG, "分集 JSON 解析失败: ${it.message}")
        }.getOrDefault(emptyList())
    }
}

// ---------------------------------------------------------------------------
// 播放队列：当前线路 + 当前集
// ---------------------------------------------------------------------------

/**
 * 播放队列。竖屏播放器只认「一条线路的一串分集」，
 * 换线时按集号对齐（尽量停在用户正在看的那一集），不是按数组下标硬切。
 */
class ShortDramaQueue(
    val lines: List<ShortDramaQueueLine>,
    startLine: Int = 0,
    startIndex: Int = 0
) {

    var lineIndex: Int = if (lines.isEmpty()) 0 else startLine.coerceIn(0, lines.size - 1)
        private set

    var index: Int = startIndex.coerceAtLeast(0)
        private set

    init {
        index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    /** 当前线路的分集（线路为空时给空列表，调用方不用判空） */
    val items: List<ShortDramaQueueItem>
        get() = lines.getOrNull(lineIndex)?.items ?: emptyList()

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    /** 当前线路名，用于顶栏 / 选集面板 */
    val lineName: String get() = lines.getOrNull(lineIndex)?.name ?: ""

    fun lineNames(): List<String> = lines.map { it.name }

    fun itemAt(position: Int): ShortDramaQueueItem? = items.getOrNull(position)

    fun current(): ShortDramaQueueItem? = items.getOrNull(index)

    fun hasNext(): Boolean = index < size - 1

    fun hasPrevious(): Boolean = index > 0

    /** 移动到线路内的某一集；越界或原地不动返回 false */
    fun moveTo(position: Int): Boolean {
        if (position !in 0 until size) return false
        if (position == index) return false
        index = position
        return true
    }

    /**
     * 换线路，尽量对齐到同一集号。
     * @return 换线后应播放的线路内下标（越界时返回 -1，调用方保留旧线路）
     */
    fun switchLine(target: Int): Int {
        if (target !in lines.indices || target == lineIndex) return -1
        val keepNum = current()?.episodeNum
        val newItems = lines[target].items
        if (newItems.isEmpty()) return -1
        val aligned = if (keepNum == null) 0 else {
            newItems.indexOfFirst { it.episodeNum == keepNum }.let { if (it >= 0) it else 0 }
        }
        lineIndex = target
        index = aligned.coerceIn(0, newItems.size - 1)
        return index
    }
}

// ---------------------------------------------------------------------------
// 每集播放位置（断点续播）
// ---------------------------------------------------------------------------

/**
 * 每集播放位置的本地记录。
 *
 * 与 [com.tv.ball.presentation.shortdrama.ShortDramaProgressStore] 的分工：
 * - ProgressStore 记「这部剧看到第几集」，给列表页 / 详情页用；
 * - 这里记「这一集看到第几秒」，给竖屏播放器切集回位用。
 * 短剧一集 1~2 分钟，用户切走再回来几乎都在同一集，位置比集号更影响体感。
 */
object ShortDramaPositionStore {

    private const val TAG = "ShortDramaPosition"
    private const val PREFS_NAME = "short_drama_position"

    /** 小于这个时长不记（片头几秒没意义，还容易让用户以为卡住了） */
    private const val MIN_POSITION_MS = 3_000L

    /** 距结尾这么近就当看完了，下次从 0 开始 */
    private const val TAIL_TOLERANCE_MS = 8_000L

    /** 最多保留多少条，超了按时间淘汰最旧的，避免 prefs 无限膨胀 */
    private const val MAX_ENTRIES = 240

    private val prefs by lazy {
        com.tv.ball.di.Dependencies.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun keyOf(bookId: String, episodeNum: Int): String = "p_${bookId.trim()}_$episodeNum"

    /** 上次看到哪；没有记录或不该续播都返回 0 */
    fun positionOf(bookId: String, episodeNum: Int): Long {
        if (bookId.isBlank()) return 0L
        val raw = runCatching { prefs.getLong(keyOf(bookId, episodeNum), 0L) }.getOrDefault(0L)
        return if (raw >= MIN_POSITION_MS) raw else 0L
    }

    fun save(bookId: String, episodeNum: Int, positionMs: Long, durationMs: Long) {
        if (bookId.isBlank() || episodeNum <= 0) return
        val tail = if (durationMs > 0) durationMs - positionMs else Long.MAX_VALUE
        // 已看完 / 刚开头：清掉记录，下次从头播
        if (positionMs < MIN_POSITION_MS || tail <= TAIL_TOLERANCE_MS) {
            clearEpisode(bookId, episodeNum)
            return
        }
        runCatching {
            prefs.edit().putLong(keyOf(bookId, episodeNum), positionMs).apply()
            trimIfNeeded()
        }.onFailure { Log.w(TAG, "保存播放位置失败: ${it.message}") }
    }

    fun clearEpisode(bookId: String, episodeNum: Int) {
        runCatching { prefs.edit().remove(keyOf(bookId, episodeNum)).apply() }
    }

    fun clearAll() {
        runCatching { prefs.edit().clear().apply() }
    }

    /**
     * 只留最近的 MAX_ENTRIES 条。
     * prefs 没有「取全部键的时间序」的便捷 API，这里按值里的时间戳没有意义
     * （我们存的是位置不是时间），所以用「键数量超限就清空」的粗粒度策略：
     * 短剧位置丢了只是从头播，比无限膨胀安全。
     */
    private fun trimIfNeeded() {
        if (prefs.all.size <= MAX_ENTRIES) return
        prefs.edit().clear().apply()
    }
}

// ---------------------------------------------------------------------------
// ExoPlayer 实例池（实现见 ShortDramaPlayerEngine）
// ---------------------------------------------------------------------------

/**
 * 播放器实例池。竖屏连播至少要「当前 + 下一集」两个实例，滑动才不黑屏；
 * 再多就是浪费内存，所以上限固定 3 个。
 */
interface ShortDramaPlaybackEngine {

    /** 取一个可用的 ExoPlayer：池里有空闲就复用，没有且未超过上限就新建 */
    fun obtainPlayer(): ExoPlayer

    /** 归还实例：停止播放并清掉媒体源，实例留在池里下次复用 */
    fun recyclePlayer(player: ExoPlayer)

    /**
     * 给实例装载一集（只 prepare，不自动 play，播放与否由界面决定）。
     * @param startPositionMs 断点位置，0 表示从头
     */
    fun load(player: ExoPlayer, item: ShortDramaQueueItem, startPositionMs: Long)

    /** Activity 销毁时调用：释放池里全部实例 */
    fun shutdown()
}

/** 工厂。界面只依赖接口，不直接 new 实现类。 */
object ShortDramaPlayback {
    fun create(context: Context): ShortDramaPlaybackEngine = ShortDramaPlayerEngine(context)
}
