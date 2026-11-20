package com.tv.ball.feature.f070

import java.io.File
import java.io.RandomAccessFile

/**
 * 一次 Range 请求的解析结果。
 *
 * 播放器拖动进度条时会发 `Range: bytes=start-end`，服务端只有两种合法回应：
 * 206 + `Content-Range`（返回请求的那一段），或者 416（请求的那一段根本不存在）。
 * 需要整体返回时（没有 Range 头 / 语法不认识）则给 200 + 全量。
 */
sealed class RangeResult {

    /** 请求的那一段真实存在，应当回 206。 */
    data class Partial(val start: Long, val end: Long) : RangeResult() {
        /** 这一段有多少字节；[F070Engine.headersFor] 的 `Content-Length` 就是它。 */
        val length: Long get() = end - start + 1L
    }

    /** 客户端没有要求分段（没有 Range 头，或语法不认识），应回 200 + 整个文件。 */
    data object Full : RangeResult()

    /**
     * 请求的一段超出了文件范围，或者多个区间里一个都满足不了，应回 416。
     *
     * [totalSize] 随结果一起带着：416 的响应必须给出 `Content-Range: bytes * /total`，
     * 否则播放器不知道文件到底多长，会直接判定资源不可用。
     */
    data class Unsatisfiable(val totalSize: Long) : RangeResult()
}

/**
 * Range 请求解析与分块流式输出（纯 Kotlin / 纯 JDK，不依赖 Android 与网络框架）。
 *
 * 要解决的两个问题：
 *
 * 1. **拖动进度条**：播放器发 `Range: bytes=1048576-` 要后半段，服务端必须按区间返回 206，
 *    否则每次拖动都要从 0 开始重新下载。
 * 2. **大文件不整读内存**：一部 4K 片源 20 GB，`File.readBytes()` 会直接把电视内存打爆，
 *    所以真正往外发数据时走 [streamChunk]，用 [RandomAccessFile] 按 [DEFAULT_CHUNK_SIZE] 一段段搬。
 *
 * 解析规则按 RFC 9110 §14 收窄到播放器真正会发的写法：
 *
 * | 请求头                | 结果                        |
 * |-----------------------|-----------------------------|
 * | `bytes=0-`            | Partial(0, total-1) → 206   |
 * | `bytes=100-200`       | Partial(100, 200) → 206     |
 * | `bytes=-500`          | 末尾 500 字节 → 206         |
 * | `bytes=999999999-`    | Unsatisfiable → 416         |
 * | `bytes=abc` / `items=`| Full → 200（不认识的单位按"整份"处理） |
 *
 * 多区间（`bytes=0-99,200-299`）按 RFC 允许的方式降级成整份 200：
 * 返回 multipart/byteranges 需要重新拼 body，对"能播就行"的电视场景没有收益。
 *
 * 所有函数都不持状态、不开线程，调用方可直接在 IO 上跑。
 */
object F070Engine {

    /** 分块读取的单块大小：64 KB 是 IO 与 syscall 次数的平衡点，电视盒子上一块也就几十微秒。 */
    const val DEFAULT_CHUNK_SIZE: Int = 64 * 1024

    /** 小于它就整份读也够快（封面、字幕、几 MB 的小片段），但仍然走分块路径，逻辑只有一条。 */
    const val SMALL_FILE_BYTES: Long = 512L * 1024L

    /** `Accept-Ranges` 的固定取值：只要服务端支持 Range，就该回这个。 */
    const val ACCEPT_RANGES_VALUE: String = "bytes"

    /** 206 状态码。 */
    const val STATUS_PARTIAL: Int = 206

    /** 200 状态码。 */
    const val STATUS_OK: Int = 200

    /** 416 状态码。 */
    const val STATUS_RANGE_NOT_SATISFIABLE: Int = 416

    // ===== 解析 =====

    /**
     * 解析 `Range` 请求头。
     *
     * @param header    原始头值，如 `bytes=100-200`；null / 空白 / 不认识都算"没要求分段"
     * @param totalSize 资源总字节数；≤ 0 时任何分段都不可满足
     * @return 见 [RangeResult] 的三种情形
     *
     * 只支持 `bytes` 单位：`items=0-9` 这类其它单位按 RFC 是"忽略该头"，也就是整份返回。
     * 多区间同样降级成整份，理由见类注释。
     */
    fun parseRange(header: String?, totalSize: Long): RangeResult {
        if (totalSize <= 0L) return RangeResult.Unsatisfiable(totalSize)
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return RangeResult.Full

        // 单位前缀必须带等号，且单位大小写不敏感（RFC 里 unit 是 token）
        val eq = raw.indexOf('=')
        if (eq <= 0) return RangeResult.Full
        val unit = raw.substring(0, eq).trim()
        if (!unit.equals(ACCEPT_RANGES_VALUE, ignoreCase = true)) return RangeResult.Full

        val specText = raw.substring(eq + 1).trim()
        if (specText.isEmpty()) return RangeResult.Full

        // 多区间：降级成整份 200，不拼 multipart/byteranges
        val specs = specText.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (specs.size != 1) return RangeResult.Full

        val spec = specs[0]
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Unsatisfiable(totalSize)

        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        // `bytes=-` 两头都是空，不是一个区间
        if (startText.isEmpty() && endText.isEmpty()) return RangeResult.Unsatisfiable(totalSize)

        return if (startText.isEmpty()) {
            parseSuffixLength(endText, totalSize)
        } else {
            parseClosedRange(startText, endText, totalSize)
        }
    }

    /** `bytes=-500`：最后 500 字节。长度 ≤ 0 或超过文件长度时按文件长度截断。 */
    private fun parseSuffixLength(endText: String, totalSize: Long): RangeResult {
        val suffix = endText.toLongOrNull() ?: return RangeResult.Unsatisfiable(totalSize)
        if (suffix <= 0L) return RangeResult.Unsatisfiable(totalSize)
        val length = suffix.coerceAtMost(totalSize)
        return RangeResult.Partial(totalSize - length, totalSize - 1L)
    }

    /** `bytes=100-200` / `bytes=100-`：起点必填，终点省略表示"一直到末尾"。 */
    private fun parseClosedRange(startText: String, endText: String, totalSize: Long): RangeResult {
        val start = startText.toLongOrNull() ?: return RangeResult.Unsatisfiable(totalSize)
        if (start < 0L) return RangeResult.Unsatisfiable(totalSize)
        if (start >= totalSize) return RangeResult.Unsatisfiable(totalSize)

        if (endText.isEmpty()) return RangeResult.Partial(start, totalSize - 1L)

        val end = endText.toLongOrNull() ?: return RangeResult.Unsatisfiable(totalSize)
        if (end < start) return RangeResult.Unsatisfiable(totalSize)
        // 终点越界不算错：按 RFC 截到文件末尾
        return RangeResult.Partial(start, end.coerceAtMost(totalSize - 1L))
    }

    // ===== 响应头 =====

    /**
     * 按解析结果生成响应头。
     *
     * @param range     上一次 [parseRange] 的结果
     * @param totalSize 资源总字节数
     * @param mime      资源类型，如 `video/mp4`
     * @return 不含状态行的头字段，键用标准大小写（HTTP 头名大小写不敏感，这里只是便于阅读）
     *
     * 三态对应三套头：
     * - Partial：`Content-Range: bytes start-end/total` + 段长的 `Content-Length`
     * - Full：整份 `Content-Length`
     * - Unsatisfiable：`Content-Range: bytes * /total`，且 `Content-Length: 0`
     *
     * 所有情况都带 `Accept-Ranges: bytes`——这是把"服务端能续传"这件事告诉播放器的方式，
     * 少了它播放器拖动进度条时会退回整段重下。
     */
    fun headersFor(range: RangeResult, totalSize: Long, mime: String): Map<String, String> {
        val type = mime.ifBlank { DEFAULT_MIME }
        return when (range) {
            is RangeResult.Partial -> linkedMapOf(
                "Content-Type" to type,
                "Content-Range" to contentRange(range, totalSize),
                "Content-Length" to range.length.toString(),
                "Accept-Ranges" to ACCEPT_RANGES_VALUE
            )

            is RangeResult.Full -> linkedMapOf(
                "Content-Type" to type,
                "Content-Length" to totalSize.coerceAtLeast(0L).toString(),
                "Accept-Ranges" to ACCEPT_RANGES_VALUE
            )

            is RangeResult.Unsatisfiable -> linkedMapOf(
                "Content-Type" to type,
                "Content-Range" to "bytes */" + range.totalSize.coerceAtLeast(0L),
                "Content-Length" to "0",
                "Accept-Ranges" to ACCEPT_RANGES_VALUE
            )
        }
    }

    /** `bytes 100-200/1000`。 */
    fun contentRange(range: RangeResult.Partial, totalSize: Long): String =
        "bytes " + range.start + "-" + range.end + "/" + totalSize.coerceAtLeast(0L)

    /** 状态码：206 / 200 / 416，与 [headersFor] 一一对应。 */
    fun statusFor(range: RangeResult): Int = when (range) {
        is RangeResult.Partial -> STATUS_PARTIAL
        is RangeResult.Full -> STATUS_OK
        is RangeResult.Unsatisfiable -> STATUS_RANGE_NOT_SATISFIABLE
    }

    // ===== 流式 =====

    /**
     * 是否该整份返回。
     *
     * 只在 [RangeResult.Full] 上为 true；416 不算"整份返回"，它是拒绝。
     * 调用方据此决定走 `200 + 整个文件` 还是 `206 / 416` 分支。
     */
    fun shouldServeFull(range: RangeResult): Boolean = range is RangeResult.Full

    /**
     * 把 `file` 的 `[start, start+len)` 这一段按 [chunkSize] 分块写进 [sink]。
     *
     * **不整读内存**：用 [RandomAccessFile] 定位到 [start]，每次只 alloc 一块
     * [chunkSize]（默认 64 KB）的数组，读满一块就交给 [sink] 写走，然后复用同一个数组。
     * 因此无论文件是 20 MB 还是 20 GB，峰值堆占用都只有一块缓冲区，
     * 20 GB 片源也不会 OOM。
     *
     * @param file      本地文件（已存在、可读）
     * @param start     起始偏移，从 0 算
     * @param len       要发出的字节数；会被夹到文件尾部
     * @param chunkSize 单块大小；≤ 0 时退回 [DEFAULT_CHUNK_SIZE]
     * @param sink      每块的消费者，典型实现就是 `outputStream.write(buf, 0, n)`
     * @return 实际写出的字节数（文件比 [start]+[len] 短时会小于 [len]）
     * @throws IOException 文件打不开 / 读失败；调用方此时已经发出了 206 头，只能断连接
     *
     * [sink] 在读完最后一块后不会被 close——连接是否复用由调用方决定。
     */
    fun streamChunk(
        file: File,
        start: Long,
        len: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        sink: (ByteArray, Int) -> Unit
    ): Long {
        if (len <= 0L) return 0L
        val from = start.coerceAtLeast(0L)
        val block = if (chunkSize > 0) chunkSize else DEFAULT_CHUNK_SIZE

        RandomAccessFile(file, "r").use { raf ->
            val total = raf.length()
            if (from >= total) return 0L
            raf.seek(from)

            val want = len.coerceAtMost(total - from)
            val buffer = ByteArray(block.coerceAtMost(if (want < Int.MAX_VALUE) want.toInt() else block))
            var written = 0L

            while (written < want) {
                val remain = (want - written).coerceAtMost(buffer.size.toLong()).toInt()
                val read = raf.read(buffer, 0, remain)
                if (read <= 0) break
                sink(buffer, read)
                written += read.toLong()
            }
            return written
        }
    }

    /**
     * 一个完整响应该发多少字节、分几块。
     *
     * 只做算术，方便界面展示"206 会分多少块发"；415 等分支不用它。
     */
    fun chunkCount(length: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Long {
        if (length <= 0L) return 0L
        val block = if (chunkSize > 0) chunkSize else DEFAULT_CHUNK_SIZE
        return (length + block - 1L) / block.toLong()
    }

    /**
     * 把响应头拼成一段可直接贴进日志 / 文本区的多行文本（不含状态行）。
     *
     * 用于页面下方那块等宽文本区：用户看到的是真实会发出去的头，而不是一句描述。
     */
    fun headerText(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val builder = StringBuilder()
        for ((key, value) in headers) {
            builder.append(key).append(": ").append(value).append('\n')
        }
        // 末尾换行去掉，避免文本区多出一个空行
        if (builder.isNotEmpty()) builder.setLength(builder.length - 1)
        return builder.toString()
    }

    /** 状态码 + 短语，例：`206 Partial Content`。 */
    fun statusLine(range: RangeResult): String = when (range) {
        is RangeResult.Partial -> "206 Partial Content"
        is RangeResult.Full -> "200 OK"
        is RangeResult.Unsatisfiable -> "416 Range Not Satisfiable"
    }

    /** mime 为空时的兜底：大多数拖动场景就是视频，不认识的一律按二进制流。 */
    private const val DEFAULT_MIME: String = "application/octet-stream"
}
