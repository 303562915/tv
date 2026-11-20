package com.tv.ball.feature.f075

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条观看历史。
 *
 * @param title            标题（可能带逗号、引号、换行，CSV 转义由 [F075Engine.toCsv] 负责）
 * @param playedAt         本次播放的毫秒时间戳，写进 CSV 时格式化成 `yyyy-MM-dd HH:mm:ss`
 * @param progressSeconds  看到第几秒
 * @param durationSeconds  总时长秒；为 0 表示时长未知
 */
data class HistoryItem(
    val title: String,
    val playedAt: Long,
    val progressSeconds: Int,
    val durationSeconds: Int
) {
    /** 看完没有：有无时长都能算，时长未知时不算看完，免得把"未知"说成"看完"。 */
    val finished: Boolean get() = durationSeconds > 0 && progressSeconds >= durationSeconds
}

/**
 * 一条收藏。
 *
 * @param cover    封面地址（本地路径或网络 URL），为空时 CSV 里留空字段
 * @param tags     标签；写进 CSV 用 `|` 拼成一个字段，标签里自带逗号也不会把列切乱
 */
data class FavoriteItem(
    val title: String,
    val cover: String,
    val favoritedAt: Long,
    val tags: List<String>
)

/**
 * F075「CSV 数据导出」的能力层。
 *
 * 要做的事只有一件：把观看历史与收藏变成**表格软件能直接打开**的两个 CSV 文件。
 *
 * 三条设计约束：
 *
 * 1. **纯 Kotlin 的拼装**：[toCsv] / [escapeField] / [historyCsv] / [favoritesCsv] /
 *    [previewText] 全是纯函数，不碰 Android API，也不读任何数据源——
 *    数据从哪来由调用方给（这一版页面用的是 [sampleHistory] / [sampleFavorites] 的样例数据）。
 * 2. **RFC 4180 转义**：字段里出现逗号、双引号、CR/LF 就整体加双引号，内部的双引号写两遍。
 *    记录之间用 CRLF 分隔，这也是 RFC 4180 与 Excel 的默认期待。
 * 3. **落盘目标**：优先公共下载目录（`Environment.DIRECTORY_DOWNLOADS`），
 *    拿不到或写不进去就退到 `getExternalFilesDir`，再退到内部 `filesDir`，
 *    三层都给不出一个能写的目录才抛 [IOException]。
 *
 * UTF-8 的 BOM 会写在文件开头：Excel 不认无 BOM 的 UTF-8，缺了它中文表头就是一片乱码，
 * 而记事本 / Numbers / WPS 见到 BOM 也不会有副作用。
 *
 * 所有函数都是阻塞调用，页面必须放到 Dispatchers.IO 上跑。
 */
object F075Engine {

    /** 预览默认显示多少行记录（含表头那一行，与页面「前 10 行」的口径一致）。 */
    const val PREVIEW_LINES: Int = 10

    /** 回落目录名：`getExternalFilesDir("csv_export")` / `filesDir/csv_export`。 */
    const val DOWNLOAD_DIR: String = "csv_export"

    /** 文件名前缀。 */
    const val FILE_PREFIX: String = "tvplayer"

    /** 历史导出的文件名种类。 */
    const val KIND_HISTORY: String = "history"

    /** 收藏导出的文件名种类。 */
    const val KIND_FAVORITES: String = "favorites"

    /** 历史表头：标题 / 播放时间 / 进度秒 / 时长秒。 */
    val HEADER_HISTORY: List<String> = listOf("标题", "播放时间", "进度秒", "时长秒")

    /** 收藏表头：标题 / 封面 / 收藏时间 / 标签。 */
    val HEADER_FAVORITES: List<String> = listOf("标题", "封面", "收藏时间", "标签")

    private const val CRLF = "\r\n"

    /** UTF-8 BOM：Excel 靠它判定「这是 UTF-8」，否则中文表头会按 GBK 解析成乱码。 */
    private const val BOM = "\uFEFF"

    /** 标签字段内部的分隔符。用 `|` 而不是逗号，标签里带逗号时才不会把列切乱。 */
    private const val TAG_SEPARATOR = "|"

    // region 两种导出

    /**
     * 观看历史 → CSV 文本。
     *
     * 表头 `标题,播放时间,进度秒,时长秒`；时间格式化成本地 `yyyy-MM-dd HH:mm:ss`，
     * 进度 / 时长写整数秒——表格软件直接按数字列排序，不需要再洗一遍数据。
     */
    fun historyCsv(items: List<HistoryItem>): String {
        val rows = ArrayList<List<String>>(items.size + 1)
        rows.add(HEADER_HISTORY)
        for (item in items) {
            rows.add(
                listOf(
                    item.title,
                    formatTime(item.playedAt),
                    item.progressSeconds.toString(),
                    item.durationSeconds.toString()
                )
            )
        }
        return toCsv(rows)
    }

    /**
     * 收藏 → CSV 文本。
     *
     * 表头 `标题,封面,收藏时间,标签`；标签是一个字段，多标签用 `|` 连起来。
     */
    fun favoritesCsv(items: List<FavoriteItem>): String {
        val rows = ArrayList<List<String>>(items.size + 1)
        rows.add(HEADER_FAVORITES)
        for (item in items) {
            rows.add(
                listOf(
                    item.title,
                    item.cover,
                    formatTime(item.favoritedAt),
                    item.tags.joinToString(TAG_SEPARATOR)
                )
            )
        }
        return toCsv(rows)
    }

    // endregion

    // region CSV 本体

    /**
     * 行 → CSV 文本（RFC 4180）。
     *
     * 每条记录以 CRLF 结尾（含最后一条）——RFC 4180 允许，Excel / Numbers 也都吃得下；
     * 空行（`listOf()`）会被写成一条空记录，也就是一个单独的 CRLF。
     */
    fun toCsv(rows: List<List<String>>): String {
        val builder = StringBuilder(rows.size * 32)
        for (row in rows) {
            for (index in row.indices) {
                if (index > 0) builder.append(',')
                builder.append(escapeField(row[index]))
            }
            builder.append(CRLF)
        }
        return builder.toString()
    }

    /**
     * 单个字段转义。
     *
     * 只有真正需要的时候才加引号：含逗号 / 双引号 / CR / LF 才包，内部的双引号写两遍。
     * 不含这些字符的字段原样输出——否则每个字段都带引号，表格虽然还能打开，
     * 但导出的文件用记事本看就全是噪音了。
     */
    fun escapeField(field: String): String {
        var needQuote = false
        for (ch in field) {
            if (ch == ',' || ch == '"' || ch == '\n' || ch == '\r') {
                needQuote = true
                break
            }
        }
        if (!needQuote) return field
        val builder = StringBuilder(field.length + 8)
        builder.append('"')
        for (ch in field) {
            if (ch == '"') builder.append("\"\"") else builder.append(ch)
        }
        builder.append('"')
        return builder.toString()
    }

    /**
     * 按记录切分 CSV（引号感知）。
     *
     * 不能直接 `split("\r\n")`：一个被引号包起来的字段里可能自带换行
     * （例如标题 `夜行者\n特别篇`），幼稚的按行切会把它切成两条半记录。
     * 这里只在**引号外**遇到 CRLF 才断记录，`""` 只是把引号状态翻两次，净效果不变。
     */
    fun splitRecords(csv: String): List<String> {
        val out = ArrayList<String>()
        if (csv.isEmpty()) return out
        val builder = StringBuilder(64)
        var inQuotes = false
        var index = 0
        while (index < csv.length) {
            val ch = csv[index]
            if (ch == '"') {
                inQuotes = !inQuotes
                builder.append(ch)
                index++
                continue
            }
            if (!inQuotes && ch == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') {
                out.add(builder.toString())
                builder.setLength(0)
                index += 2
                continue
            }
            builder.append(ch)
            index++
        }
        if (builder.isNotEmpty()) out.add(builder.toString())
        return out
    }

    /** 记录数（含表头）。 */
    fun recordCount(csv: String): Int = splitRecords(csv).size

    /**
     * 前 [limit] 条记录，供页面预览。
     *
     * 返回的文本用 `\n` 连接、不带 BOM：TextView 不需要 BOM，也不需要 CRLF。
     * 字段内自带换行的记录在预览里会占两行视觉效果——这是它本来的样子，
     * 不做替换，否则用户看到的就不是「将写出的内容」了。
     */
    fun previewText(csv: String, limit: Int = PREVIEW_LINES): String {
        val records = splitRecords(csv)
        if (records.isEmpty()) return ""
        val builder = StringBuilder()
        for (record in records.take(limit.coerceAtLeast(1))) {
            builder.append(record).append('\n')
        }
        return builder.toString().trimEnd('\n')
    }

    // endregion

    // region 写文件

    /**
     * 一次写入的结果。
     *
     * @param path     落盘绝对路径（页面直接显示，用户照着去下载目录里找）
     * @param bytes    文件字节数（含 BOM）
     * @param fallback 是否用了回落目录（公共下载目录拿不到 / 写不进去时为 true）
     */
    data class WriteResult(val path: String, val bytes: Long, val fallback: Boolean) {

        /** 人读的大小：B / KB / MB。 */
        fun bytesText(): String = formatBytes(bytes)
    }

    /**
     * 把 CSV 写到磁盘。
     *
     * 目录按优先级依次尝试，任何一个能写成就返回：
     * 1. `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` —— 用户最容易找到；
     * 2. `getExternalFilesDir("csv_export")` —— 不需要权限，卸载重装会丢，但至少能导出；
     * 3. `filesDir/csv_export` —— 前两层都写不进去时的兜底。
     *
     * 每次写完都回读 `File.length()` 与字节数比一遍：分区满 / 被拦截时 `write` 不一定会抛，
     * 静默写出半个文件比直接失败更糟。
     */
    fun writeCsv(context: Context, fileName: String, csv: String): WriteResult {
        val bytes = (BOM + csv).toByteArray(Charsets.UTF_8)
        val publicDownloads = publicDownloadsDir()
        var lastError: Exception? = null
        for (dir in candidateDirs(context)) {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("目录建不出来：${dir.absolutePath}")
                }
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                val written = file.length()
                if (written != bytes.size.toLong()) {
                    throw IOException("写入不完整：$written / ${bytes.size} 字节")
                }
                val fallback = publicDownloads == null || dir.absolutePath != publicDownloads.absolutePath
                return WriteResult(file.absolutePath, written, fallback)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("CSV 写入失败：${lastError?.message ?: "没有可写的目录"}", lastError)
    }

    /** 候选目录，顺序即优先级。 */
    fun candidateDirs(context: Context): List<File> {
        val dirs = ArrayList<File>(3)
        publicDownloadsDir()?.let { dirs.add(it) }
        val external = runCatching { context.getExternalFilesDir(DOWNLOAD_DIR) }.getOrNull()
        if (external != null) dirs.add(external)
        dirs.add(File(context.filesDir, DOWNLOAD_DIR))
        return dirs
    }

    /** 公共下载目录；个别机型外置存储没挂上时这里可能拿到一个写不进去的路径，交给上层回落。 */
    @Suppress("DEPRECATION")
    private fun publicDownloadsDir(): File? =
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()

    /**
     * 文件名：`tvplayer-history-20261120-221500.csv`。
     *
     * 用 ASCII 前缀 + 时间戳：同一个小时里连点两次导出，两次的文件名也不会撞，
     * 而中文文件名在某些文件管理器 / SMB 共享里会被转义成一串百分号。
     */
    fun fileName(kind: String, now: Long = System.currentTimeMillis()): String =
        "$FILE_PREFIX-$kind-${stamp(now)}.csv"

    // endregion

    // region 样例数据

    /**
     * 观看历史样例。
     *
     * 这一版不含「数据源接入」：标题里刻意放了逗号、双引号、换行三种要转义的情况，
     * 导出的 CSV 一打开就能验证转义对不对（见页面上的「转义示例」卡片）。
     */
    fun sampleHistory(now: Long = System.currentTimeMillis()): List<HistoryItem> = listOf(
        HistoryItem(
            title = "蓝色星球 II, 第 1 集",
            playedAt = now - 35 * 60 * 1000L,
            progressSeconds = 1450,
            durationSeconds = 2940
        ),
        HistoryItem(
            title = "\"地球脉动\" 第 3 集",
            playedAt = now - 3 * 60 * 60 * 1000L,
            progressSeconds = 2880,
            durationSeconds = 2880
        ),
        HistoryItem(
            title = "夜行者\n特别篇",
            playedAt = now - 26 * 60 * 60 * 1000L,
            progressSeconds = 120,
            durationSeconds = 3600
        ),
        HistoryItem(
            title = "街角美食 第 12 集",
            playedAt = now - 50 * 60 * 60 * 1000L,
            progressSeconds = 900,
            durationSeconds = 0
        )
    )

    /** 收藏样例：标签里带逗号，封面有的空着，时间格式与历史一致。 */
    fun sampleFavorites(now: Long = System.currentTimeMillis()): List<FavoriteItem> = listOf(
        FavoriteItem(
            title = "\"我们的星球\", 第 2 集",
            cover = "https://img.example.com/cover/our-planet-2.jpg",
            favoritedAt = now - 2 * 60 * 60 * 1000L,
            tags = listOf("纪录片", "自然, 生态")
        ),
        FavoriteItem(
            title = "夜行者\n幕后花絮",
            cover = "https://img.example.com/cover/nightwalker-extra.jpg",
            favoritedAt = now - 34 * 60 * 60 * 1000L,
            tags = listOf("剧情")
        ),
        FavoriteItem(
            title = "街角美食 第 5 集",
            cover = "",
            favoritedAt = now - 4 * 24 * 60 * 60 * 1000L,
            tags = listOf("美食", "下饭")
        )
    )

    // endregion

    // region 小工具

    /** 字节数 → `B / KB / MB / GB`，保留一位小数（< 1KB 直接给整数 B）。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** 文件名时间戳：`yyyyMMdd-HHmmss`，本地时区，排序即时间序。 */
    fun stamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

    /** 给人看的时间：`yyyy-MM-dd HH:mm:ss`（表格软件按文本列读，跨时区也不会错位一天）。 */
    fun formatTime(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

    /**
     * 结果行文案：`<路径> · 1.2 KB`。
     *
     * 页面也可以换成自己的资源文案；这里给一份默认的，方便日志 / 其它入口直接用。
     */
    fun summaryText(result: WriteResult): String =
        "%1\$s · %2\$s".format(result.path, result.bytesText())

    // endregion
}