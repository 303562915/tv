package com.tv.ball.presentation.shortdrama

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tv.ball.di.Dependencies
import org.json.JSONObject

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
 */
object ShortDramaProgressStore {

    private const val TAG = "ShortDramaProgress"
    private const val PREFS_NAME = "short_drama_progress"

    /** 每条进度一个 key，前缀 + bookId，方便按剧取、也方便以后按前缀整体清 */
    private const val KEY_PREFIX = "p_"

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
        val json = JSONObject().apply {
            put("bookId", id)
            put("title", title.trim())
            put("cover", cover.trim())
            put("episodeNum", episodeNum)
            put("totalEpisodes", totalEpisodes)
            put("providerKey", providerKey.trim())
            put("updatedAt", System.currentTimeMillis())
        }.toString()
        // apply()：异步落盘，不卡起播
        prefs.edit().putString(KEY_PREFIX + id, json).apply()
    }

    /** 读某部剧的进度；没记录过或数据坏了都返回 null。 */
    fun progressOf(bookId: String): Progress? {
        val id = bookId.trim()
        if (id.isEmpty()) return null
        val raw = prefs.getString(KEY_PREFIX + id, null) ?: return null
        return runCatching {
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

    /** 清空全部短剧进度（只动本文件，不碰别的 prefs） */
    fun clear() {
        prefs.edit().clear().apply()
    }
}