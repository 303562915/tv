package com.tv.ball.feature.f094

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 「缓存分类清理」的能力层。
 *
 * ## 它做两件事
 *
 * 1. [scan]：把 `cacheDir` 这一棵树按**归档原因**分成图片 / 视频 / 日志 / 其他四类，
 *    给出每一类的字节数与文件数；
 * 2. [clear] / [clearAll]：只删**某一类**（或四类全删）的缓存文件，逐个 `File.delete()`，
 *    并在返回值里如实统计删掉了多少、失败了多少。
 *
 * ## 分类口径（顺序即优先级）
 *
 * 1. `cacheDir/thumbnail_cache` 及其子目录 → [Category.IMAGE]：缩略图缓存（和 F021 / F076 是同一个目录），
 *    这一层里的文件不再看扩展名——它整块就是封面图缓存，清图片时就该一起清；
 * 2. 路径上带 `logs` 目录，或文件名是 `*.log` / 轮转日志 `*.log.1` → [Category.LOG]；
 * 3. `cacheDir` 下的视频扩展名（mp4 / mkv / ts / m3u8 …）→ [Category.VIDEO]：播放与下载过程留下的分片；
 * 4. 剩下的 → [Category.OTHER]。
 *
 * 优先级是硬性的，所以每个文件**只属于一类**，四类字节数相加严格等于 `cacheDir` 的总占用——
 * 页面上「四行相加 == 顶部合计」就是靠这条成立，不需要再补一个「未知」桶来抹平差额。
 * 也因此 `thumbnail_cache/a.log` 算图片、`logs/a.mp4` 算日志，这两条都是有意为之。
 *
 * ## 清理边界（哪些绝对不碰）
 *
 * - 只遍历 `cacheDir` 内部：数据库 `databases/`（收藏、历史、播放记录都在库里）、
 *   `filesDir`、外置存储里下载好的成品视频都不在收集范围里，删不到也删不坏；
 * - 符号链接一律跳过：不跟随、不删除。链接指向哪里不确定，删链接等于赌运气；
 * - 删完只顺手收掉**因这次清理变空**的目录（仍在 `cacheDir` 内部）。
 *   目录不占多少字节，也不计入 [ClearResult]，只是别让用户看见一堆空壳文件夹。
 *
 * ## 线程
 *
 * 全部方法都要在 IO 线程调用：递归遍历 + 每个文件一次 `length()`（清理时再加一次 `delete()`），
 * 几千个文件的量级放在主线程就是肉眼可见的卡顿。
 */
object F094Engine {

    /** 缩略图缓存目录名：F021「缩略图磁盘缓存」和 F076「存储占用分析」用的是同一个目录。 */
    const val THUMB_DIR_NAME: String = "thumbnail_cache"

    /** 日志目录名：`cacheDir/logs` 下的一切都按日志算。 */
    const val LOG_DIR_NAME: String = "logs"

    /**
     * 视频扩展名：在 `cacheDir` 下出现就归「视频」类。
     *
     * 收的都是播放器/下载器会落在缓存里的格式：`ts` / `m3u8` 是 HLS 分片与播放列表，
     * `mp4` / `mkv` 是边下边播的临时文件。不含 `jpg` / `png` 这类图片格式——
     * 除缩略图目录之外的散图归「其他」，因为它们不是可复用的封面缓存。
     */
    private val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "mkv", "avi", "mov", "wmv", "flv", "webm",
        "ts", "m3u8", "mpg", "mpeg", "rmvb", "rm", "3gp", "f4v"
    )

    /**
     * 缓存分类。
     *
     * [label] 是给人看的短名，清单与对话框标题直接用它；
     * 声明顺序就是页面四行的顺序，[ordered] 把它固定下来，避免各处自己排一遍。
     */
    enum class Category(val label: String) {
        /** 缩略图缓存：封面、剧照的本地副本。 */
        IMAGE("图片"),

        /** 播放 / 下载留下的临时视频与分片。 */
        VIDEO("视频"),

        /** 运行日志与轮转日志。 */
        LOG("日志"),

        /** 其余缓存：接口响应、字体、临时 JSON 之类。 */
        OTHER("其他");

        companion object {
            /** 固定展示顺序：图片 → 视频 → 日志 → 其他。 */
            val ordered: List<Category> = listOf(IMAGE, VIDEO, LOG, OTHER)
        }
    }

    /**
     * 一类缓存的统计结果。
     *
     * @param category 分类。
     * @param bytes 该类文件的字节数合计（`length()` 相加，不是估值）。
     * @param files 该类文件的个数。
     */
    data class Bucket(
        val category: Category,
        val bytes: Long,
        val files: Int
    ) {
        /** 人读大小，例如 `12.3 MB`。 */
        fun sizeText(): String = formatBytes(bytes)

        /** 这一类是不是已经空了；页面据此禁掉「清理」按钮。 */
        fun isEmpty(): Boolean = files <= 0
    }

    /**
     * 一次清理的回执。
     *
     * @param files 真正删掉的文件数。
     * @param bytes 删掉的文件字节数（按**删除前**的 `length()` 累加，删完就量不到了）。
     * @param failed 删除失败的文件数：`File.delete()` 返回 false 或抛异常的，都算进来。
     *
     * 失败不吞掉：文件可能正被播放器占着、也可能权限不足，
     * 页面上「删了 8 个、2 个没删掉」比「已清理」这种一句话诚实得多。
     */
    data class ClearResult(
        val files: Int,
        val bytes: Long,
        val failed: Int
    ) {
        /** 有没有真的删掉东西。 */
        val removed: Boolean get() = files > 0

        /** 有没有失败。 */
        val hasFailure: Boolean get() = failed > 0

        companion object {
            /** 什么都没发生（目录不存在、分类为空）时的回执。 */
            val EMPTY: ClearResult = ClearResult(files = 0, bytes = 0L, failed = 0)
        }
    }

    // ===== 统计 =====

    /**
     * 扫一遍缓存，按 [Category.ordered] 的顺序返回四类。
     *
     * 四类**永远都在**（空的给 0），页面直接按下标连线，不用处理「某一类不在列表里」的情况。
     *
     * 必须在 IO 线程调用。`cacheDir` 取不到或不存在时返回四个 0，不抛异常：
     * 统计页因为一个目录读不了就整页报错，比少几行数字更糟。
     */
    fun scan(context: Context): List<Bucket> {
        val app = context.applicationContext ?: context
        val cache = cacheDirOf(app)
        val bytes = HashMap<Category, Long>(Category.ordered.size)
        val counts = HashMap<Category, Int>(Category.ordered.size)

        if (cache != null) {
            for (file in collectFiles(cache)) {
                val category = classify(file, cache)
                bytes[category] = (bytes[category] ?: 0L) + lengthOf(file)
                counts[category] = (counts[category] ?: 0) + 1
            }
        }

        return Category.ordered.map { category ->
            Bucket(
                category = category,
                bytes = bytes[category] ?: 0L,
                files = counts[category] ?: 0
            )
        }
    }

    /**
     * 单类的统计结果；[buckets] 里没有这一类时给一个 0。
     *
     * 页面渲染与「清理前二次确认」都用它取数字，保证对话框里的数量就是列表上的数量。
     */
    fun of(buckets: List<Bucket>, category: Category): Bucket =
        buckets.firstOrNull { it.category == category } ?: Bucket(category, 0L, 0)

    /** 四类合计字节数；与逐行相加严格一致（每个文件只归一类，不会重复计）。 */
    fun total(buckets: List<Bucket>): Long = buckets.sumOf { it.bytes }

    /** 四类合计文件数。 */
    fun totalFiles(buckets: List<Bucket>): Int = buckets.sumOf { it.files }

    /** 四类全 0 的空表，页面在还没统计出来时先拿它占位。 */
    fun emptyBuckets(): List<Bucket> =
        Category.ordered.map { Bucket(it, bytes = 0L, files = 0) }

    // ===== 清理 =====

    /**
     * 只清某一类。
     *
     * 做法分两步，不边遍历边删：先把属于该类的文件**列全**，再逐个删。
     * `walkTopDown` 是一个活的迭代器，删除会打乱目录项顺序，边遍历边删会漏文件。
     *
     * 安全边界见类注释：只看 `cacheDir`，跳过符号链接，不碰数据库与 `filesDir`。
     */
    fun clear(context: Context, category: Category): ClearResult {
        val app = context.applicationContext ?: context
        val cache = cacheDirOf(app) ?: return ClearResult.EMPTY

        val targets = ArrayList<File>()
        for (file in collectFiles(cache)) {
            if (classify(file, cache) == category) targets.add(file)
        }
        return deleteAll(cache, targets)
    }

    /**
     * 清全部四类缓存。
     *
     * 刻意复用 [clear] 逐类清，而不是「把 `cacheDir` 整个删掉」：
     * 分类口径只有一处，四类的删除范围与页面上那四行永远对得上；
     * 顺序也照 [Category.ordered]，回执是四次结果的相加。
     */
    fun clearAll(context: Context): ClearResult {
        var files = 0
        var bytes = 0L
        var failed = 0
        for (category in Category.ordered) {
            val result = clear(context, category)
            files += result.files
            bytes += result.bytes
            failed += result.failed
        }
        return ClearResult(files = files, bytes = bytes, failed = failed)
    }

    /**
     * 逐个删文件并记账。
     *
     * - 先量 `length()` 再删：删完文件就没了，事后补不回来；
     * - `delete()` 返回 false（占用 / 权限）或抛异常都记进 [ClearResult.failed]，继续删下一个，
     *   不因为一个删不掉的文件把整类清理中断；
     * - 删成功的文件把父目录收进待检查集合，最后清一遍变空的壳目录。
     */
    private fun deleteAll(cacheRoot: File, targets: List<File>): ClearResult {
        if (targets.isEmpty()) return ClearResult.EMPTY

        var files = 0
        var bytes = 0L
        var failed = 0
        val touchedDirs = LinkedHashSet<File>()

        for (file in targets) {
            val size = lengthOf(file)
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (deleted) {
                files++
                bytes += size
                file.parentFile?.let { touchedDirs.add(it) }
            } else {
                failed++
            }
        }

        pruneEmptyDirs(cacheRoot, touchedDirs)
        return ClearResult(files = files, bytes = bytes, failed = failed)
    }

    /**
     * 收掉因为这次清理变空的目录。
     *
     * 从每个受影响目录往上走到 `cacheDir`（不含它自己）：空就删、删不动或非空就停。
     * 只处理 [touchedDirs] 里的父目录链，不做全树扫描——
     * 原本就空着的目录不归这次清理管，顺手删掉只会让「上次清理」这种统计口径变得可疑。
     */
    private fun pruneEmptyDirs(cacheRoot: File, touchedDirs: Set<File>) {
        val rootPath = cacheRoot.absolutePath
        for (start in touchedDirs) {
            var current: File? = start
            while (current != null) {
                val dir = current
                val path = dir.absolutePath
                if (path == rootPath || !path.startsWith(rootPath)) break
                val empty = runCatching { dir.list()?.isEmpty() ?: false }.getOrDefault(false)
                if (!empty) break
                val removed = runCatching { dir.delete() }.getOrDefault(false)
                if (!removed) break
                current = dir.parentFile
            }
        }
    }

    // ===== 分类判定 =====

    /**
     * 一个文件的分类。优先级见类注释：缩略图目录 → 日志 → 视频扩展名 → 其他。
     *
     * [cacheRoot] 只用来算相对路径（判断第一层是不是 `thumbnail_cache`），
     * 判定本身不读任何全局状态，纯字符串 + 文件名，重复调用无副作用。
     */
    private fun classify(file: File, cacheRoot: File): Category {
        val segments = relativeSegments(file, cacheRoot)

        // 1. 缩略图缓存整块算图片：这一层里的文件不再看扩展名。
        if (segments.isNotEmpty() && segments[0].equals(THUMB_DIR_NAME, ignoreCase = true)) {
            return Category.IMAGE
        }

        // 2. 落在某个 logs 目录里的，一律算日志。
        if (segments.dropLast(1).any { it.equals(LOG_DIR_NAME, ignoreCase = true) }) {
            return Category.LOG
        }

        val name = file.name
        if (isLogFile(name)) return Category.LOG

        // 3. 视频扩展名。
        if (extensionOf(name) in VIDEO_EXTENSIONS) return Category.VIDEO

        // 4. 其余兜底。
        return Category.OTHER
    }

    /**
     * 相对 `cacheDir` 的路径分段，例如 `thumbnail_cache/2026/a.jpg` → [thumbnail_cache, 2026, a.jpg]。
     *
     * 用绝对路径前缀来切，而不是 `canonicalPath`：判定只需要「是不是第一层缩略图目录」，
     * 每个文件都做一次 canonical 解析要多几万次系统调用；链接在 [collectFiles] 里已经挡掉了。
     */
    private fun relativeSegments(file: File, cacheRoot: File): List<String> {
        val rootPath = cacheRoot.absolutePath
        val path = file.absolutePath
        val relative = if (path.length > rootPath.length && path.startsWith(rootPath)) {
            path.substring(rootPath.length).trimStart(File.separatorChar)
        } else {
            file.name
        }
        return relative.split(File.separatorChar).filter { it.isNotEmpty() }
    }

    /** 是不是日志文件：`.log` 结尾，或轮转出来的 `xxx.log.1` / `xxx.log.2.gz`。 */
    private fun isLogFile(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        if (lower.endsWith(".log")) return true
        return lower.contains(".log.")
    }

    /** 小写扩展名；没有扩展名时给空串。 */
    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    // ===== 文件遍历 =====

    /**
     * 收集 `cacheDir` 之下所有**普通文件**。
     *
     * - 只走 [File.walkTopDown]，不跨出 `cacheDir`；
     * - `onEnter` 里挡掉符号链接目录：链接指回上层会让遍历原地打转，指到外置存储会把别人的文件拉进来；
     * - 文件层面的链接也跳过——链接的 `isFile` 同样为 true，但删它删的是链接，
     *   统计到的却是目标的大小，账面会错；
     * - 目录不存在 / 读不了时返回空表，不抛：统计页不该因为一棵树读不了整页失败。
     */
    private fun collectFiles(cacheRoot: File): List<File> {
        if (!exists(cacheRoot) || !cacheRoot.isDirectory) return emptyList()
        val out = ArrayList<File>()
        val walk = cacheRoot.walkTopDown().onEnter { dir -> !isSymlink(dir) }
        for (entry in walk) {
            if (entry.isDirectory) continue
            if (isSymlink(entry)) continue
            out.add(entry)
        }
        return out
    }

    /** `cacheDir`；拿不到时 null（极少数 ROM 会抛）。 */
    private fun cacheDirOf(context: Context): File? =
        runCatching { context.cacheDir }.getOrNull()

    private fun lengthOf(file: File): Long =
        runCatching { file.length() }.getOrDefault(0L)

    private fun exists(file: File): Boolean =
        runCatching { file.exists() }.getOrDefault(false)

    /**
     * 是不是符号链接。
     *
     * 不用 `Os.lstat`（F076 里那种写法）：本页只在 IO 线程跑，
     * `canonicalPath` 一次解析足够，少一个 `android.system` 依赖，行为在各版本 ROM 上更稳。
     */
    private fun isSymlink(file: File): Boolean = try {
        file.canonicalPath != file.absolutePath
    } catch (e: Throwable) {
        // 解析失败（断链、权限）时按「不是链接」处理：断链文件的 length 是 0，多算 0 字节无害。
        false
    }

    // ===== 展示 =====

    /**
     * 人读的大小：B / KB / MB / GB。
     *
     * 二进制口径（1 KB = 1024 B），与系统「应用占用」页面一致，用户对得上数。
     * B 给整数，KB / MB 留一位，GB 留两位——够看清量级，又不至于一串小数。
     */
    fun formatBytes(bytes: Long): String {
        val value = if (bytes < 0L) 0L else bytes
        if (value < 1024L) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
