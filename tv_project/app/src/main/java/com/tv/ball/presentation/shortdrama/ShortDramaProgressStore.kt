package com.tv.ball.presentation.shortdrama

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tv.ball.di.Dependencies
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 短剧观看进度（本地存储）。
 *
 * 为什么单独抽一个 object
 * ----------------------
 * 列表页要「继续观看」、详情/播放页要在每集起播时记一笔，两边都得读同一份数据。
 * 放进 ViewModel 会随页面销毁丢状态，放进数据库又太重（只是几条 key-value）。
 *
 * 为什么记录时不搞重活
 * --------------------
 * [record] 会在每集起播的路径上被调用（用户在竖屏里连滑十几集就是十几次调用），
 * 所以这里只做「拼一个 JSON + prefs.edit().apply()」：
 * - 不联网、不查库、不启协程；
 * - 用 apply() 而不是 commit()，写盘异步化，不阻塞主线程。
 *
 * 为什么加了内存缓存和监听器
 * --------------------------
 * 列表页每 bind 一张卡就要读一次进度，而「读一次」= prefs.getString + JSONObject 解析。
 * 滚动时这个开销会落在主线程上，所以加一层内存缓存：读到就留在内存，之后只查 Map。
 * 但缓存带来了新问题——播放页写进度的不进列表页的 Map，卡片会一直显示旧的「看到第 N 集」。
 * 于是再补一个监听器：写成功后回调一次，列表页收到就刷卡片。
 */
object ShortDramaProgressStore {

    private const val TAG = "ShortDramaProgress"
    private const val PREFS_NAME = "short_drama_progress"

    /** 每条进度一个 key，前缀 + bookId，方便按剧取、也方便以后按前缀整体清 */
    private const val KEY_PREFIX = "p_"

    /**
     * 内存缓存：值类型是 Progress?，因为「读过盘，确认没记录」也要缓存，
     * 否则没有进度的剧每次 bind 都会重新读一次盘。
     */
    private val cache = HashMap<String, Progress?>()

    /** 缓存锁：读在列表页主线程、写在起播路径，两边都可能有，加个对象锁最省事 */
    private val cacheLock = Any()

    /**
     * 进度变化监听器。
     * CopyOnWriteArrayList：回调时顺手改列表（比如页面销毁时 removeListener）不会把遍历搞崩，
     * 而且这里读写比极端（写一次刷全表 / 读只在增删监听器），正合适。
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 主线程 Handler：监听方基本都是要改 View 的，统一 post 到主线程回调，
     * 调用方（record 可能发生在播放器回调或子线程）不用自己切线程。
     */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 为什么延迟初始化：
     * Dependencies.context 取的是 VideoPlayerApplication.instance.applicationContext，
     * Application 还没创建完时访问会炸。等第一次真正用到时再拿最安全。
     */
    private val prefs: SharedPreferences by lazy {
        Dependencies.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class Progress(
        val bookId: String,
        val title: String,
        val cover: String,
        val episodeNum: Int,
        val totalEpisodes: Int,
        val providerKey: String,
        val updatedAt: Long
    )

    /**
     * 记录某部剧看到第几集。
     *
     * 用 JSON 而不是拼分隔符：剧名里本来就可能带 : / | 之类的字符，拼字符串迟早解析错。
     *
     * **totalEpisodes 的语义（重要）**：它是「这部剧的总集数」，不是「当前播放线路的集数」。
     * 调用方应当传**该剧全部线路里的最大集数**（同一个采集源里，同一部剧的不同线路常常
     * 一长一短：第 1 条线路只有 20 集、第 2 条有 80 集）。传成当前源的 size 会出现
     * 「用户明明看到第 60 集了，卡片上还写共 20 集」这种自相矛盾的进度。
     * 目录页 / 详情页拿到的是 `sources`，最大集数应当由它们算好后传进来。
     */
    fun record(
        bookId: String,
        title: String,
        cover: String,
        episodeNum: Int,
        totalEpisodes: Int,
        providerKey: String
    ) {
        val id = bookId.trim()
        if (id.isEmpty()) return
        val progress = Progress(
            bookId = id,
            title = title.trim(),
            cover = cover.trim(),
            episodeNum = episodeNum,
            totalEpisodes = totalEpisodes,
            providerKey = providerKey.trim(),
            updatedAt = System.currentTimeMillis()
        )
        val json = JSONObject().apply {
            put("bookId", progress.bookId)
            put("title", progress.title)
            put("cover", progress.cover)
            put("episodeNum", progress.episodeNum)
            put("totalEpisodes", progress.totalEpisodes)
            put("providerKey", progress.providerKey)
            put("updatedAt", progress.updatedAt)
        }.toString()
        // apply()：异步落盘，不卡起播
        prefs.edit().putString(KEY_PREFIX + id, json).apply()
        // 落盘是异步的，缓存必须同步更新：
        // 否则紧接着在列表页读到的还是旧值（甚至是被缓存下来的 null），卡片会显示上一集。
        synchronized(cacheLock) { cache[id] = progress }
        notifyListeners()
    }

    /** 读某部剧的进度；没记录过或数据坏了都返回 null。 */
    fun progressOf(bookId: String): Progress? {
        val id = bookId.trim()
        if (id.isEmpty()) return null
        // 先查内存。用 containsKey 而不是「get() != null」判断命中：
        // 「缓存了一个 null」（= 读过盘、确认这部没记录）和「没缓存过」是两件事，
        // 混为一谈的话，没进度的剧每次 bind 都会重新 getString + JSONObject 解析一遍。
        synchronized(cacheLock) {
            if (cache.containsKey(id)) {
                return cache[id]
            }
        }
        val raw = prefs.getString(KEY_PREFIX + id, null)
        val progress = if (raw == null) {
            null
        } else {
            runCatching {
                val o = JSONObject(raw)
                Progress(
                    bookId = o.optString("bookId", id),
                    title = o.optString("title"),
                    cover = o.optString("cover"),
                    episodeNum = o.optInt("episodeNum", 0),
                    totalEpisodes = o.optInt("totalEpisodes", 0),
                    providerKey = o.optString("providerKey"),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            }.onFailure {
                Log.w(TAG, "进度解析失败 bookId=$id: ${it.message}")
            }.getOrNull()
        }
        // 解析结果（包括 null）一并回填，下次直接命中
        synchronized(cacheLock) { cache[id] = progress }
        return progress
    }

    /**
     * 某部剧记录下来的总集数（没记录过返回 0）。
     *
     * 只读 [Progress.totalEpisodes]，不额外解析、不改数据格式——线上已经有进度数据落盘，
     * 加字段会让老数据读出来是默认值，所以这里只是给调用方一个语义明确的读入口。
     */
    fun episodeCountFor(bookId: String): Int {
        return progressOf(bookId)?.totalEpisodes ?: 0
    }

    /** 清空全部短剧进度（只动本文件，不碰别的 prefs） */
    fun clear() {
        prefs.edit().clear().apply()
        // 缓存不清就等于没清：清完立刻读还会从内存里把旧进度翻出来
        synchronized(cacheLock) { cache.clear() }
        // 不通知的话，列表页已经画好的卡片会一直挂着「看看到第 N 集」，
        // 用户清完进度回到列表还以为没生效
        notifyListeners()
    }

    /**
     * 清除**单部剧**的进度（列表卡片上的「清除观看记录」走这条）。
     *
     * 只删这一条 key，不动别的剧：整体 [clear] 会把用户所有观看进度一次抹掉，
     * 用在单卡片操作上就是灾难。
     */
    fun clear(bookId: String) {
        val id = bookId.trim()
        if (id.isEmpty()) return
        prefs.edit().remove(KEY_PREFIX + id).apply()
        // 缓存里那条（可能是「确认没记录」的 null）也要一起删：
        // 否则删完立刻读还是命中旧缓存，UI 上表现为「清了但还在」
        synchronized(cacheLock) { cache.remove(id) }
        notifyListeners()
    }

    /**
     * 注册进度变化监听器。
     * 回调在主线程；监听方要改 RecyclerView 的话请自己再 post 一帧（见 ShortDramaAdapter），
     * 因为这里只保证在主线程、不保证此时不在布局计算过程中。
     */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    /** 注销监听器；页面销毁 / 适配器脱离 RecyclerView 时必须调，否则会一直持有引用 */
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        // 刻意不在 record 的调用线程上直接回调：record 可能来自播放器回调（不保证主线程），
        // 而监听方要动 View。统一 post 到主线程，调用方就不用自己包 runOnUiThread。
        mainHandler.post {
            // toList()：回调过程中若有监听器被移除，遍历的是快照，不会抛异常
            listeners.toList().forEach { it() }
        }
    }
}