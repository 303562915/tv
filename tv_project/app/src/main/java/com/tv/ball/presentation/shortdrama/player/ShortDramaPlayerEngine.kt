package com.tv.ball.presentation.shortdrama.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy

/**
 * 竖屏短剧的 ExoPlayer 实例池 + 装载逻辑。
 *
 * 为什么要有实例池
 * ----------------
 * 竖屏连播里用户一秒能滑三集，如果每次滑动都 new / release 一个 ExoPlayer，
 * 解码器初始化和释放会直接顶在滑动帧上，表现就是「滑过去黑一下」。
 * 所以实例不销毁，只做 stop + clearMediaItems 复用；上限卡在 3 个，
 * 因为再多就是拿内存换一个用户根本感受不到的首帧。
 *
 * 为什么不接 SimpleCache / 下载缓存
 * --------------------------------
 * 老播放器那套 [com.tv.ball.presentation.player.ExoPlayerManager] 的缓存是给「一部电影看两小时」
 * 设计的。短剧一集 1~2 分钟、下一集是全新直链，缓存命中率极低，
 * 但 CacheDataSource 的写盘和索引查询会一直占着 IO 和主流程，纯亏。
 * 这里只走 DefaultDataSource，不碰磁盘缓存。
 *
 * 线程约束：所有方法都从主线程调用（RecyclerView 回调和 Activity 生命周期），
 * 池状态统一用 [lock] 保护，避免界面层偶发的非主线程回收把集合改坏。
 */
class ShortDramaPlayerEngine(private val context: Context) : ShortDramaPlaybackEngine {

    private val lock = Any()

    /**
     * 空闲实例。尾部是最近回收的，取用时也从尾部取：
     * 刚回收的那个最可能正是用户要滑到的那一集，解码器参数还热着。
     */
    private val idle = ArrayDeque<ExoPlayer>()

    /** 借出中的实例：只用来判断「池子还剩几个额度」，以及 shutdown 时统一释放 */
    private val borrowed = ArrayList<ExoPlayer>()

    /**
     * 不属于池子的实例：池已关闭后才被索取的兜底实例，以及快速滑动时池子满载多开的那一个。
     * 它们也是我们 new 出来的，所以回收回调回来时要就地 release，否则会漏一个解码器。
     */
    private val detached = HashSet<ExoPlayer>()

    private var closed = false

    /**
     * 加载失败重试策略，全池共用一个实例。
     *
     * 为什么不在 ExoPlayer.Builder 上设：2.19.1 的 Builder 已经没有
     * setLoadErrorHandlingPolicy 了（老播放器 [com.tv.ball.presentation.player.ExoPlayerManager]
     * 里也留了同样的说明），策略只能挂在媒体源工厂上，所以这里每个源都要显式带上它。
     */
    private val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT)

    // -----------------------------------------------------------------------
    // 池
    // -----------------------------------------------------------------------

    override fun obtainPlayer(): ExoPlayer {
        synchronized(lock) {
            if (closed) {
                // Activity 已经 shutdown 却还有人要实例，说明界面回调晚到了。
                // 这里给一个池外实例让它继续跑，抛异常只会变成用户侧的闪退。
                Log.w(TAG, "池已关闭仍被索取实例，新建池外实例兜底")
                return newPlayer().also { detached.add(it) }
            }

            val reused = idle.removeLastOrNull()
            if (reused != null) {
                borrowed.add(reused)
                return reused
            }

            if (borrowed.size < MAX_PLAYERS) {
                val created = newPlayer()
                borrowed.add(created)
                Log.d(TAG, "新建实例（借出=${borrowed.size}, 上限=$MAX_PLAYERS）")
                return created
            }

            // 实例全借出、也没有空闲：说明用户正在快速滑动，同一个屏幕上短暂存在了超过 3 页。
            // 这里刻意不去抢占别人手里的实例——被抢的那一页如果是可见页会当场黑屏；
            // 更麻烦的是它稍后回收时会把这个实例再还回池子，而抢占者还以为自己独占，
            // 同一个 ExoPlayer 被两页同时持有，就会串画面、串声音。
            // 宁愿临时多开一个池外实例：滑动停下后它会被回收释放，内存只多占一个解码器。
            val extra = newPlayer()
            detached.add(extra)
            Log.w(TAG, "无空闲实例，临时新建池外实例（池上限 $MAX_PLAYERS 已满，借出=${borrowed.size}）")
            return extra
        }
    }

    override fun recyclePlayer(player: ExoPlayer) {
        synchronized(lock) {
            if (!borrowed.remove(player)) {
                // 不是本池借出的实例（池外兜底实例，或已经回收过一次），直接忽略。
                // RecyclerView 的 onViewRecycled / onDetach 会重复触发，幂等靠这条分支保证。
                if (detached.remove(player)) {
                    resetPlayer(player)
                    releaseQuietly(player)
                    Log.d(TAG, "回收池外实例并释放")
                }
                return
            }

            resetPlayer(player)

            if (closed) {
                // 关闭之后回收：池已经不收了，就地释放，不留悬空实例
                releaseQuietly(player)
                Log.d(TAG, "回收实例并释放（池已关闭）")
                return
            }

            idle.addLast(player)
            // 空闲超过两个说明用户已经滑远了，多留只是白占一个解码器和它的缓冲
            while (idle.size > MAX_IDLE_PLAYERS) {
                releaseQuietly(idle.removeFirst())
            }
            Log.d(TAG, "回收实例（空闲=${idle.size}, 借出=${borrowed.size}）")
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            closed = true
            val all = ArrayList<ExoPlayer>(borrowed.size + idle.size + detached.size)
            all.addAll(borrowed)
            all.addAll(idle)
            all.addAll(detached)
            borrowed.clear()
            idle.clear()
            detached.clear()
            // 逐个释放：任何一个实例已经处于异常状态都不该拖累其余的
            all.forEach { releaseQuietly(it) }
        }
    }

    // -----------------------------------------------------------------------
    // 装载
    // -----------------------------------------------------------------------

    override fun load(player: ExoPlayer, item: ShortDramaQueueItem, startPositionMs: Long) {
        val rawUrl = item.url.trim()
        if (rawUrl.isEmpty()) {
            Log.w(TAG, "load 失败：URL 为空（第${item.episodeNum}集）")
            return
        }

        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            // 采集源偶尔会给出半截地址或相对路径，这类 URL 交给 ExoPlayer 只会变成
            // 一条看不出根因的 source error，不如在这里就拦下来
            Log.w(TAG, "load 失败：URL 非法（第${item.episodeNum}集）: ${rawUrl.take(80)}")
            return
        }

        try {
            player.setMediaSource(createMediaSource(uri, rawUrl))
            player.prepare()
            // 装好不等于要播：滑动时实例会被预加载，这里自己起播会变成两集同时出声
            player.playWhenReady = false
            if (startPositionMs > RESUME_MIN_POSITION_MS) {
                player.seekTo(startPositionMs)
            } else {
                player.seekTo(0)
            }
        } catch (e: Exception) {
            // 实例可能已经被外部 release,这里兜住，别把异常抛进 RecyclerView 回调
            Log.w(TAG, "load 失败：${e.javaClass.simpleName} ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun createMediaSource(uri: Uri, rawUrl: String): MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            // 采集源 CDN 普遍按 UA 粗筛，桌面 Chrome UA 命中率最高
            .setUserAgent(USER_AGENT)
            // 采集站给 http 直链再 302 到 https 很常见，不放开跨协议重定向会直接 403
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        // 防盗链：不少 m3u8 校验 Referer 是站点根，缺了会返回 403 或空 playlist
        refererOf(uri)?.let { referer ->
            httpFactory.setDefaultRequestProperties(mapOf("Referer" to referer))
        }

        // 走 DefaultDataSource 而不是直接塞 HTTP 工厂：短剧目前只有 http(s)，
        // 但保留这层分发能让 content:// 之类的地址不至于当场抛 scheme 不支持
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaItem = MediaItem.fromUri(uri)

        return when {
            isHls(rawUrl) -> HlsMediaSource.Factory(dataSourceFactory)
                // 采集源的 m3u8 常有多码率和不规整的 EXTINF，开 chunkless 首帧能快一点
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)

            isDash(rawUrl) -> DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)

            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
        }
    }

    /** 采集源的直链后面几乎都挂防盗链查询串，所以判后缀要看查询串之前那一段 */
    private fun isHls(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".m3u8") || lower.contains(".m3u8?")
    }

    private fun isDash(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mpd") || lower.contains(".mpd?")
    }

    /** 只拼到站点根：防盗链校验的一般是站点而不是具体分片路径 */
    private fun refererOf(uri: Uri): String? {
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        return "$scheme://$host/"
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            // 起播权在界面手里
            playWhenReady = false
        }

    /**
     * 复用前把实例清干净。失败也不往上抛：
     * 走到这里实例可能已经在别处被 release，清理失败不影响后续重新 load。
     */
    private fun resetPlayer(player: ExoPlayer) {
        runCatching {
            player.pause()
            player.stop()
            player.clearMediaItems()
            player.playWhenReady = false
        }
    }

    private fun releaseQuietly(player: ExoPlayer) {
        runCatching { player.release() }
    }

    private companion object {
        private const val TAG = "SDPlayerEngine"

        /** 竖屏连播只要「当前 + 下一集」，第三个是给快速滑动留的余量 */
        private const val MAX_PLAYERS = 3

        /** 空闲最多留两个，多出来的释放：否则滑到第一百集时池外会攒一堆解码器 */
        private const val MAX_IDLE_PLAYERS = 2

        /** 片头两秒内的位置没意义，硬 seek 反而多一次重缓冲 */
        private const val RESUME_MIN_POSITION_MS = 2_000L

        private const val LOAD_RETRY_COUNT = 3
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/110.0.0.0 Mobile Safari/537.36"
    }
}
