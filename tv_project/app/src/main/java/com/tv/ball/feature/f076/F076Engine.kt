package com.tv.ball.feature.f076

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import com.tv.ball.R
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 一类占用。
 *
 * [name] / [pathDesc] / [actionHint] 都是给人看的文案，由 [F076Engine.analyze] 从资源里取；
 * [bytes] 是这一类目录递归累加出来的真实字节数，不是估算。
 *
 * [actionHint] 只说明「这一类的清理入口在哪一页」——
 * 本功能的按钮不删任何文件，删除是不可逆动作，应交回对应功能页自己做。
 */
data class Bucket(
    val name: String,
    val bytes: Long,
    val pathDesc: String,
    val actionHint: String
) {
    /** 人读大小，例如 `12.3 MB`。 */
    fun sizeText(): String = F076Engine.formatBytes(bytes)
}

/**
 * F076「存储占用分析」的能力层。
 *
 * 只做一件事：把「本机到底被谁占了多少」量出来，量的是真实目录、真实字节数：
 *   1. 缓存 `cacheDir`（**剔除**缩略图缓存那一块，否则和下面第 5 项重复计算）；
 *   2. 应用文件 `filesDir`；
 *   3. 数据库 `databases/`：主库 + `-wal` / `-shm` / `-journal`，
 *      WAL 模式下最近的写入就躺在 `-wal` 里，只看主库会少算一大截；
 *   4. 下载目录 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，外置存储没挂载时按 0 算；
 *   5. 缩略图缓存 `cacheDir/thumbnail_cache`，和 F021「缩略图磁盘缓存」用的是同一个目录。
 *
 * 目录大小一律用 [File.walkTopDown] 递归累加，并**逐层跳过符号链接**：
 * 链接指回上层或指到共享目录会把同一份字节数算两遍，极端情况下（指回自身）还会让遍历原地打转。
 * 链接判定走 `Os.lstat` 的 `st_mode`（API 21 起可用），拿不到时退回 canonical 路径比较。
 *
 * 只有 [analyze] / [databaseFiles] 碰 Android API；[total] / [share] / [percent] / [formatBytes]
 * 是纯 Kotlin，页面层算总量与占比都走这些函数，保证「列表逐行相加 == 顶部合计」。
 */
object F076Engine {

    /** 缩略图缓存目录名：F021 也是同一个目录，两边口径必须一致。 */
    const val THUMB_DIR_NAME: String = "thumbnail_cache"

    /** 主库文件名：只用它反查 databases 目录，不硬编码 `/data/data/<包名>/databases`。 */
    const val DB_NAME: String = "tv_player.db"

    /** 分类数量：缓存 / 应用文件 / 数据库 / 下载 / 缩略图，顺序就是占比条从左到右的顺序。 */
    const val BUCKET_COUNT: Int = 5

    /** 数据库随附文件前缀：WAL 的新写入在 -wal，共享索引在 -shm，回滚日志在 -journal。 */
    private val DB_SIDE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

    /**
     * 逐类量一遍，返回五类占用。
     *
     * 必须在 IO 线程调用：五棵目录树，几千到几万个文件的 `length()`，
     * 主线程上跑一次就是几百毫秒的卡顿。
     *
     * 返回顺序固定（缓存 → 应用文件 → 数据库 → 下载 → 缩略图），
     * 页面按同样的下标取颜色，占比条和下面的列表才对得上。
     */
    fun analyze(context: Context): List<Bucket> {
        val app = context.applicationContext ?: context
        val cacheDir = safeDir { app.cacheDir }
        val filesDir = safeDir { app.filesDir }
        val downloadDir = safeDir { app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }
        val thumbDir = cacheDir?.let { File(it, THUMB_DIR_NAME) }
        val dbDir = databasesDir(app)
        val dbFiles = databaseFiles(app)

        return listOf(
            Bucket(
                name = app.getString(R.string.f076_bucket_cache),
                // 缩略图单独算一类，这里把它从缓存里扣掉，五类相加才不会重复
                bytes = dirBytes(cacheDir, skipTopLevel = setOf(THUMB_DIR_NAME)),
                pathDesc = describe(app, R.string.f076_path_cache, cacheDir),
                actionHint = app.getString(R.string.f076_hint_cache)
            ),
            Bucket(
                name = app.getString(R.string.f076_bucket_files),
                bytes = dirBytes(filesDir),
                pathDesc = describe(app, R.string.f076_path_files, filesDir),
                actionHint = app.getString(R.string.f076_hint_files)
            ),
            Bucket(
                name = app.getString(R.string.f076_bucket_database),
                bytes = dirBytes(dbDir),
                pathDesc = if (dbDir == null) {
                    app.getString(R.string.f076_path_missing)
                } else {
                    app.getString(R.string.f076_path_database, dbDir.absolutePath, dbFiles.size)
                },
                actionHint = app.getString(R.string.f076_hint_database)
            ),
            Bucket(
                name = app.getString(R.string.f076_bucket_download),
                bytes = dirBytes(downloadDir),
                pathDesc = describe(app, R.string.f076_path_download, downloadDir),
                actionHint = app.getString(R.string.f076_hint_download)
            ),
            Bucket(
                name = app.getString(R.string.f076_bucket_thumb),
                bytes = dirBytes(thumbDir),
                pathDesc = describe(app, R.string.f076_path_thumb, thumbDir),
                actionHint = app.getString(R.string.f076_hint_thumb)
            )
        )
    }

    /**
     * 数据库三件套：主库 + 随附文件（存在才收）。
     *
     * 名字走 [DB_NAME]，位置靠 [Context.getDatabasePath] 反查——
     * 有些 ROM 会把数据库目录重定向，硬编码绝对路径一定会在某台盒子上量成 0。
     */
    fun databaseFiles(context: Context, name: String = DB_NAME): List<File> {
        val main = runCatching { context.getDatabasePath(name) }.getOrNull() ?: return emptyList()
        val out = ArrayList<File>(DB_SIDE_SUFFIXES.size + 1)
        if (exists(main)) out.add(main)
        for (suffix in DB_SIDE_SUFFIXES) {
            val side = File(main.path + suffix)
            if (exists(side)) out.add(side)
        }
        return out
    }

    /** databases 目录：用主库路径反查父目录，拿不到时退回应用数据目录下的同名目录。 */
    fun databasesDir(context: Context): File? {
        val main = runCatching { context.getDatabasePath(DB_NAME) }.getOrNull()
        main?.parentFile?.let { return it }
        val dataDir = runCatching { context.applicationInfo.dataDir }.getOrNull()
        return dataDir?.let { File(it, "databases") }
    }

    /** 五类合计；与列表逐行字节数相加严格一致（缩略图已从缓存里扣掉，不会重复）。 */
    fun total(buckets: List<Bucket>): Long = buckets.sumOf { it.bytes }

    /** 人读的大小；实现放在 [Bytes]，这里只是给页面一个短名字。 */
    fun formatBytes(bytes: Long): String = Bytes.format(bytes)

    /** 占总量多少（0f..1f）；总量为 0 时给 0，页面据此把条留空。 */
    fun share(bytes: Long, total: Long): Float = Bytes.ratio(bytes, total)

    /** 百分比整数（四舍五入到 0..100），用于「占 12%」。 */
    fun percent(bytes: Long, total: Long): Int {
        if (bytes <= 0L || total <= 0L) return 0
        val raw = (bytes.toDouble() / total.toDouble() * 100.0).roundToInt()
        return raw.coerceIn(0, 100)
    }

    // region 目录递归

    /**
     * 递归累加一个目录下的真实字节数。
     *
     * - `walkTopDown()` 会把目录本身也吐出来，所以只累加 `!isDirectory` 的条目；
     * - `onEnter` 里同时挡掉符号链接（返回 false 就不再往下走，避免死循环）与
     *   [skipTopLevel] 里列出的**顶层**子目录（缩略图缓存要单独算一类）；
     * - 每个文件再过一遍链接判定：链接文件指向真实文件时 `isFile` 也为 true，
     *   不挡掉就会把同一份内容算两遍；
     * - 目录不存在、权限不足、拿不到 length 一律按 0 算——统计页不该因为一个目录读不了就整页失败。
     */
    private fun dirBytes(root: File?, skipTopLevel: Set<String> = emptySet()): Long {
        if (root == null || !exists(root)) return 0L
        if (!root.isDirectory) return if (root.isFile && !isSymlink(root)) root.length() else 0L

        var bytes = 0L
        val walk = root.walkTopDown().onEnter { dir ->
            !isSymlink(dir) && !(dir.parentFile == root && dir.name in skipTopLevel)
        }
        for (file in walk) {
            if (file.isDirectory) continue
            if (isSymlink(file)) continue
            bytes += file.length()
        }
        return bytes
    }

    /**
     * 是不是符号链接。
     *
     * 首选 `Os.lstat`：它不会跟随链接，`st_mode` 里就是链接本身。
     * 拿不到时（极少数路径 / 权限）退回 canonical 比较：真链接解析后路径会变。
     */
    private fun isSymlink(file: File): Boolean {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
        } catch (e: Throwable) {
            return try {
                file.canonicalPath != file.absolutePath
            } catch (e2: Throwable) {
                false
            }
        }
    }

    private fun exists(file: File): Boolean = runCatching { file.exists() }.getOrDefault(false)

    private fun safeDir(block: () -> File?): File? = runCatching { block() }.getOrNull()

    /** 路径文案：拿不到目录就说明原因，不显示一个假的绝对路径。 */
    private fun describe(context: Context, labelRes: Int, dir: File?): String =
        if (dir == null) {
            context.getString(R.string.f076_path_missing)
        } else {
            context.getString(labelRes, dir.absolutePath)
        }

    // endregion
}

/**
 * 字节数与占比的小工具。
 *
 * 页面里「12.3 MB」「占 37%」「条占多宽」全是这里算的：
 * 总量和逐行字节数来自同一份 [Bucket]，所以条宽、百分比、合计三者不会互相打脸。
 */
object Bytes {

    /** 1 KB 的字节数。二进制口径（1 KB = 1024 B），与系统「应用占用」页面一致。 */
    private const val KB: Double = 1024.0

    /** B / KB / MB / GB，B 给整数，其余保留一位（GB 两位），够看清量级又不啰嗦。 */
    fun format(value: Long): String {
        val bytes = if (value < 0L) 0L else value
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / KB
        if (kb < KB) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / KB
        if (mb < KB) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / KB)
    }

    /** 占比（0f..1f）；[bytes] 或 [total] 非正时给 0，页面据此留空。 */
    fun ratio(bytes: Long, total: Long): Float {
        if (bytes <= 0L || total <= 0L) return 0f
        return (bytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}
