package com.tv.ball.feature.f071

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * F071「全量数据备份」的能力层。
 *
 * 这里负责「换机前把本机数据打成一份能带走的 ZIP」这件事本身：
 * 1. 按清单把 [SharedPreferences] 导出成 JSON（`shared_prefs/<名字>.json`）；
 * 2. 把 Room / SQLite 数据库拷进包里（`databases/<库文件名>`，连同 `-wal` / `-shm` / `-journal`）；
 * 3. 把 `filesDir` 下的可选文件（例如 F042「焦点记忆」的 `f042_focus_memory.json`）带上；
 * 4. 写一份 `manifest.json`：版本号、创建时间、每个条目的相对路径 / 来源 / 字节数 / sha256。
 *
 * 只有 [backup] 碰 Android API（Context / SharedPreferences），其余是纯 Kotlin 的
 * 路径、hash、大小计算——页面层只负责选目标文件、把条目列给人看、把结果说出来。
 *
 * sha256 记在 manifest 里：还原时先比一遍 hash，包里的数据和当初备份出去的是不是同一份，
 * 一眼就能看出来，也不必为了核对把整个 ZIP 解一遍。
 */
object F071Engine {

    /** 备份格式版本。字段或目录结构变了就 +1，还原端靠它决定怎么读。 */
    const val VERSION: Int = 1

    /** 包的根标识，写进 manifest，还原端顺手校验是不是自己的包。 */
    const val KIND: String = "tvplayer-backup"

    /** manifest 在包里的固定路径。 */
    const val MANIFEST_ENTRY: String = "manifest.json"

    /** 包内目录：SharedPreferences 的 JSON 导出一律放这里。 */
    const val DIR_PREFS: String = "shared_prefs"

    /** 包内目录：数据库文件一律放这里。 */
    const val DIR_DB: String = "databases"

    /** 包内目录：filesDir 里的可选文件一律放这里。 */
    const val DIR_FILES: String = "files"

    /** 默认备份目录：应用专属外部目录，不需要存储权限，卸载应用才会被系统清掉。 */
    const val BACKUP_DIR: String = "backup"

    /** 备份文件名前缀，后面接时间戳。 */
    const val NAME_PREFIX: String = "tvplayer-"

    /** F042 焦点记忆文件：存在就顺手带上。 */
    const val OPTIONAL_FOCUS_MEMORY: String = "f042_focus_memory.json"

    /**
     * 一条要写进包里的数据。
     *
     * [entry] 是包内相对路径（`/` 分隔、不带开头斜线），同时是 manifest 里认这条的键；
     * [source] 是给人看的来源说明，页面把清单列出来时显示它；
     * prefs 条目没有 File（要从内存里现导出），文件类条目才有。
     */
    sealed class Item {

        abstract val entry: String

        abstract val source: String

        /** SharedPreferences 导出成 JSON。 */
        data class Prefs(val name: String) : Item() {
            override val entry: String get() = "$DIR_PREFS/$name.json"
            override val source: String get() = "SharedPreferences · $name"
        }

        /** 数据库文件（连同 `-wal` / `-shm` / `-journal` 一起收）。 */
        data class Database(val dbName: String) : Item() {
            override val entry: String get() = "$DIR_DB/$dbName"
            override val source: String get() = "数据库 · $dbName"
        }

        /** filesDir 下的普通文件。 */
        data class AppFile(val file: File) : Item() {
            override val entry: String get() = "$DIR_FILES/${file.name}"
            override val source: String get() = "应用文件 · ${file.name}"
        }
    }

    /** 一次备份里真正写进去的一条：包内路径、来源、字节数、sha256（小写十六进制）。 */
    data class Entry(
        val entry: String,
        val source: String,
        val bytes: Long,
        val sha256: String
    ) {
        /** 人读的大小。 */
        fun sizeText(): String = formatBytes(bytes)

        /** 展示用的短 hash。 */
        fun shortSha(): String = sha256.take(12)
    }

    /**
     * 备份结果。
     *
     * [path] 是最终落盘的 ZIP；[entries] 是实际写进去的数据条目（不含 manifest）；
     * [bytes] 是整个 ZIP 的字节数（用户真正要拷走的大小）；
     * [prefsCount] / [dbCount] / [fileCount] 是分类计数，页面拿它说清「都备份了什么」。
     */
    data class BackupResult(
        val path: String,
        val entries: List<Entry>,
        val bytes: Long,
        val createdAt: Long,
        val prefsCount: Int,
        val dbCount: Int,
        val fileCount: Int
    ) {
        /** 条目数（不含 manifest）。 */
        val entryCount: Int get() = entries.size

        /** ZIP 大小的人读文案。 */
        fun sizeText(): String = formatBytes(bytes)

        /** 数据总和：各条目字节数相加，不含 manifest 与 ZIP 压缩开销。 */
        fun payloadBytes(): Long = entries.sumOf { it.bytes }

        fun payloadSizeText(): String = formatBytes(payloadBytes())

        /** 文件名，页面显示路径时用它做标题。 */
        fun fileName(): String = path.substringAfterLast('/')
    }

    // region 清单

    /**
     * 组装本次要备份的条目清单。
     *
     * prefs 名字必须由调用方给：`getSharedPreferences` 只能按名字取，
     * 应用里有哪些名字是各功能自己知道的（设置 / 收藏 / 播放历史……），引擎不猜。
     * 数据库名同理——通常是 Room 的库文件名。
     *
     * 只收真实存在的条目：prefs 里一个键都没有的不列、数据库文件不存在的跳过、
     * 可选文件不存在的跳过。页面列出来的「将写入」因此就是「真的会写」。
     */
    fun plan(context: Context, prefsNames: List<String>, dbNames: List<String>, optionalFiles: List<File>): List<Item> {
        val items = ArrayList<Item>()
        for (name in prefsNames) {
            if (name.isBlank()) continue
            val prefs = runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE) }.getOrNull() ?: continue
            if (prefs.allOf().isEmpty()) continue
            items.add(Item.Prefs(name))
        }
        for (name in dbNames) {
            if (name.isBlank()) continue
            val file = runCatching { context.getDatabasePath(name) }.getOrNull() ?: continue
            if (!file.exists() || !file.isFile) continue
            items.add(Item.Database(name))
        }
        for (file in optionalFiles) {
            if (!file.exists() || !file.isFile) continue
            items.add(Item.AppFile(file))
        }
        return items
    }

    /** 用默认清单（[F071BackupPlan]）＋ filesDir 里的可选文件，组出本次的条目清单。 */
    fun planDefault(context: Context): List<Item> = plan(
        context,
        F071BackupPlan.PREFS,
        F071BackupPlan.DATABASES,
        optionalFiles(context)
    )

    /** 可选文件：存在才收，坏路径不影响整份备份。 */
    fun optionalFiles(context: Context): List<File> {
        val candidate = File(context.filesDir, OPTIONAL_FOCUS_MEMORY)
        return if (runCatching { candidate.isFile }.getOrDefault(false)) listOf(candidate) else emptyList()
    }

    /**
     * 默认备份目标：`getExternalFilesDir("backup")/tvplayer-<时间>.zip`。
     *
     * 外部目录拿不到（个别机型外置存储没挂上）就退回内部 `filesDir/backup`：
     * 宁可在内部也能备份，也不要这里直接抛出去让整个功能不可用。
     * 目录不存在会顺手建出来。
     */
    fun defaultTarget(context: Context, clock: () -> Long = { System.currentTimeMillis() }): File {
        val base = context.getExternalFilesDir(BACKUP_DIR) ?: File(context.filesDir, BACKUP_DIR)
        if (!base.exists()) base.mkdirs()
        return File(base, "$NAME_PREFIX${stamp(clock())}.zip")
    }

    // endregion

    // region 打包

    /**
     * 打包。
     *
     * 顺序：先按清单逐条写数据，最后写 manifest——manifest 要记每条数据的大小和 sha256，
     * 边写边算、写完再落 manifest，就不用把整包读回来算一遍。
     *
     * [targetFile] 的父目录不存在会建；写一半失败会删掉半成品并抛 [IOException]，
     * 免得磁盘上留一个「看起来像备份、其实打不开」的 zip。
     *
     * 这个调用做真实 IO + 压缩，必须在 IO 线程上跑。
     */
    fun backup(context: Context, targetFile: File): BackupResult {
        val startedAt = System.currentTimeMillis()
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("备份目录建不出来：${parent.absolutePath}")
        }

        val items = planDefault(context)
        if (items.isEmpty()) throw IOException("没有可备份的数据：SharedPreferences、数据库与应用文件都是空的")

        val written = ArrayList<Entry>()
        var prefsCount = 0
        var dbCount = 0
        var fileCount = 0

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(targetFile), 32 * 1024)).use { zip ->
                // 压缩级别 6：备份是偶尔一次，省体积比省 CPU 更划算。
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)

                for (item in items) {
                    when (item) {
                        is Item.Prefs -> {
                            val json = exportPrefs(context, item.name, { startedAt })
                            written.add(writeBytes(zip, item.entry, item.source, json.toByteArray(Charsets.UTF_8)))
                            prefsCount++
                        }

                        is Item.Database -> {
                            val dbFile = runCatching { context.getDatabasePath(item.dbName) }.getOrNull()
                            if (dbFile == null || !dbFile.exists()) continue
                            written.addAll(writeDatabase(zip, item, dbFile))
                            dbCount++
                        }

                        is Item.AppFile -> {
                            written.add(writeFile(zip, item.entry, item.source, item.file))
                            fileCount++
                        }
                    }
                }

                // manifest 本身也是一个条目，但不进 entries：entries 只描述用户数据。
                val manifest = buildManifest(startedAt, written)
                writeBytes(zip, MANIFEST_ENTRY, "备份清单", manifest.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // 半成品不留：否则文件管理器里会多一个打不开的 zip。
            runCatching { if (targetFile.exists()) targetFile.delete() }
            throw if (e is IOException) e else IOException("备份失败：${e.message}", e)
        }

        return BackupResult(
            path = targetFile.absolutePath,
            entries = written,
            bytes = targetFile.length(),
            createdAt = startedAt,
            prefsCount = prefsCount,
            dbCount = dbCount,
            fileCount = fileCount
        )
    }

    // endregion

    // region SharedPreferences 导出

    /**
     * 把一个 SharedPreferences 导出成 JSON 文本。
     *
     * 结构：`{"name":"settings","version":1,"exportedAt":1700000000000,"values":{...}}`。
     * 值按原始类型原样转：String / Int / Long / Float / Boolean，
     * StringSet 转成字符串数组；其它类型（不该出现）退化成 tostring。
     * 键名和字符串值都走 [quote]，中文和引号不会把 JSON 写坏。
     */
    fun exportPrefs(context: Context, name: String, clock: () -> Long = { System.currentTimeMillis() }): String {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return exportPrefs(prefs, name, clock)
    }

    /** 已经把 prefs 拿到手时走这个重载，页面列清单时不必再取一次。 */
    fun exportPrefs(prefs: SharedPreferences, name: String, clock: () -> Long = { System.currentTimeMillis() }): String {
        val builder = StringBuilder(256)
        builder.append('{')
        builder.append("\"name\":").append(quote(name))
        builder.append(",\"version\":").append(VERSION)
        builder.append(",\"exportedAt\":").append(clock())
        builder.append(",\"values\":{")
        var first = true
        for ((key, value) in prefs.allOf()) {
            if (!first) builder.append(',')
            first = false
            builder.append(quote(key)).append(':')
            appendValue(builder, value)
        }
        builder.append("}}")
        return builder.toString()
    }

    private fun appendValue(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is String -> builder.append(quote(value))
            is Boolean -> builder.append(value)
            is Int -> builder.append(value)
            is Long -> builder.append(value)
            // 非有限值的 JSON 表示不存在，按字符串存，还原端照样能读回去。
            is Float -> if (value.isFinite()) builder.append(value) else builder.append(quote(value.toString()))
            is Double -> if (value.isFinite()) builder.append(value) else builder.append(quote(value.toString()))
            is Set<*> -> {
                builder.append('[')
                var first = true
                for (element in value) {
                    if (!first) builder.append(',')
                    first = false
                    builder.append(quote(element?.toString() ?: ""))
                }
                builder.append(']')
            }

            else -> builder.append(quote(value.toString()))
        }
    }

    // endregion

    // region 写条目

    private fun writeDatabase(zip: ZipOutputStream, item: Item.Database, dbFile: File): List<Entry> {
        val out = ArrayList<Entry>(2)
        out.add(writeFile(zip, item.entry, item.source, dbFile))
        // Room / SQLite 在 WAL 模式下新数据可能还在 -wal 里，不带上的话还原会丢最近的写入。
        for (suffix in DB_SUFFIXES) {
            val side = File(dbFile.path + suffix)
            if (!side.exists() || !side.isFile) continue
            out.add(writeFile(zip, "$DIR_DB/${dbFile.name}$suffix", "${item.source} · 附带文件", side))
        }
        return out
    }

    /** 流式写一个文件，边写边算 sha256 与字节数，大文件不会整块进内存。 */
    private fun writeFile(zip: ZipOutputStream, entry: String, source: String, file: File): Entry {
        val digest = MessageDigest.getInstance("SHA-256")
        pushEntry(zip, entry)
        val total = FileInputStream(file).use { input -> pump(input, zip, digest) }
        zip.closeEntry()
        return Entry(entry, source, total, digest.digest().hex())
    }

    private fun writeBytes(zip: ZipOutputStream, entry: String, source: String, data: ByteArray): Entry {
        pushEntry(zip, entry)
        zip.write(data)
        zip.closeEntry()
        return Entry(entry, source, data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data).hex())
    }

    /**
     * 开一个条目。
     *
     * 时间戳固定成 0（DOS 时间的 1980-01-01）：同一份数据重打一次，
     * 「内容相同 → 包体积相同」这个直觉才成立，也不至于让某些解压工具把时间显示成一团。
     */
    private fun pushEntry(zip: ZipOutputStream, entry: String) {
        val ze = ZipEntry(entry)
        ze.time = 0L
        ze.method = ZipEntry.DEFLATED
        zip.putNextEntry(ze)
    }

    private fun pump(input: InputStream, zip: ZipOutputStream, digest: MessageDigest): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            zip.write(buffer, 0, read)
            total += read
        }
        return total
    }

    // endregion

    // region manifest

    /**
     * 生成 manifest.json。
     *
     * 字段固定：`kind` / `app` / `version` / `createdAt`（毫秒）/ `createdAtText`（给人看的时间）/
     * `entryCount` / `payloadBytes` / `entries[]`，每条 entry 带 `entry` / `source` / `bytes` / `sha256`。
     * 还原端先看 `version`，再对 entries 的 hash，最后才真去解包。
     */
    fun buildManifest(createdAt: Long, entries: List<Entry>): String {
        val builder = StringBuilder(512)
        builder.append('{')
        builder.append("\"kind\":").append(quote(KIND))
        builder.append(",\"app\":").append(quote("tv_player"))
        builder.append(",\"version\":").append(VERSION)
        builder.append(",\"createdAt\":").append(createdAt)
        builder.append(",\"createdAtText\":").append(quote(formatTime(createdAt)))
        builder.append(",\"entryCount\":").append(entries.size)
        builder.append(",\"payloadBytes\":").append(entries.sumOf { it.bytes })
        builder.append(",\"entries\":[")
        var first = true
        for (entry in entries) {
            if (!first) builder.append(',')
            first = false
            builder.append('{')
            builder.append("\"entry\":").append(quote(entry.entry))
            builder.append(",\"source\":").append(quote(entry.source))
            builder.append(",\"bytes\":").append(entry.bytes)
            builder.append(",\"sha256\":").append(quote(entry.sha256))
            builder.append('}')
        }
        builder.append("]}")
        return builder.toString()
    }

    /**
     * 解析 manifest（还原 / 自检用的最小实现，保持纯函数）。
     *
     * 解析失败返回 null：manifest 是信封，读不懂信封就不该继续往解包里走。
     */
    fun parseManifest(json: String?): Manifest? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { MiniJson.parseObject(json) }.getOrNull() ?: return null
        val version = (root["version"] as? Double)?.toInt() ?: return null
        val createdAt = (root["createdAt"] as? Double)?.toLong() ?: 0L
        val list = ArrayList<Entry>()
        val raw = root["entries"] as? List<*> ?: emptyList<Any?>()
        for (element in raw) {
            val item = element as? Map<*, *> ?: continue
            val entry = item["entry"] as? String ?: continue
            val source = item["source"] as? String ?: ""
            val bytes = (item["bytes"] as? Double)?.toLong() ?: 0L
            val sha = item["sha256"] as? String ?: ""
            list.add(Entry(entry, source, bytes, sha))
        }
        return Manifest(version, createdAt, list)
    }

    /** 解出来的 manifest。 */
    data class Manifest(val version: Int, val createdAt: Long, val entries: List<Entry>) {

        /** 版本读不读得懂：不高于本端实现的就是兼容的。 */
        fun isCompatible(): Boolean = version in 1..VERSION
    }

    // endregion

    // region 小工具

    /** 字节数写成 B / KB / MB / GB，保留一位小数（< 1KB 直接给整数 B）。 */
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

    /** 给人看的时间：`yyyy-MM-dd HH:mm:ss`。 */
    fun formatTime(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

    /** JSON 字符串转义：双引号、反斜杠、换行与控制字符。 */
    internal fun quote(raw: String): String {
        val builder = StringBuilder(raw.length + 2)
        builder.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') {
                    builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private fun ByteArray.hex(): String {
        val chars = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    /** 数据库随附文件后缀：WAL 模式的写入在 -wal 里，-shm 是索引，-journal 是回滚日志。 */
    private val DB_SUFFIXES = listOf("-wal", "-shm", "-journal")

    private val HEX = "0123456789abcdef".toCharArray()

    // endregion

    /**
     * 极简 JSON 读取器，只为把 manifest 读回来：对象 / 数组 / 字符串 / 数字 / true / false / null。
     */
    private object MiniJson {

        fun parseObject(text: String): Map<String, Any?> {
            val value = Reader(text).readValue()
            return value as? Map<String, Any?> ?: emptyMap()
        }

        private class Reader(private val src: String) {

            private var pos = 0

            fun skipSpace() {
                while (pos < src.length && src[pos].isWhitespace()) pos++
            }

            fun readValue(): Any? {
                skipSpace()
                if (pos >= src.length) throw IllegalArgumentException("JSON 提前结束")
                return when (val ch = src[pos]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    't' -> {
                        expectLiteral("true")
                        true
                    }

                    'f' -> {
                        expectLiteral("false")
                        false
                    }

                    'n' -> {
                        expectLiteral("null")
                        null
                    }

                    else -> if (ch == '-' || ch.isDigit()) readNumber() else {
                        throw IllegalArgumentException("JSON 里出现意外字符 '$ch'")
                    }
                }
            }

            private fun readObject(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                pos++
                skipSpace()
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                while (true) {
                    skipSpace()
                    val key = readString()
                    skipSpace()
                    if (pos >= src.length || src[pos] != ':') throw IllegalArgumentException("缺少 ':'")
                    pos++
                    map[key] = readValue()
                    skipSpace()
                    if (pos >= src.length) throw IllegalArgumentException("对象没有闭合")
                    when (src[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return map
                        }

                        else -> throw IllegalArgumentException("对象里出现意外字符 '${src[pos]}'")
                    }
                }
            }

            private fun readArray(): List<Any?> {
                val list = ArrayList<Any?>()
                pos++
                skipSpace()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (true) {
                    list.add(readValue())
                    skipSpace()
                    if (pos >= src.length) throw IllegalArgumentException("数组没有闭合")
                    when (src[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return list
                        }

                        else -> throw IllegalArgumentException("数组里出现意外字符 '${src[pos]}'")
                    }
                }
            }

            private fun readString(): String {
                if (pos >= src.length || src[pos] != '"') throw IllegalArgumentException("缺少字符串引号")
                pos++
                val builder = StringBuilder()
                while (pos < src.length) {
                    when (val ch = src[pos++]) {
                        '"' -> return builder.toString()
                        '\\' -> {
                            if (pos >= src.length) throw IllegalArgumentException("转义被截断")
                            when (val esc = src[pos++]) {
                                '"' -> builder.append('"')
                                '\\' -> builder.append('\\')
                                '/' -> builder.append('/')
                                'b' -> builder.append('\b')
                                'f' -> builder.append('\u000C')
                                'n' -> builder.append('\n')
                                'r' -> builder.append('\r')
                                't' -> builder.append('\t')
                                'u' -> {
                                    if (pos + 4 > src.length) throw IllegalArgumentException("\\u 转义被截断")
                                    val hex = src.substring(pos, pos + 4)
                                    pos += 4
                                    builder.append(hex.toInt(16).toChar())
                                }

                                else -> throw IllegalArgumentException("未知转义 '\\$esc'")
                            }
                        }

                        else -> builder.append(ch)
                    }
                }
                throw IllegalArgumentException("字符串没有闭合")
            }

            private fun readNumber(): Double {
                val start = pos
                if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
                while (pos < src.length && isNumberChar(src[pos])) pos++
                val text = src.substring(start, pos)
                return text.toDoubleOrNull() ?: throw IllegalArgumentException("数字格式不对：$text")
            }

            private fun isNumberChar(ch: Char): Boolean =
                ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-'

            private fun expectLiteral(literal: String) {
                if (!src.startsWith(literal, pos)) throw IllegalArgumentException("字面量不对：期望 $literal")
                pos += literal.length
            }
        }
    }
}

/**
 * SharedPreferences 的键值快照。
 *
 * `prefs.all` 的返回类型是 `Map<String, *>`，每处都写一遍强转很啰嗦；
 * 这里统一成 `Map<String, Any?>`，导出时按值的实际类型分支即可。
 */
internal fun SharedPreferences.allOf(): Map<String, Any?> {
    val raw = runCatching { all }.getOrNull() ?: emptyMap<String, Any?>()
    val out = LinkedHashMap<String, Any?>(raw.size)
    for ((key, value) in raw) out[key] = value
    return out
}

/**
 * 默认清单：应用里真正落盘的那几份数据，页面启动时用它列「将写入的条目」。
 *
 * 名字都是惯例命名，实际取不到就由 [F071Engine.plan] 过滤掉——
 * 清单给宽一点没有副作用，缺的文件不会凭空多出一条。
 */
object F071BackupPlan {

    /** SharedPreferences 名字清单。 */
    val PREFS: List<String> = listOf(
        "settings",
        "tv_settings",
        "favorite",
        "favorites",
        "history",
        "play_history",
        "playlist",
        "source_config"
    )

    /** Room / SQLite 数据库文件名清单。 */
    val DATABASES: List<String> = listOf(
        "tv_player.db",
        "tvplayer.db",
        "app_database"
    )
}