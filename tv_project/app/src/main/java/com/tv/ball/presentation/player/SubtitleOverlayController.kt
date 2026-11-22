package com.tv.ball.presentation.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView
import com.tv.ball.feature.f011.F011SubtitleParser
import com.tv.ball.feature.f012.F012Engine
import com.tv.ball.feature.f012.SubtitleBackground
import com.tv.ball.feature.f013.F013Engine
import com.tv.ball.feature.f013.F013State
import com.tv.ball.feature.f013.F013StateStore
import com.tv.ball.feature.f015.F015Engine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页外挂字幕控制器：读文件 → 探编码 → 解析 → 按播放位置取当前字幕文本。
 *
 * 故意不持有 Activity：播放页只把 [textAt] 塞进 200ms 的 tick 里、把 [applyStyle] 套到浮层 TextView 上，
 * 控制器自己活到 [release]。这样菜单、弹窗、横竖屏切换都不会打断已加载的字幕。
 *
 * 三段职责分别落在：
 * - 解析 f011（[F011SubtitleParser]），编码 f015（[F015Engine]），样式 f012（[F012Engine]），
 *   时间轴延迟 f013（[F013Engine]）—— 本类只做接线与显示，不重复实现它们的规则。
 */
class SubtitleOverlayController(context: Context) {

    /** 一次加载的结果：成功带文件名与条数，失败带一句能直接念给用户听的原因。 */
    sealed class LoadResult {
        data class Success(val fileName: String, val cueCount: Int) : LoadResult()

        data class Failure(val reason: String) : LoadResult()
    }

    /** 给菜单行显示的只读快照：没加载时 [fileName] 为 null。 */
    data class State(val fileName: String?, val cueCount: Int, val delayMs: Int)

    private val appContext: Context = context.applicationContext
    private val encodingEngine = F015Engine()

    // 只借用 f013 的「延迟」这一半能力：tracks 传空表（延迟与轨道列表无关），
    // 存盘实现见文件末尾 [DelayPrefsStore] —— 键和功能中心页共用，两处调的是同一个值
    private val delayEngine = F013Engine(store = DelayPrefsStore(appContext), tracks = emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    /** 按开始时间升序，[textAt] 的二分查找依赖这个前提。 */
    private var cues: List<F011SubtitleParser.Cue> = emptyList()
    private var fileName: String? = null

    val state: State
        get() = State(fileName, cues.size, delayEngine.delayMs)

    val delayMs: Int
        get() = delayEngine.delayMs

    // ---------------------------------------------------------------- 加载

    /** 从 SAF 拿到的 uri 加载字幕；回调一定在主线程。 */
    fun loadFromUri(uri: Uri, onResult: (LoadResult) -> Unit) {
        // lastPathSegment 在部分 provider 上是 "documentId" 形态，取最后一段只为给用户看个名字
        val displayName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FILE_NAME
        startLoad(displayName, onResult) { readBytesFromUri(uri) }
    }

    /** 从本地绝对路径加载（同名字幕自动匹配走这条路）。 */
    fun loadFromPath(path: String, onResult: (LoadResult) -> Unit) {
        val displayName = path.replace('\\', '/').substringAfterLast('/').ifBlank { DEFAULT_FILE_NAME }
        startLoad(displayName, onResult) { readBytesFromPath(path) }
    }

    private fun startLoad(
        displayName: String,
        onResult: (LoadResult) -> Unit,
        reader: () -> ByteArray?
    ) {
        // 连续点两次选文件时，前一次的结果必须作废，否则后一次会被旧回调盖掉
        loadJob?.cancel()
        loadJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val bytes = runCatching(reader).getOrNull()
                when {
                    bytes == null -> Loaded.Failed(REASON_UNREADABLE)
                    bytes.isEmpty() -> Loaded.Failed(REASON_UNREADABLE)
                    else -> parse(bytes)
                }
            }
            // IO 阶段不碰成员变量：切回主线程再落状态，[textAt] 永远读到的是一份完整的表
            when (outcome) {
                is Loaded.Ok -> {
                    cues = outcome.cues
                    fileName = displayName
                    onResult(LoadResult.Success(displayName, outcome.cues.size))
                }

                is Loaded.Failed -> {
                    cues = emptyList()
                    fileName = null
                    onResult(LoadResult.Failure(outcome.reason))
                }
            }
        }
    }

    private fun parse(bytes: ByteArray): Loaded {
        val text = decodeText(bytes)
        val result = F011SubtitleParser.parse(text)
        val error = result.error
        if (error != null) return Loaded.Failed(describe(error))
        return Loaded.Ok(result.cues.sortedBy { it.startMs })
    }

    /**
     * 编码处理：先用 f015 的探测结果解码，不可信或解出来还是乱码再按 GB18030 兜一次。
     *
     * 为什么要兜：GBK 与 Big5 的字节区间重叠，f015 的候选顺序里 GBK 在前，个别 Big5 字幕会被判成 GBK；
     * 而 GB18030 是 GBK 的超集，国内字幕绝大多数在这套编码里。只有在「兜底解出来的可疑字符更少」时才采用，
     * 否则会把本来正确的 UTF-8 覆盖成一堆乱码。
     */
    private fun decodeText(bytes: ByteArray): String {
        val detection = encodingEngine.detect(bytes)
        val primary = encodingEngine.decode(bytes, detection.charset)
        val primaryBad = encodingEngine.suspiciousRatio(primary)
        if (detection.reliable && primaryBad < F015Engine.GARBLED_RATIO) return primary
        val fallback = encodingEngine.decode(bytes, FALLBACK_CHARSET)
        return if (encodingEngine.suspiciousRatio(fallback) < primaryBad) fallback else primary
    }

    private fun readBytesFromUri(uri: Uri): ByteArray? =
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    private fun readBytesFromPath(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile) return null
        return file.readBytes()
    }

    private fun describe(error: F011SubtitleParser.Error): String = when (error) {
        F011SubtitleParser.Error.EMPTY -> REASON_EMPTY
        F011SubtitleParser.Error.BAD_TIMECODE -> "第 ${error.detailLine} 行的时间格式不对"
        F011SubtitleParser.Error.NO_CUES -> REASON_NO_CUES
    }

    // ---------------------------------------------------------------- 状态

    /** 卸掉字幕（cue 与文件名），延迟值保留 —— 那是用户调好的观感，不该跟着文件一起丢。 */
    fun clear() {
        loadJob?.cancel()
        loadJob = null
        cues = emptyList()
        fileName = null
    }

    /** 控制器跟着播放页一起结束：停掉后台加载协程，避免 IO 回来后还往已经销毁的 TextView 上写。 */
    fun release() {
        loadJob?.cancel()
        loadJob = null
        scope.cancel()
    }

    fun adjustDelay(deltaMs: Int): Int = delayEngine.adjustDelay(deltaMs)

    fun resetDelay(): Int {
        delayEngine.resetDelay()
        return delayEngine.delayMs
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 当前播放位置应该显示的字幕文本；空串表示不显示。
     *
     * 每 200ms 调一次，所以不能全表扫：先二分找到「开始时间已经过了」的最后一条（延迟是整条时间轴平移，
     * 平移后顺序不变，可以直接比 effectiveTime），再从这里往回最多看 [BACKWARD_SCAN_LIMIT] 条 ——
     * 长句 cue 可能被后面已经结束的短 cue 盖住，往回退几步才能取出真正还在显示的那条。
     * 可见性判定统一走 f013.isVisibleAt，不在这里另写一套 end/延迟比较。
     */
    fun textAt(positionMs: Long): String {
        val list = cues
        if (list.isEmpty()) return ""
        var index = lastStartedIndex(list, positionMs)
        if (index < 0) return ""
        val floor = maxOf(0, index - BACKWARD_SCAN_LIMIT + 1)
        while (index >= floor) {
            val cue = list[index]
            if (delayEngine.isVisibleAt(cue.startMs, cue.endMs, positionMs)) return cue.text
            index--
        }
        return ""
    }

    /** 满足 effectiveTime(start) <= positionMs 的最后一条下标；早于全部 cue 时返回 -1。 */
    private fun lastStartedIndex(list: List<F011SubtitleParser.Cue>, positionMs: Long): Int {
        var low = 0
        var high = list.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (delayEngine.effectiveTime(list[mid].startMs) <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * 把 f012 的样式套到浮层上。
     *
     * 描边用 setShadowLayer 画：外层描边本质就是四周一圈同色阴影，半径 0 时 TextView 不会画阴影，
     * 正好对应「描边关」；底色按档位设置，NONE 必须给透明，否则 reset 之后会留一块黑底。
     */
    fun applyStyle(textView: TextView) {
        val style = F012Engine.load(appContext)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp.toFloat())
        textView.setTextColor(style.textColor)
        val density = appContext.resources.displayMetrics.density
        if (style.outlineEffective) {
            textView.setShadowLayer(style.outlineWidthDp * density, 0f, 0f, Color.BLACK)
        } else {
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        textView.setBackgroundColor(backgroundOf(style.background))
    }

    private fun backgroundOf(mode: SubtitleBackground): Int = when (mode) {
        SubtitleBackground.NONE -> Color.TRANSPARENT
        SubtitleBackground.TRANSLUCENT -> Color.argb(TRANSLUCENT_ALPHA, 0, 0, 0)
        SubtitleBackground.OPAQUE -> Color.BLACK
    }

    /** IO 线程的解析产物：要么一张排好序的 cue 表，要么一句失败原因。 */
    private sealed class Loaded {
        data class Ok(val cues: List<F011SubtitleParser.Cue>) : Loaded()

        data class Failed(val reason: String) : Loaded()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "字幕文件"

        /** 探测不可信时的兜底编码：GB18030 覆盖 GBK，国内字幕基本都在这套里。 */
        const val FALLBACK_CHARSET = "GB18030"

        /** 半透明黑底的 alpha：太浓会糊住画面，太淡在亮场景下又看不清字。 */
        const val TRANSLUCENT_ALPHA = 0x66

        /** 二分定位后最多往回看几条：够覆盖重叠 cue，又不会退化成全表扫。 */
        const val BACKWARD_SCAN_LIMIT = 8

        const val REASON_UNREADABLE = "读不到文件内容"
        const val REASON_EMPTY = "字幕文件是空的"
        const val REASON_NO_CUES = "没有解析出任何字幕"
    }
}

/**
 * 延迟状态的 SharedPreferences 实现。
 *
 * 必须和 `F013Activity` 里的 `F013PrefsStore` 用同一份 prefs、同一个键
 * （video_player_prefs / f013_subtitle_delay_ms），否则功能中心页调好的延迟到播放页不生效。
 *
 * 那里是 file-private 类，这里不能直接复用，只能复刻同一个键；[save] 故意只写延迟键：
 * 轨道选中态归功能中心页管，从播放页写回去会把它抹掉。
 */
private class DelayPrefsStore(context: Context) : F013StateStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): F013State? {
        if (!prefs.contains(KEY_DELAY_MS)) return null
        return F013State(prefs.getString(KEY_TRACK_ID, null), prefs.getInt(KEY_DELAY_MS, 0))
    }

    override fun save(state: F013State) {
        prefs.edit().putInt(KEY_DELAY_MS, state.delayMs).apply()
    }

    private companion object {
        const val PREFS_NAME = "video_player_prefs"
        const val KEY_TRACK_ID = "f013_selected_track"
        const val KEY_DELAY_MS = "f013_subtitle_delay_ms"
    }
}
