package com.tv.ball.feature.f067

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Locale

/**
 * 「内置媒体服务器」的实现。
 *
 * 一句话：把本机的一个目录挂成 HTTP 目录页，同一局域网里的手机 / 平板 / 电脑用浏览器点开就能播放。
 * 传输层用工程里已有的 NanoHTTPD 2.3.1（`org.nanohttpd:nanohttpd:2.3.1` 打进的是 `fi.iki.elonen` 包），
 * 不另引框架，也不碰 MediaStore / ContentProvider：这个页面服务的只是应用自己的外部目录，
 * 不申请任何存储权限就能读写。
 *
 * 三个要点：
 * 1. [MediaServer] 继承 NanoHTTPD，[MediaServer.serve] 负责目录页与文件下载；
 * 2. 端口在 NanoHTTPD 构造时就固定了，改不了，所以「端口被占就试下一个」只能换实例——
 *    [MediaServer] 既是 serve 的实现，也是这组候选端口的控制器（`startServer` / `stopServer`）；
 * 3. 视频要能拖进度条，所以文件响应必须支持 `Range`，否则播放器只能从头播、不能 seek。
 */

/** 端口下限：1024 以下要特权，Android 普通应用也拿不到。 */
private const val F067_MIN_PORT = 1024

/** 端口上限。 */
private const val F067_MAX_PORT = 65535

/** 默认端口：8080，和大多数调试习惯一致。 */
private const val F067_DEFAULT_PORT = 8080

/** 取不到局域网 IPv4 时的兜底地址，保证页面上永远有一条能点的 URL。 */
private const val F067_LOOPBACK = "127.0.0.1"

private const val F067_HTML_MIME = "text/html; charset=utf-8"

private const val F067_TEXT_MIME = "text/plain; charset=utf-8"

/** 目录页最多列这么多项：再多的目录在浏览器里也没法看，还会把响应撑大。 */
private const val F067_MAX_LISTING = 1000

/**
 * 把任意整数收进合法端口区间。
 *
 * 放在文件顶层而不是 object 里：主构造函数把端口交给 `NanoHTTPD(port)` 时就会求值，
 * 那时候引用 object 成员会绕一圈初始化顺序，顶层函数没这个顾虑。
 */
private fun f067ClampPort(raw: Int): Int = raw.coerceIn(F067_MIN_PORT, F067_MAX_PORT)

/** 扩展名 → MIME。先查这张表，查不到再问 NanoHTTPD 自带的那份。 */
private val F067_MIME_TYPES: Map<String, String> = mapOf(
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "mkv" to "video/x-matroska",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "avi" to "video/x-msvideo",
    "flv" to "video/x-flv",
    "ts" to "video/mp2t",
    "m3u8" to "application/vnd.apple.mpegurl",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "srt" to "text/plain; charset=utf-8",
    "ass" to "text/plain; charset=utf-8",
    "ssa" to "text/plain; charset=utf-8",
    "vtt" to "text/vtt; charset=utf-8",
    "txt" to "text/plain; charset=utf-8",
    "json" to "application/json; charset=utf-8",
    "html" to "text/html; charset=utf-8"
)

/**
 * 目录页的样式：故意只用系统字体和 `currentColor` / `opacity`，一个十六进制色值都不写。
 *
 * 这样电视上的深色模式、手机上的浅色模式都能直接读，也不用跟 App 的 theme_* token 对齐。
 */
private const val F067_PAGE_STYLE = "*{box-sizing:border-box}" +
    "body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;margin:0;padding:24px;line-height:1.5}" +
    "h1{font-size:20px;margin:0 0 6px}" +
    "p.path{margin:0 0 18px;font-size:13px;opacity:.65;word-break:break-all}" +
    "table{width:100%;border-collapse:collapse;font-size:14px}" +
    "th,td{text-align:left;padding:9px 6px;border-bottom:1px solid rgba(0,0,0,.12)}" +
    "th{font-size:12px;font-weight:600;opacity:.55;text-transform:uppercase}" +
    "td.size{width:96px;text-align:right;white-space:nowrap;opacity:.7}" +
    "td a{text-decoration:none}" +
    "td a:hover{text-decoration:underline}" +
    "tr.dir td a{font-weight:600}" +
    "td.empty{opacity:.6}" +
    "p.foot{margin:24px 0 0;font-size:12px;opacity:.45}"

/**
 * 一次启动尝试的结果。
 *
 * [error] 为空表示成功，这时 [address] 是给用户点开的那条地址、[actualPort] 是真正绑上的端口；
 * 端口被占时 [actualPort] 会大于请求的端口（自动顺延）。失败时 [address] 为空、[actualPort] 为 0。
 */
data class StartResult(
    val address: String,
    val actualPort: Int,
    val error: String
) {
    /** 起没起来。界面据此决定显示地址还是显示原因。 */
    val ok: Boolean get() = error.isEmpty() && address.isNotEmpty()
}

/**
 * 只放行 `[start, start + count)` 一段字节的输入流。
 *
 * 用于 `Range` 请求：`FileInputStream.skip` 到位之后，外面套这一层就能把「总长度」交给
 * NanoHTTPD 去写 `Content-Length`，不用先把整段切片读进内存（4K 视频切片动辄几百 MB）。
 */
private class F067SliceStream(
    private val inner: InputStream,
    private var remaining: Long
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = inner.read()
        if (value >= 0) remaining -= 1
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val want = if (len.toLong() > remaining) remaining.toInt() else len
        val read = inner.read(b, off, want)
        if (read > 0) remaining -= read
        return read
    }

    override fun available(): Int {
        val buffered = inner.available().toLong()
        val capped = if (buffered > remaining) remaining else buffered
        return capped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        inner.close()
    }
}

/**
 * 内置媒体服务器本体。
 *
 * 用法：`val server = F067Engine.createServer(port, rootDir) { line -> ... }`，然后
 * `startServer()` 拿 [StartResult]，退出时 `stopServer()`。一个实例管一组候选端口：
 * 从 [requestedPort] 开始最多试 [F067Engine.MAX_PORT_ATTEMPTS] 个，第一个绑上的就是它。
 *
 * 为什么不是「一个实例绑一个端口」：NanoHTTPD 的端口是构造参数、存成 final，`start()` 里
 * 直接拿它 bind，失败之后同一个对象重试还是同一个端口。所以真正监听的是按候选端口克隆出来的
 * 实例（也是 [MediaServer]，serve 同一份代码），本对象只持有它的引用。
 *
 * 线程模型：`serve()` 在 NanoHTTPD 的工作线程上被调用，[onLog] 也就在那条线程上回调，
 * 界面侧自己 post 回主线程；这里不碰任何 UI。
 */
class MediaServer(
    val requestedPort: Int,
    private val rootDir: File,
    private val onLog: (String) -> Unit
) : NanoHTTPD(f067ClampPort(requestedPort)) {

    /** 真正在监听的那个实例；为空就是没起来。 */
    private var active: MediaServer? = null

    /** 实际绑上的端口；没起来时是 -1。 */
    private var boundPort: Int = -1

    /** 最近一次失败的原因，界面直接拿去显示，不做二次加工。 */
    var lastError: String = ""
        private set

    /** 服务是否在监听。 */
    val isRunning: Boolean
        get() = active != null

    /** 实际端口；还没起来时回落到请求的端口，方便界面先显示「将会是 8080」。 */
    val port: Int
        get() = if (boundPort > 0) boundPort else f067ClampPort(requestedPort)

    /**
     * 启动服务：从请求端口开始往上试，端口被占就顺延，最多 [F067Engine.MAX_PORT_ATTEMPTS] 次。
     *
     * 已经在跑就直接返回当前状态，不会起第二个（否则用户连点两下「启动」会绑出两个端口，
     * 界面只记得最后一个，前一个就漏了）。
     */
    fun startServer(): StartResult {
        if (isRunning) return StartResult(accessAddress(), port, "")

        val basePort = f067ClampPort(requestedPort)
        var failure = ""
        for (offset in 0 until F067Engine.MAX_PORT_ATTEMPTS) {
            val candidatePort = basePort + offset
            if (candidatePort > F067_MAX_PORT) break

            val candidate = MediaServer(candidatePort, rootDir, onLog)
            val started = try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                true
            } catch (e: IOException) {
                failure = e.message ?: e.javaClass.simpleName
                false
            } catch (e: SecurityException) {
                // 少数机器上 1024 以下或已被系统保留的端口会在这里抛出来
                failure = e.message ?: e.javaClass.simpleName
                false
            }
            if (!started) {
                candidate.stop()
                continue
            }

            active = candidate
            boundPort = candidate.listeningPort
            lastError = ""
            val address = accessAddress()
            onLog("监听已就绪 " + address)
            return StartResult(address, boundPort, "")
        }

        boundPort = -1
        lastError = failure.ifEmpty { "没有可用端口" }
        return StartResult("", 0, lastError)
    }

    /** 停掉监听；没在跑就是空操作。 */
    fun stopServer() {
        val current = active ?: return
        active = null
        boundPort = -1
        try {
            current.stop()
        } catch (e: RuntimeException) {
            lastError = e.message.orEmpty()
        }
        onLog("服务已停止监听")
    }

    /** 本机的局域网 IPv4；取不到就是空串，界面据此换提示语。 */
    fun localAddress(): String = F067Engine.localAddress()

    /** 给用户点开的那条地址。没有局域网地址时回落到回环，至少本机浏览器能验。 */
    fun accessAddress(): String {
        val host = F067Engine.localAddress().ifEmpty { F067_LOOPBACK }
        return "http://" + host + ":" + port + "/"
    }

    /**
     * 每个请求的入口。
     *
     * 路由很简单：`/` 或某个目录 → 目录页；其余按路径取文件。方法只认 GET / HEAD。
     *
     * 路径安全分两层：先按段拒绝 `..`，再对 canonicalFile 做一次前缀比对——第二层挡的是
     * 目录里的软链接指向外面（媒体目录里放个指向 `/data` 的软链接不该把整机暴露出去）。
     */
    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri ?: "/"
        if (method != Method.GET && method != Method.HEAD) {
            return reply(session, Status.METHOD_NOT_ALLOWED, F067_TEXT_MIME, "只提供浏览与下载：GET / HEAD")
        }

        val relative = F067Engine.safeRelativePath(uri)
            ?: return reply(session, Status.FORBIDDEN, F067_TEXT_MIME, "路径不合法，已拒绝")

        val root = rootDir
        if (!root.isDirectory) {
            return reply(session, Status.INTERNAL_ERROR, F067_TEXT_MIME, "媒体目录不可用：" + root.absolutePath)
        }

        val target = if (relative.isEmpty()) root else File(root, relative)
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull()
        if (canonicalTarget == null || !isInside(canonicalRoot, canonicalTarget)) {
            return reply(session, Status.FORBIDDEN, F067_TEXT_MIME, "路径不在媒体目录内，已拒绝")
        }
        if (!canonicalTarget.exists()) {
            return reply(session, Status.NOT_FOUND, F067_TEXT_MIME, "找不到：" + relative)
        }
        if (canonicalTarget.isDirectory) {
            return reply(session, Status.OK, F067_HTML_MIME, listingPage(relative, canonicalTarget))
        }
        return serveFile(session, relative, canonicalTarget)
    }

    /** 单个文件的响应：没有 Range 就整段 200，有 Range 就 200 换成 206 + Content-Range。 */
    private fun serveFile(session: IHTTPSession, relative: String, file: File): Response {
        if (!file.canRead()) {
            return reply(session, Status.FORBIDDEN, F067_TEXT_MIME, "文件不可读：" + relative)
        }
        val total = file.length()
        val mime = F067Engine.mimeTypeFor(file.name)
        val range = F067Engine.parseRange(session.headers?.get("range"), total)
        return try {
            if (range == null) {
                logRequest(session, 200)
                NanoHTTPD.newFixedLengthResponse(Status.OK, mime, FileInputStream(file), total).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            } else {
                val first = range.first
                val last = range.last
                val count = last - first + 1L
                val stream = FileInputStream(file)
                F067Engine.skipFully(stream, first)
                logRequest(session, 206)
                NanoHTTPD.newFixedLengthResponse(
                    Status.PARTIAL_CONTENT,
                    mime,
                    F067SliceStream(stream, count),
                    count
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes " + first + "-" + last + "/" + total)
                }
            }
        } catch (e: IOException) {
            reply(session, Status.INTERNAL_ERROR, F067_TEXT_MIME, "读取失败：" + (e.message ?: file.name))
        }
    }

    /** 统一出口：先把「方法 + 路径 + 状态码」记一行日志，再拼响应，避免两处手写码值对不上。 */
    private fun reply(session: IHTTPSession, status: Status, mime: String, body: String): Response {
        logRequest(session, status.requestStatus)
        return NanoHTTPD.newFixedLengthResponse(status, mime, body)
    }

    /** 记一行请求日志，交给上层（Activity）落进列表。 */
    private fun logRequest(session: IHTTPSession, code: Int) {
        val method = session.method?.name ?: "GET"
        val uri = session.uri ?: "/"
        onLog(method + " " + uri + " → " + code)
    }

    /** 目标是否落在根目录之内（含根目录自身）。入参都必须是 canonicalFile。 */
    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar)
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    /** 目录页：目录在前、文件在后，各自按名字排；目录显示 `—`，文件显示大小。 */
    private fun listingPage(relative: String, directory: File): String {
        val folders = ArrayList<File>()
        val files = ArrayList<File>()
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) folders.add(child) else files.add(child)
        }
        val byName = Comparator<File> { left, right ->
            val byLower = left.name.lowercase(Locale.US).compareTo(right.name.lowercase(Locale.US))
            if (byLower != 0) byLower else left.name.compareTo(right.name)
        }
        folders.sortWith(byName)
        files.sortWith(byName)

        val ordered = folders + files
        val shown = if (ordered.size > F067_MAX_LISTING) ordered.subList(0, F067_MAX_LISTING) else ordered

        val rows = StringBuilder()
        if (relative.isNotEmpty()) {
            rows.append(rowHtml(parentHref(relative), "返回上一级", true, "—"))
        }
        for (entry in shown) {
            val isDir = entry.isDirectory
            val label = if (isDir) entry.name + "/" else entry.name
            val size = if (isDir) "—" else F067Engine.formatSize(entry.length())
            rows.append(rowHtml(hrefFor(relative, entry), label, isDir, size))
        }
        if (shown.isEmpty() && relative.isEmpty()) {
            rows.append("<tr><td colspan=\"2\" class=\"empty\">目录是空的，先把视频拷进来再刷新本页。</td></tr>\n")
        } else if (ordered.size > shown.size) {
            rows.append(
                "<tr><td colspan=\"2\" class=\"empty\">还有 " + (ordered.size - shown.size) +
                    " 项没有列出，建议按子目录分组。</td></tr>\n"
            )
        }

        val body = StringBuilder()
        body.append("<p class=\"path\">").append(F067Engine.escapeHtml("/" + relative)).append("</p>\n")
        body.append("<table>\n<thead><tr><th>名称</th><th>大小</th></tr></thead>\n<tbody>\n")
        body.append(rows)
        body.append("</tbody>\n</table>\n")
        val title = if (relative.isEmpty()) "媒体目录" else "媒体目录 / " + relative
        return page(title, body.toString())
    }

    /** 子目录的「返回上一级」链接：一层就回根，多层回上一层目录。 */
    private fun parentHref(relative: String): String {
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return "/"
        return "/" + segments.dropLast(1).joinToString("/") { F067Engine.encodeSegment(it) } + "/"
    }

    /**
     * 条目的链接。
     *
     * 一律写成绝对路径（以 `/` 开头）：目录页的 URL 可能不带结尾斜杠（`/videos`），
     * 相对链接会被浏览器解析成根目录下的同名路径，点进去就 404。
     */
    private fun hrefFor(relative: String, file: File): String {
        val parent = relative.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { F067Engine.encodeSegment(it) }
        val name = F067Engine.encodeSegment(file.name) + if (file.isDirectory) "/" else ""
        return if (parent.isEmpty()) "/" + name else "/" + parent + "/" + name
    }

    /** 一行条目。名字和大小都过一遍 HTML 转义——目录里出现 `<` 或 `&` 不该把页面结构打坏。 */
    private fun rowHtml(href: String, label: String, isDirectory: Boolean, size: String): String {
        val cssClass = if (isDirectory) "dir" else "file"
        return "<tr class=\"" + cssClass + "\"><td><a href=\"" + href + "\">" + F067Engine.escapeHtml(label) +
            "</a></td><td class=\"size\">" + F067Engine.escapeHtml(size) + "</td></tr>\n"
    }

    /** 拼整页 HTML。标题同样转义，子目录名里带尖括号也不会破版。 */
    private fun page(title: String, body: String): String {
        val builder = StringBuilder(body.length + 512)
        builder.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
        builder.append("<meta charset=\"utf-8\">\n")
        builder.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        builder.append("<title>").append(F067Engine.escapeHtml(title)).append("</title>\n")
        builder.append("<style>").append(F067_PAGE_STYLE).append("</style>\n")
        builder.append("</head>\n<body>\n<h1>").append(F067Engine.escapeHtml(title)).append("</h1>\n")
        builder.append(body)
        builder.append("<p class=\"foot\">内置媒体服务器 · 同一局域网内用浏览器打开本页即可点播</p>\n")
        builder.append("</body>\n</html>\n")
        return builder.toString()
    }
}

/**
 * 「内置媒体服务器」的常量与纯函数层。
 *
 * 和界面无关的部分都收在这里：端口规则、本机 IPv4 探测、路径清洗、Range 解析、MIME、大小格式化。
 * 这样 [MediaServer] 只管 HTTP，测试和界面都能单独复用这几条规则。
 */
object F067Engine {

    /** 默认端口。 */
    const val DEFAULT_PORT: Int = F067_DEFAULT_PORT

    /** 允许的最小端口。 */
    const val MIN_PORT: Int = F067_MIN_PORT

    /** 允许的最大端口。 */
    const val MAX_PORT: Int = F067_MAX_PORT

    /** 端口占用时最多试几个：请求端口 + 后面 4 个。 */
    const val MAX_PORT_ATTEMPTS: Int = 5

    /** 把任意整数收进合法端口区间。 */
    fun normalizePort(raw: Int): Int = f067ClampPort(raw)

    /** 输入框里的端口：非数字、越界、空串一律返回 null，由界面提示，不静默改成默认值。 */
    fun parsePort(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = text.toIntOrNull() ?: return null
        return if (value in MIN_PORT..MAX_PORT) value else null
    }

    /** 造一个可启停的服务器；[onLog] 在 NanoHTTPD 的工作线程上被回调。 */
    fun createServer(port: Int, rootDir: File, onLog: (String) -> Unit): MediaServer =
        MediaServer(port, rootDir, onLog)

    /**
     * 本机的局域网 IPv4 地址。
     *
     * 先看 `wlan` / `eth` / `ap` 这类真实网卡，再退回任意非回环、非链路本地地址；一个都没有
     * 就返回空串（比如电视只插了网线但没连上、或者开的是热点）。这里不返回 `0.0.0.0`：
     * 那是个绑定用的通配地址，粘到浏览器里什么也打不开。
     */
    fun localAddress(): String {
        val preferred = ArrayList<String>()
        val others = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase(Locale.US).orEmpty()
                val isWiredOrWifi = name.contains("wlan") || name.contains("wifi") ||
                    name.contains("eth") || name.contains("ap") || name.contains("wifi0")
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    if (isWiredOrWifi) preferred.add(host) else others.add(host)
                }
            }
        } catch (e: Exception) {
            return ""
        }
        return preferred.firstOrNull() ?: others.firstOrNull() ?: ""
    }

    /** 字节数 → 人看的大小。目录页里用，界面不用。 */
    fun formatSize(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes < 1024L) return bytes.toString() + " B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.size - 1) {
            value /= 1024.0
            index += 1
        }
        val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, value, units[index])
    }

    /** HTML 文本转义：`& < > " '` 五个字符，够目录页用，也够文件名里出现的各种怪符号。 */
    fun escapeHtml(raw: String): String {
        val builder = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** 单个路径段的 URL 编码；`+` 换成 `%20`，否则文件名里的空格在部分浏览器里会变成加号。 */
    fun encodeSegment(raw: String): String {
        val encoded = try {
            URLEncoder.encode(raw, "UTF-8")
        } catch (e: Exception) {
            return raw
        }
        return encoded.replace("+", "%20")
    }

    /**
     * URI → 根目录下的相对路径。
     *
     * 只做清洗，不碰磁盘：丢掉空段和 `.`，任何 `..` 直接判非法（返回 null），带 NUL 的也判非法。
     * 根路径 `/` 与 `""` 都归一成空串。
     */
    fun safeRelativePath(uri: String): String? {
        val path = uri.substringBefore('?').substringBefore('#')
        val segments = path.split('/', '\\')
        val kept = ArrayList<String>(segments.size)
        for (segment in segments) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") return null
            if (segment.indexOf('\u0000') >= 0) return null
            kept.add(segment)
        }
        return kept.joinToString("/")
    }

    /**
     * 解析 `Range: bytes=...`。
     *
     * 支持三种写法：`0-499`、`500-`、`-500`（末尾 500 字节）。只取第一段（多段 Range 基本没有
     * 播放器用，回了也只会让解析器更难写），越界 / 语法不通就返回 null，等于「忽略 Range，整段发」。
     */
    fun parseRange(header: String?, total: Long): LongRange? {
        if (header.isNullOrBlank() || total <= 0L) return null
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substring(6).substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val head = spec.substring(0, dash).trim()
        val tail = spec.substring(dash + 1).trim()

        if (head.isEmpty()) {
            val suffix = tail.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            val start = if (suffix >= total) 0L else total - suffix
            return start..(total - 1L)
        }

        val start = head.toLongOrNull() ?: return null
        if (start < 0L || start >= total) return null
        val end = if (tail.isEmpty()) total - 1L else (tail.toLongOrNull() ?: return null).coerceAtMost(total - 1L)
        if (end < start) return null
        return start..end
    }

    /** 跳过 [count] 个字节；`skip` 返回值可能小于请求值，所以循环到跳够为止。 */
    fun skipFully(stream: InputStream, count: Long) {
        var left = count
        while (left > 0L) {
            val skipped = stream.skip(left)
            if (skipped > 0L) {
                left -= skipped
            } else if (stream.read() < 0) {
                return
            } else {
                left -= 1L
            }
        }
    }

    /** 扩展名 → MIME；不认识的交给 NanoHTTPD 自带表，再不行就 `application/octet-stream`。 */
    fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        val known = F067_MIME_TYPES[extension]
        if (known != null) return known
        val fallback = runCatching { NanoHTTPD.getMimeTypeForFile(name) }.getOrNull()
        return if (fallback.isNullOrBlank()) "application/octet-stream" else fallback
    }
}
