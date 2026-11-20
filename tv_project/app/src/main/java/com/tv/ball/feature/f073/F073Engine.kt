package com.tv.ball.feature.f073

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F073「历史导入导出」的能力层。
 *
 * 只干四件事，全部是纯逻辑 + 少量文件 IO：
 *   1. [export]：把一组播放历史序列化成一份结构化 JSON
 *      （`{"version":1,"items":[{id,title,playedAtMs,progressMs,durationMs}]}`）；
 *   2. [import]：把 JSON 读回来，按 id 去重、坏条目录计数但绝不抛异常
 *      —— 用户剪贴板里粘进来的可能是半截文本、可能是别的 App 的 JSON，
 *      这种输入不该让页面崩在导入按钮上；
 *   3. [writeTo]：落盘到应用专属外部目录 `getExternalFilesDir("export")`，
 *      换机时接根数据线就能把文件拷走，也不需要任何存储权限；
 *   4. [readFrom]：从 `OpenDocument` 拿到的 Uri 读回文本，带体积上限。
 *
 * 去重口径：已经在本地的 id 一律算「跳过」，同一次导入里重复出现的 id 也算「跳过」，
 * 只有真正新进来的才算「新增」。这样界面上三个数字相加等于「这份文件里的条目数」，
 * 用户一眼能对上账。
 */
object F073Engine {

    /** 导出格式版本。字段或含义变了就 +1，导入端按它决定怎么读。 */
    const val VERSION: Int = 1

    /** 导出目录名：应用专属外部目录，卸载才会被系统清掉。 */
    const val EXPORT_DIR: String = "export"

    /** 文件名前缀，后面接时间戳，连着导几次也不会互相覆盖。 */
    const val FILE_PREFIX: String = "tvplayer-history-"

    /** 导出文件后缀。 */
    const val FILE_EXT: String = ".json"

    /** 导入时最多读这么多字节：误选一部视频进来也不会把内存吃爆。 */
    const val MAX_BYTES: Int = 8 * 1024 * 1024

    /** 单次导入最多扫这么多条，超出部分直接记「非法」，不让一份畸形文件把页面卡死。 */
    const val MAX_SCAN: Int = 20_000

    /** 顶层字段名。 */
    const val KEY_VERSION: String = "version"
    const val KEY_ITEMS: String = "items"

    /** 单条字段名。顺序就是导出时的书写顺序。 */
    const val KEY_ID: String = "id"
    const val KEY_TITLE: String = "title"
    const val KEY_PLAYED_AT: String = "playedAtMs"
    const val KEY_PROGRESS: String = "progressMs"
    const val KEY_DURATION: String = "durationMs"

    /** 空输入。 */
    const val ERROR_EMPTY: String = "内容是空的，没东西可导"

    /** 顶层认不出来：既不是对象里的 items，也不是裸数组。 */
    const val ERROR_ROOT: String = "这不是一份历史 JSON：既没有 items 数组，本身也不是数组"

    /** 读文件时的兜底失败文案。 */
    const val ERROR_READ_FAILED: String = "这个文件读不出来"

    /**
     * 一条播放历史。
     *
     * [id] 是去重键（播放列表里那条视频的稳定标识）；[playedAtMs] 是最后一次播放的墙钟时间；
     * [progressMs] / [durationMs] 是播放进度与总时长，用来在列表里拼出「12:34 / 45:00」。
     */
    data class HistoryItem(
        val id: String,
        val title: String,
        val playedAtMs: Long,
        val progressMs: Long,
        val durationMs: Long
    ) {

        /** 进度文案：有时长是「12:34 / 45:00」，没时长就只给当前进度。 */
        fun progressText(): String {
            val progress = formatClock(progressMs)
            return if (durationMs > 0L) "$progress / ${formatClock(durationMs)}" else progress
        }
    }

    /**
     * 一次导入的结果。
     *
     * [added] / [skipped] / [invalid] 就是页面上那三个数字；[items] 是本次真正新进来的条目
     * （调用方 `addAll` 进自己的列表即可）；[error] 非空表示整份文档都没读成，
     * 这时三个计数都是 0，页面直接显示这条原因。
     */
    data class ImportResult(
        val added: Int,
        val skipped: Int,
        val invalid: Int,
        val items: List<HistoryItem> = emptyList(),
        val error: String? = null
    ) {

        /** 整份文档是否读成了。 */
        val ok: Boolean get() = error == null
    }

    // ===== 导出 =====

    /**
     * 序列化成 JSON。
     *
     * 默认输出紧凑单行（就是 [VERSION] + [KEY_ITEMS] 那套结构），体积小、拷来拷去不容易被
     * 编辑器加进奇怪的空白；[pretty] = true 时按 2 空格缩进，人肉看文件时用得上。
     *
     * 字段顺序固定成 id → title → playedAtMs → progressMs → durationMs，
     * 和文档里写的格式一致，diff 的时候不会因为键序抖动而看不出改动。
     */
    fun export(items: List<HistoryItem>, pretty: Boolean = false): String {
        val array = JSONArray()
        for (item in items) {
            array.put(toJson(item))
        }
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_ITEMS, array)
        return if (pretty) root.toString(2) else root.toString()
    }

    private fun toJson(item: HistoryItem): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ID, item.id)
        obj.put(KEY_TITLE, item.title)
        obj.put(KEY_PLAYED_AT, item.playedAtMs)
        obj.put(KEY_PROGRESS, item.progressMs)
        obj.put(KEY_DURATION, item.durationMs)
        return obj
    }

    // ===== 导入 =====

    /**
     * 反序列化。
     *
     * 容错口径：
     *   - 顶层可以是 `{"version":1,"items":[...]}`，也可以直接是一个裸数组（手工写的片段）；
     *   - 单条缺 id 或 title 的算「非法」，`playedAtMs` / `progressMs` / `durationMs`
     *     缺失或不是数字的按 0 处理、负数夹到 0 —— 计数值坏掉不该把整条记录作废；
     *   - id 已经在 [existingIds] 里、或在本次导入里出现过第二次的，算「跳过」；
     *   - 任何一步失败都只是计数 +1，方法本身不抛异常。
     */
    fun import(json: String, existingIds: Set<String>, maxScan: Int = MAX_SCAN): ImportResult {
        val text = json.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) {
            return ImportResult(0, 0, 0, error = ERROR_EMPTY)
        }

        val array = when (text.first()) {
            '[' -> runCatching { JSONArray(text) }.getOrNull()
            '{' -> runCatching { JSONObject(text) }.getOrNull()?.optJSONArray(KEY_ITEMS)
            else -> null
        } ?: return ImportResult(0, 0, 0, error = ERROR_ROOT)

        val seen = HashSet<String>(existingIds)
        val parsed = ArrayList<HistoryItem>()
        var added = 0
        var skipped = 0
        var invalid = 0

        val scanned = if (maxScan > 0) minOf(array.length(), maxScan) else array.length()
        for (index in 0 until scanned) {
            val obj = array.optJSONObject(index)
            if (obj == null) {
                invalid++
                continue
            }
            val item = parseItem(obj)
            if (item == null) {
                invalid++
                continue
            }
            if (!seen.add(item.id)) {
                skipped++
                continue
            }
            added++
            parsed.add(item)
        }
        // 超过扫描上限的部分一律算非法，免得界面上的三个数字加起来和文件对不上
        if (array.length() > scanned) {
            invalid += array.length() - scanned
        }

        return ImportResult(added, skipped, invalid, parsed)
    }

    /**
     * 单条解析。
     *
     * 返回 null 表示这条不算历史记录（缺 id 或 title）。
     * 取值走 [opt] 而不是 [JSONObject.optString]：后者遇到 JSON 里的 `null`
     * 会吐出字符串 "null"，那会被当成合法 id 混进去。
     */
    private fun parseItem(obj: JSONObject): HistoryItem? {
        val id = textOf(obj, KEY_ID)
        val title = textOf(obj, KEY_TITLE)
        if (id.isEmpty() || title.isEmpty()) return null
        return HistoryItem(
            id = id,
            title = title,
            playedAtMs = longOf(obj, KEY_PLAYED_AT),
            progressMs = longOf(obj, KEY_PROGRESS),
            durationMs = longOf(obj, KEY_DURATION)
        )
    }

    /** 取字符串字段，缺失 / JSON null / 不是字符串都按空串处理。 */
    private fun textOf(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        return if (value is String) value.trim() else ""
    }

    /** 取数字字段，缺失 / 不是数字按 0，负数夹到 0。 */
    private fun longOf(obj: JSONObject, key: String): Long {
        val value = when (val raw = obj.opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.trim().toDoubleOrNull()?.toLong()
            else -> null
        } ?: return 0L
        return value.coerceAtLeast(0L)
    }

    // ===== 落盘与读取 =====

    /**
     * 导出目录：优先 `getExternalFilesDir("export")`（接电脑能直接拷走），
     * 外部目录没挂上（个别机型）就退回内部 `filesDir/export`，宁可在内部也别让功能不可用。
     */
    fun exportDir(context: Context): File =
        context.getExternalFilesDir(EXPORT_DIR) ?: File(context.filesDir, EXPORT_DIR)

    /**
     * 把 [text] 写成 [fileName]，返回落盘后的文件。
     *
     * 目录不存在会建；[fileName] 里的路径分隔符、控制字符会被替换掉，
     * 免得调用方传进来一个带 `../` 的名字把文件写到别处去。
     * 写失败（目录建不出来、磁盘满）抛 [IOException]，由页面层提示。
     */
    fun writeTo(context: Context, fileName: String, text: String): File {
        val dir = exportDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("导出目录建不出来：${dir.absolutePath}")
        }
        val target = File(dir, sanitizeFileName(fileName))
        target.writeText(text, Charsets.UTF_8)
        return target
    }

    /**
     * 从 `OpenDocument` 的 Uri 读回文本。
     *
     * 超过 [maxBytes]（默认 [MAX_BYTES]）直接抛 [IOException] —— 选错文件是常事，
     * 与其把一部视频读进内存，不如立刻说清楚读不了。
     * 顺带剥掉 Windows 记事本爱加的 BOM，否则 JSON 解析会在第一个字符上就失败。
     */
    fun readFrom(context: Context, uri: Uri, maxBytes: Int = MAX_BYTES): String {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: throw IOException(ERROR_READ_FAILED)
        val bytes = stream.use { input -> readCapped(input, maxBytes) }
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("文件超过 ${formatBytes(maxBytes.toLong())}，不像一份历史 JSON")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** 默认文件名：`tvplayer-history-20261120-225415.json`。 */
    fun defaultFileName(clock: () -> Long = { System.currentTimeMillis() }): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        return FILE_PREFIX + stamp + FILE_EXT
    }

    // ===== 小工具 =====

    /** 当前列表里所有 id，导入前用来判重。 */
    fun idsOf(items: List<HistoryItem>): Set<String> {
        val ids = HashSet<String>(items.size)
        for (item in items) {
            ids.add(item.id)
        }
        return ids
    }

    /** 合并：本地原有 + 本次新增，按最后播放时间倒序，界面上最近看的在最上面。 */
    fun merge(current: List<HistoryItem>, incoming: List<HistoryItem>): List<HistoryItem> {
        val merged = ArrayList<HistoryItem>(current.size + incoming.size)
        val seen = HashSet<String>(current.size + incoming.size)
        for (item in current + incoming) {
            if (seen.add(item.id)) merged.add(item)
        }
        return merged.sortedByDescending { it.playedAtMs }
    }

    /** 毫秒数 → `12:34` / `1:02:03`。 */
    fun formatClock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** 字节数 → `742 B` / `1.2 KB` / `3.4 MB`，保留一位小数。 */
    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        }
    }

    /** 时间戳 → `2026-11-20 22:31`，列表行里显示记录时间。 */
    fun formatMoment(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms.coerceAtLeast(0L)))

    /** 文件名消毒：换掉路径分隔符、Windows 保留字符与控制字符；整串洗没了就给个占用位名。 */
    private fun sanitizeFileName(fileName: String): String {
        val cleaned = ILLEGAL_FILE_CHARS.replace(fileName.trim(), "_")
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            FILE_PREFIX + "export" + FILE_EXT
        } else {
            cleaned
        }
    }

    private val ILLEGAL_FILE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /**
     * 内置的 6 条示例历史。
     *
     * 页面上先拿它把导出 / 导入两条路径跑通：不需要真实播放过视频，
     * 装上就能看到「导出 6 条 → 再粘回来 → 全部跳过」的完整闭环。
     * 时间基于 [clock]，所以每次进页面看到的都是「最近」的时间。
     */
    fun sampleItems(clock: () -> Long = { System.currentTimeMillis() }): List<HistoryItem> {
        val now = clock()
        return listOf(
            HistoryItem("sample-001", "漫长的季节 · 第 6 集", now - 26 * MINUTE_MS, 1_812_000L, 2_760_000L),
            HistoryItem("sample-002", "三体 · 第 12 集", now - 3 * HOUR_MS, 2_245_000L, 2_700_000L),
            HistoryItem("sample-003", "狂飙 · 第 26 集", now - 26 * HOUR_MS, 512_000L, 2_820_000L),
            HistoryItem("sample-004", "一年一度喜剧大赛 · 第 4 期", now - 3 * DAY_MS, 4_120_000L, 5_400_000L),
            HistoryItem("sample-005", "宇宙探索编辑部", now - 8 * DAY_MS, 6_480_000L, 6_480_000L),
            HistoryItem("sample-006", "声之形", now - 21 * DAY_MS, 0L, 0L)
        )
    }

    private const val MINUTE_MS = 60L * 1000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS
}
