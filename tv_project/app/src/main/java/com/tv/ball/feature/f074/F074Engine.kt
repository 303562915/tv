package com.tv.ball.feature.f074

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
 * F074「收藏导入导出」的能力层。
 *
 * 收藏是用户自己的东西，换机 / 换端 / 重装都要能带走，所以这里只做四件事：
 *   1. [export]：把一批 [Favorite] 序列化成 JSON
 *      （`{"version":1,"exportedAtMs":...,"count":n,"items":[{title,coverUrl,addedAtMs,tags}]}`）；
 *   2. [import]：把 JSON 读回来，按 `title + coverUrl` 去重，坏条目计数但绝不抛异常
 *      —— 用户粘进来的可能只是半段文本，也可能是别的 App 的 JSON；
 *   3. [writeTo]：落盘到应用专属外部目录 `getExternalFilesDir("export")`，
 *      接根数据线就能把文件拷走，不需要任何存储权限；
 *   4. [readFrom]：从 `OpenDocument` 拿到的 Uri 读回文本，带体积上限。
 *
 * 去重口径：`title + coverUrl` 拼成的键已经在本地、或在同一次导入里出现过，
 * 都算「跳过」；只有真正新进来的算「新增」；缺 title 的算「非法」。
 * 三个数字相加等于这份文件里的条目数，界面上对得上账。
 *
 * 合并口径：[merge] 遇到同一个键时**标签取并集**、收藏时间取更早的那次
 * （「我什么时候第一次收的」比「我什么时候又收了一次」有用），
 * 封面 / 标题以后到的为准（老文件里的空封面能被新文件补上）。
 */
object F074Engine {

    /** 导出格式版本。字段或含义变了就 +1，导入端按它决定怎么读。 */
    const val VERSION: Int = 1

    /** 导出目录名：应用专属外部目录，卸载才会被系统清掉。 */
    const val EXPORT_DIR: String = "export"

    /** 文件名前缀，后面接时间戳，连着导几次也不会互相覆盖。 */
    const val FILE_PREFIX: String = "tvplayer-favorites-"

    /** 导出文件后缀。 */
    const val FILE_EXT: String = ".json"

    /** 导入时最多读这么多字节：误选一部视频进来也不会把内存吃爆。 */
    const val MAX_BYTES: Int = 4 * 1024 * 1024

    /** 单次导入最多扫这么多条，超出部分记「非法」，不让畸形文件把页面卡死。 */
    const val MAX_SCAN: Int = 20_000

    /** 单条最多保留这么多个标签，防止有人手写一个上万标签的文件把界面撑死。 */
    const val MAX_TAGS: Int = 32

    /** 顶层字段名。 */
    const val KEY_VERSION: String = "version"
    const val KEY_EXPORTED_AT: String = "exportedAtMs"
    const val KEY_COUNT: String = "count"
    const val KEY_ITEMS: String = "items"

    /** 单条字段名。顺序就是导出时的书写顺序。 */
    const val KEY_TITLE: String = "title"
    const val KEY_COVER_URL: String = "coverUrl"
    const val KEY_ADDED_AT: String = "addedAtMs"
    const val KEY_TAGS: String = "tags"

    /** 键分隔符：`title` 与 `coverUrl` 里都不会出现的控制字符，避免拼接歧义。 */
    private const val KEY_SEP: String = "\u0001"

    /** 空输入。 */
    const val ERROR_EMPTY: String = "内容是空的，没东西可导"

    /** 顶层认不出来：既不是对象里的 items，也不是裸数组。 */
    const val ERROR_ROOT: String = "这不是一份收藏 JSON：既没有 items 数组，本身也不是数组"

    /** 读文件时的兜底失败文案。 */
    const val ERROR_READ_FAILED: String = "这个文件读不出来"

    /**
     * 一条收藏。
     *
     * [coverUrl] 可以是空串（没有封面的条目照样能收藏），但 [title] 不能空 —— 空标题在列表里
     * 就是一行空白，用户分不清那是哪一条，所以导入时直接判非法。
     * [addedAtMs] 是收藏那一刻的墙钟时间；[tags] 是用户自己打的标签，顺序即展示顺序。
     */
    data class Favorite(
        val title: String,
        val coverUrl: String,
        val addedAtMs: Long,
        val tags: List<String> = emptyList()
    ) {

        /**
         * 去重键：标题 + 封面。
         *
         * 收藏没有稳定 id（同一个视频在不同源上的 id 不一样），而「同一个封面 + 同一个标题」
         * 基本可以认定是同一条。标题与封面各自先 trim、封面 URL 再抹掉尾斜杠，
         * 免得 `x.jpg` 与 `x.jpg/` 被当成两条。
         */
        fun dedupeKey(): String = title.trim() + KEY_SEP + coverUrl.trim().trimEnd('/')

        /** 标签文案：没有标签给一个短横线占位，列表行里不会出现「标签：」后面空白。 */
        fun tagsText(): String = if (tags.isEmpty()) "—" else tags.joinToString(" · ")
    }

    /**
     * 一次导入的结果。
     *
     * [added] / [skipped] / [invalid] 就是页面上那三个数字；[items] 是本次真正新进来的条目
     * （调用方交给 [merge] 即可）；[error] 非空表示整份文档都没读成，
     * 这时三个计数都是 0，页面直接显示这条原因。
     */
    data class ImportResult(
        val added: Int,
        val skipped: Int,
        val invalid: Int,
        val items: List<Favorite> = emptyList(),
        val error: String? = null
    ) {

        /** 整份文档是否读成了。 */
        val ok: Boolean get() = error == null

        /** 「新增 2 · 跳过 3 · 非法 1」这类文案在页面层用 strings 拼，这里只保证数字可读。 */
        fun summary(): String = "added=$added skipped=$skipped invalid=$invalid"
    }

    // ===== 导出 =====

    /**
     * 序列化成 JSON。
     *
     * 默认紧凑单行；[pretty] = true 时按 2 空格缩进，用户直接打开文件看也能读。
     * 顶层带上 [KEY_EXPORTED_AT] 与 [KEY_COUNT]：人眼扫一眼就知道这份文件是什么时候导的、
     * 里面几条，导入端也不依赖它们（缺了照读）。
     */
    fun export(
        items: List<Favorite>,
        pretty: Boolean = false,
        clock: () -> Long = { System.currentTimeMillis() }
    ): String {
        val array = JSONArray()
        for (item in items) {
            array.put(toJson(item))
        }
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_EXPORTED_AT, clock())
        root.put(KEY_COUNT, items.size)
        root.put(KEY_ITEMS, array)
        return if (pretty) root.toString(2) else root.toString()
    }

    private fun toJson(item: Favorite): JSONObject {
        val tags = JSONArray()
        for (tag in item.tags) {
            tags.put(tag)
        }
        val obj = JSONObject()
        obj.put(KEY_TITLE, item.title)
        obj.put(KEY_COVER_URL, item.coverUrl)
        obj.put(KEY_ADDED_AT, item.addedAtMs)
        obj.put(KEY_TAGS, tags)
        return obj
    }

    // ===== 导入 =====

    /**
     * 反序列化。
     *
     * 容错口径：
     *   - 顶层可以是 `{"version":1,"items":[...]}`，也可以直接是一个裸数组（手工写的片段）；
     *   - 单条没有 title（或 title 不是字符串）算「非法」；coverUrl 缺失按空串；
     *     addedAtMs 缺失 / 不是数字按 0、负数夹到 0 —— 时间坏了不该把整条收藏作废；
     *   - tags 支持字符串数组，也支持逗号 / 顿号分隔的单个字符串，去空去重后最多留 [MAX_TAGS] 个；
     *   - 键已经在 [existing] 里、或在本次导入里出现过第二次的，算「跳过」；
     *   - 任何一步失败都只是计数 +1，方法本身不抛异常。
     */
    fun import(
        json: String,
        existing: List<Favorite>,
        maxScan: Int = MAX_SCAN
    ): ImportResult {
        val text = json.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) {
            return ImportResult(0, 0, 0, error = ERROR_EMPTY)
        }

        val array = when (text.first()) {
            '[' -> runCatching { JSONArray(text) }.getOrNull()
            '{' -> runCatching { JSONObject(text) }.getOrNull()?.optJSONArray(KEY_ITEMS)
            else -> null
        } ?: return ImportResult(0, 0, 0, error = ERROR_ROOT)

        val seen = HashSet<String>(existing.size * 2 + 8)
        for (item in existing) {
            seen.add(item.dedupeKey())
        }

        val parsed = ArrayList<Favorite>()
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
            if (!seen.add(item.dedupeKey())) {
                skipped++
                continue
            }
            added++
            parsed.add(item)
        }
        // 超过扫描上限的部分一律算非法，免得界面上三个数字加起来和文件对不上
        if (array.length() > scanned) {
            invalid += array.length() - scanned
        }

        return ImportResult(added, skipped, invalid, parsed)
    }

    /**
     * 单条解析，返回 null 表示这条不算收藏（缺 title）。
     *
     * 取值走 [JSONObject.opt] 而不是 `optString`：后者遇到 JSON 里的 `null`
     * 会吐出字符串 "null"，那会被当成合法标题混进去。
     */
    private fun parseItem(obj: JSONObject): Favorite? {
        val title = textOf(obj, KEY_TITLE)
        if (title.isEmpty()) return null
        return Favorite(
            title = title,
            coverUrl = textOf(obj, KEY_COVER_URL),
            addedAtMs = longOf(obj, KEY_ADDED_AT),
            tags = tagsOf(obj)
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

    /** 标签字段：数组或分隔字符串都收，trim、去空、去重（保序）、截到 [MAX_TAGS]。 */
    private fun tagsOf(obj: JSONObject): List<String> {
        val raw = when (val value = obj.opt(KEY_TAGS)) {
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                val element = value.opt(index)
                if (element is String) element else null
            }

            is String -> value.split(',', '，', '、', '|', ';', '；')
            else -> emptyList()
        }
        val out = ArrayList<String>(minOf(raw.size, MAX_TAGS))
        val seen = HashSet<String>(raw.size * 2 + 4)
        for (tag in raw) {
            val clean = tag.trim()
            if (clean.isEmpty()) continue
            if (!seen.add(clean)) continue
            out.add(clean)
            if (out.size >= MAX_TAGS) break
        }
        return out
    }

    // ===== 合并 =====

    /**
     * 合并本地与导入的收藏。
     *
     * 同一个去重键只留一条：标签取并集、收藏时间取更早的、标题与封面以 [incoming] 为准
     * （新文件通常更完整，空封面 / 带空白的老标题能被修掉）。
     *
     * 结果按收藏时间倒序，最近收的在最上面；时间相同就按标题排，保证每次渲染顺序一致。
     */
    fun merge(items: List<Favorite>, imported: List<Favorite>): List<Favorite> {
        val byKey = LinkedHashMap<String, Favorite>(items.size + imported.size)
        for (item in items) {
            byKey[item.dedupeKey()] = item
        }
        for (item in imported) {
            val key = item.dedupeKey()
            val old = byKey[key]
            byKey[key] = if (old == null) {
                item
            } else {
                Favorite(
                    title = if (item.title.isNotEmpty()) item.title else old.title,
                    coverUrl = if (item.coverUrl.isNotEmpty()) item.coverUrl else old.coverUrl,
                    addedAtMs = earliest(old.addedAtMs, item.addedAtMs),
                    tags = unionTags(old.tags, item.tags)
                )
            }
        }
        return byKey.values.sortedWith(
            compareByDescending<Favorite> { it.addedAtMs }.thenBy { it.title }
        )
    }

    /**
     * 时间取更早的，但 0 表示「不知道」：一边是 0 就取另一边，两边都是 0 才是 0。
     * 否则一份没写时间的老文件会把本地真实收藏时间冲成 1970。
     */
    private fun earliest(a: Long, b: Long): Long = when {
        a <= 0L -> b
        b <= 0L -> a
        else -> minOf(a, b)
    }

    /** 标签并集：先本地后导入，保序去重，截到 [MAX_TAGS]。 */
    private fun unionTags(first: List<String>, second: List<String>): List<String> {
        val out = ArrayList<String>(first.size + second.size)
        val seen = HashSet<String>(first.size + second.size)
        for (tag in first + second) {
            val clean = tag.trim()
            if (clean.isEmpty()) continue
            if (seen.add(clean)) out.add(clean)
            if (out.size >= MAX_TAGS) break
        }
        return out
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
     * 免得传进来一个带 `../` 的名字把文件写到别处去。
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
     * 超过 [maxBytes] 直接抛 [IOException] —— 选错文件是常事，与其把一部视频读进内存，
     * 不如立刻说清楚读不了。顺带剥掉 Windows 记事本爱加的 BOM。
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
                throw IOException("文件超过 ${formatBytes(maxBytes.toLong())}，不像一份收藏 JSON")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** 默认文件名：`tvplayer-favorites-20261120-225415.json`。 */
    fun defaultFileName(clock: () -> Long = { System.currentTimeMillis() }): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        return FILE_PREFIX + stamp + FILE_EXT
    }

    // ===== 小工具 =====

    /** 当前列表的去重键集合，导入前判重用（页面层也可以直接传列表）。 */
    fun keysOf(items: List<Favorite>): Set<String> = items.mapTo(HashSet(items.size * 2 + 4)) { it.dedupeKey() }

    /** 毫秒数 → `2026-11-20 22:31`，列表行里显示收藏时间。 */
    fun formatMoment(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms.coerceAtLeast(0L)))

    /** 字节数 → `742 B` / `1.2 KB` / `3.4 MB`，保留一位小数。 */
    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        }
    }

    /**
     * 一行里用的短文案：封面取域名（`img.example.com`），没封面给「无封面」。
     * 完整 URL 太长，列表里展示不下，也不需要在列表里点。
     */
    fun coverHost(coverUrl: String): String {
        val text = coverUrl.trim()
        if (text.isEmpty()) return "无封面"
        val withoutScheme = text.substringAfter("://", text)
        val host = withoutScheme.substringBefore('/').substringBefore('?')
        return host.ifEmpty { text }
    }

    /** 文件名消毒：换掉路径分隔符、Windows 保留字符与控制字符；整串洗没了就给个占位名。 */
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
     * 内置的 6 条示例收藏。
     *
     * 页面首屏先拿它把导出 / 导入两条路径跑通：不需要真的收过东西，
     * 装上就能看到「导出 6 条 → 再粘回来 → 全部跳过」的完整闭环。
     * 时间基于 [clock]，所以每次进页面看到的都是「最近」的时间。
     *
     * 第 2 条与第 4 条封面为空、第 5 条标签多到一个手指数不完 —— 都是真机上会遇到的样子。
     */
    fun sampleFavorites(clock: () -> Long = { System.currentTimeMillis() }): List<Favorite> {
        val now = clock()
        return listOf(
            Favorite(
                "漫长的季节",
                "https://img.example.com/cover/manman-de-jijie.jpg",
                now - 40 * MINUTE_MS,
                listOf("国产", "悬疑", "已看完")
            ),
            Favorite(
                "三体 · 第 12 集",
                "https://cdn.example.com/poster/three-body-s1e12.jpg",
                now - 6 * HOUR_MS,
                listOf("科幻", "在看")
            ),
            Favorite(
                "宇宙探索编辑部",
                "https://cdn.example.org/movie/ued2021.webp",
                now - 2 * DAY_MS,
                listOf("电影", "喜剧")
            ),
            Favorite(
                "声之形",
                "",
                now - 9 * DAY_MS,
                listOf("动画", "电影")
            ),
            Favorite(
                "一年一度喜剧大赛 · 第 4 期",
                "https://img.example.net/variety/xiangqi-s2e4.png",
                now - 15 * DAY_MS,
                listOf("综艺", "喜剧", "待看", "国产", "高分", "2024")
            ),
            Favorite(
                "狂飙 · 第 26 集",
                "https://img.example.com/cover/kuangbiao-e26.jpg",
                now - 33 * DAY_MS,
                listOf("国产", "刑侦")
            )
        )
    }

    private const val MINUTE_MS = 60L * 1000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS
}
