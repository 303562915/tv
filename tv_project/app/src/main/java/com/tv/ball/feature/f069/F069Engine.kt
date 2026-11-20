package com.tv.ball.feature.f069

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条访问记录。
 *
 * 只有四个字段，是全功能唯一的数据形状：日志列表、导出文本、环形缓冲都吃它。
 * [path] 在入库前已经过 [F069Engine.sanitizePath]，令牌值不会出现在这里——
 * 日志的用途是"谁在什么时候拿了什么"，不是把凭证抄一份留下来。
 */
data class AccessLog(
    /** 对端地址，取 `remoteAddr`，不带端口。 */
    val ip: String,
    /** 请求路径（已脱敏，不含令牌明文）。 */
    val path: String,
    /** HTTP 状态码，200 正常、401 令牌不符、404/500 之类的服务端问题都靠它区分。 */
    val status: Int,
    /** 发生的时刻（毫秒时间戳），用于时间列与导出文本。 */
    val atMs: Long
)

/**
 * 状态码落在哪一档。
 *
 * 页面上要按档着色（2xx 绿、3xx 蓝、4xx 橙、5xx 红），但引擎不引用 R，
 * 所以这里只吐语义档位，具体颜色由 [F069Activity] 翻成 `@color/theme_*`。
 */
enum class StatusTone {
    /** 1xx / 2xx：请求被正常接住（含 206 分段、204 空响应）。 */
    OK,

    /** 3xx：客户端手里那份还是新的（304），或地址被动过（302）。 */
    REDIRECT,

    /** 4xx：客户端的问题——没带令牌（401）、路径写错（404）、被拒（403）。 */
    CLIENT_ERROR,

    /** 5xx：我这边的服务出错了，跟对方带没带令牌无关。 */
    SERVER_ERROR
}

/**
 * 令牌守卫。
 *
 * 两件事：**发一枚够难猜的令牌**、**判断这次请求带的令牌对不对**。
 * 令牌是局域网服务的唯一门槛——端口开着，同一网段任何设备都能连，
 * 所以这里的随机源、长度、比较方式都不能"差不多就行"：
 *
 * - 随机源用 [SecureRandom] 而不是 `Random`：后者由时间种子推得出来，
 *   连上几次看到几个令牌就能反推下一个。
 * - 16 位 base32（RFC 4648 字母表 A-Z2-7）= 80 bit 熵，写进 URL 不会太长，
 *   也不含 `+` `/` `=` 这些在查询串里要转义的字符，粘到浏览器里能直接用。
 * - 比较走逐字符异或累加（[constantTimeEquals]），不提前 return：
 *   提前返回的时间差能把"前几位对"泄漏出去，撞库时这就是个可用的侧信道。
 *
 * 令牌本身与开关状态都落 SharedPreferences（[prefs]），
 * 进程重建、切后台回来、电视重启之后都还是同一枚——否则用户配好的播放地址会集体失效。
 */
class TokenGuard(private val prefs: SharedPreferences) {

    companion object {
        /** 令牌长度。16 × 5bit = 80bit。 */
        const val LENGTH: Int = 16

        /** 查询串里令牌的参数名。 */
        const val PARAM: String = "token"

        /** RFC 4648 base32 字母表。刻意不用 base64：避免 `+` `/` `=`。 */
        private const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private const val KEY_TOKEN: String = "token_value"
        private const val KEY_ENABLED: String = "token_enabled"

        /** 默认开启：局域网服务默认就该锁着，用户明确关掉才算放行。 */
        const val DEFAULT_ENABLED: Boolean = true

        private val RANDOM: SecureRandom = SecureRandom()

        /** 生成一枚新令牌（不改动已有状态）。 */
        fun createToken(): String {
            val builder = StringBuilder(LENGTH)
            repeat(LENGTH) { builder.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]) }
            return builder.toString()
        }

        /**
         * 定长比较，逐字符累加异或差。
         *
         * 长度不等直接 false 不会额外泄漏什么——长度是固定的 [LENGTH]，
         * 攻击者自己也知道；真正要藏的是"前几位猜对了"这种位置信息。
         */
        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (index in a.indices) {
                diff = diff or (a[index].code xor b[index].code)
            }
            return diff == 0
        }
        const val PREFS_NAME: String = "f069_access"

        /** 环形缓冲容量。200 条足够看出一次扫描，又不至于把存储写爆。 */
        const val CAPACITY: Int = 200

        private const val KEY_LOG: String = "log_lines"

        /** 落盘分隔符。用 `\t` 而不是 `|`：路径里可能出现 `|`，制表符不会。 */
        /* moved */ private const val __FIELD_SEP_UNUSED: String = "\t"
        private const val LINE_SEP: String = "\n"

        fun create(context: Context): F069Engine =
            F069Engine(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        /** `token=xxx` 直到下一个 `&` 或结尾；捕获组只留参数名，值替换成 `***`。 */
        private val TOKEN_VALUE_RE: Regex = Regex("(?i)(token=)[^&\\s]*")
    }

    /**
     * 当前令牌。
     *
     * 首次读取时如果 prefs 里没有（新装、清数据），当场生成并写回：
     * 页面一进来就有令牌可复制，不需要用户先按一次"重置"。
     */
    var token: String
        get() {
            val stored = prefs.getString(KEY_TOKEN, null)
            if (stored != null && stored.length == LENGTH) return stored
            val fresh = createToken()
            prefs.edit().putString(KEY_TOKEN, fresh).apply()
            return fresh
        }
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    /**
     * 令牌校验开关。
     *
     * 关着的时候服务对同网段完全敞开——这是"我家里就我一个"，不是"忘了配"。
     * 开着的时候没带令牌、带错令牌一律 401，但列表、播放页这类只读入口
     * 仍然可以用静态资源地址访问，见 [F069Engine.authorize] 的接线说明。
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * 这次请求带的令牌对不对。
     *
     * - 开关关着 → 一律放行（[enabled] 为 false 时校验没有意义）。
     * - 开着 → 查 [PARAM] 参数；缺失、空串、长度不符、逐字符不符都算不通过。
     *
     * 入参用 `Map<String, String>` 而不是裸 URL：调用方（HTTP 处理）手上本来就是
     * 解析好的查询参数，别让引擎再去分词一遍——两套分词规则迟早对不上，
     * 出现"浏览器打得开、播放器 401"这种最难查的错。大小写不敏感：
     * 电视遥控器输入、二维码扫出来的地址常被转成小写。
     */
    fun check(params: Map<String, String>): Boolean {
        if (!enabled) return true
        val given = params[PARAM]?.trim()?.uppercase(Locale.ROOT)
        if (given.isNullOrEmpty()) return false
        return constantTimeEquals(given, token)
    }

    /**
     * 换一枚新令牌并立即持久化。
     *
     * 旧令牌从此失效——这正是"怀疑被陌生人连过"时要的效果：
     * 之前发出去的播放地址全部作废，重新复制一次即可。
     */
    fun rotate(): String {
        val fresh = createToken()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /**
     * 拼一个带令牌的完整地址，给用户复制去用。
     *
     * 几处细节都是踩过的：
     * - base 末尾的 `/` 与 path 开头的 `/` 只保留一个，否则出现 `//media/...`，
     *   部分播放器的 URL 归一化会把它当成协议相对地址。
     * - path 不带前导 `/` 时补上，用户手填 `media/EP01.mp4` 也能拼对。
     * - 已有的查询串（`?v=2`）后面接 `&`，不能无脑 `?`，否则第二个问号之后全被当成参数值。
     * - 开关关着时不拼令牌：显示一个当下用不上的参数只会让人以为地址写错了。
     */
    fun urlWithToken(base: String, path: String): String {
        val root = base.trim().trimEnd('/')
        val tail = if (path.startsWith("/")) path else "/$path"
        val url = root + tail
        if (!enabled) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url$separator$PARAM=$token"
    }
}

/**
 * F069「访问日志与令牌鉴权」的能力层。
 *
 * 局域网 HTTP 服务上线后有两件事必须当场回答：**刚才谁在访问**、**陌生设备能不能连**。
 * 这一层就是这两件的实现——一个容量 [CAPACITY] 的环形缓冲记访问，
 * 一个 [TokenGuard] 守门，两者都落 SharedPreferences（[prefs]）：
 *
 * - 日志必须在进程重建后还在，否则"刚才是不是被人扫过"这个问题永远只能靠猜；
 *   但日志又不能无限长——电视的存储和渲染都经不起，于是钉死 200 条。
 * - 令牌与开关同理，见 [TokenGuard]。
 *
 * 引擎**不引用 R**、不碰 Activity：纯 Kotlin 逻辑（`java.*` 与 SharedPreferences 除外），
 * 文案与颜色由页面自己翻（见 [F069Activity]）。
 */
class F069Engine(private val prefs: SharedPreferences) {


    /** 令牌守卫，页面直接读它的 `token` / `enabled`。 */
    val guard: TokenGuard = TokenGuard(prefs)

    private val ring: ArrayDeque<AccessLog> = ArrayDeque(CAPACITY)

    private val clock: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp: SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    companion object {
        /** 日志落盘的 prefs 键。 */
        private const val KEY_LOG: String = "log_lines"
        /** 行分隔符。 */
        private const val LINE_SEP: String = "\n"
        /** 字段分隔符。 */
        private const val FIELD_SEP: String = "\t"
        /** 环形缓冲上限；页面拿它显示「200 条上限」。 */
        const val CAPACITY: Int = 200

        /** 路径里 `token=xxx` 的匹配；只抹值不抹名。 */
        private val TOKEN_VALUE_RE: Regex = Regex("(?i)(token=)[^&\\s]*")

        /** 页面入口：按 `f069_access` 这份 prefs 建一个引擎。 */
        fun create(context: android.content.Context): F069Engine =
            F069Engine(context.getSharedPreferences("f069_access", android.content.Context.MODE_PRIVATE))
    }

    init {
        restore()
    }

    // ===== 日志 =====

    /** 已记录的条数（≤ [CAPACITY]）。 */
    val size: Int
        get() = ring.size

    /**
     * 记一条访问。
     *
     * 环形缓冲的语义是"最近的 N 条"：满了就丢最旧的一条（[ArrayDeque.removeFirst]），
     * 而不是停止记录——一次端口扫描可能几秒内打几十条，此时"停下不记"
     * 恰好会把后面真正有价值的那几条丢掉。
     *
     * 路径入库前先过 [sanitizePath]：日志会被导出、贴进聊天窗口、发给朋友排查，
     * 令牌明文出现在那里等于把它又发了一遍。
     *
     * @param atMs 发生时刻，默认当前时间；补录时传历史时间戳。
     * @return 实际入库的记录（已脱敏），方便调用方直接展示。
     */
    fun record(
        ip: String,
        path: String,
        status: Int,
        atMs: Long = System.currentTimeMillis()
    ): AccessLog {
        val entry = AccessLog(
            ip = ip.trim().ifEmpty { "unknown" },
            path = sanitizePath(path),
            status = status,
            atMs = atMs
        )
        ring.addLast(entry)
        while (ring.size > CAPACITY) {
            ring.removeFirst()
        }
        persist()
        return entry
    }

    /**
     * 最近 [n] 条，**新的在前**。
     *
     * 列表从上往下读，最新的那行必须出现在最上面：查"刚才谁在扫"时，
     * 让人先滚到底部才看到现场是不合理的。n 超过实际条数时返回全部。
     */
    fun recent(n: Int = CAPACITY): List<AccessLog> =
        if (n <= 0) emptyList() else ring.reversed().take(n)

    /**
     * 导出成纯文本，一行一条，新的在前。
     *
     * 形如 `11-20 23:41:07  192.168.1.23  206  /media/EP01.mp4`。
     * 用 `MM-dd HH:mm:ss` 而不是只有时分秒：日志跨天之后，"23:41"属于哪天
     * 是个必须能回答的问题。空日志返回空串，页面据此显示"暂无访问"。
     */
    fun exportText(): String {
        if (ring.isEmpty()) return ""
        return ring.reversed().joinToString(LINE_SEP) { line(it) }
    }

    /** 清空日志（连持久化的那份一起清）。开关与令牌不受影响。 */
    fun clear() {
        ring.clear()
        persist()
    }

    /**
     * 一次完整的接入判断：校验令牌 → 记一条 → 返回是否放行。
     *
     * 校验与记录绑在一起是有意的——**被拒的请求也要记**。
     * 只在放行时记日志，正好把"陌生设备拿着错令牌反复试"这段最想看的内容滤掉了。
     *
     * @param params 解析好的查询参数（含 [TokenGuard.PARAM]）。
     */
    fun authorize(ip: String, path: String, params: Map<String, String>): Boolean {
        val allowed = guard.check(params)
        record(ip, path, if (allowed) 200 else 401)
        return allowed
    }

    // ===== 脱敏与格式化 =====

    /**
     * 把路径里的令牌值抹成 `***`。
     *
     * 只抹值不抹名：`?token=***` 仍然明确告诉读日志的人"这个请求带过令牌"，
     * 而 `?token=K3M...` 相当于把凭证抄在纸上发出去。大小写与位置都处理，
     * 因为参数可能出现在查询串中间（`?a=1&token=x&b=2`）。
     */
    fun sanitizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        return trimmed.replace(TOKEN_VALUE_RE, "$1***")
    }

    /** 状态码归档，见 [StatusTone]。 */
    fun toneOf(status: Int): StatusTone = when {
        status in 200..299 -> StatusTone.OK
        status in 100..199 -> StatusTone.OK
        status in 300..399 -> StatusTone.REDIRECT
        status in 400..499 -> StatusTone.CLIENT_ERROR
        else -> StatusTone.SERVER_ERROR
    }

    /** 时分秒，列表的时间列用。 */
    fun formatClock(atMs: Long): String = synchronized(clock) { clock.format(Date(atMs)) }

    /** 月日时分秒，导出文本用（跨天也能定位）。 */
    fun formatFull(atMs: Long): String = synchronized(stamp) { stamp.format(Date(atMs)) }

    // ===== 持久化 =====

    /**
     * 整份日志写回 prefs。
     *
     * 每次 record 都写一遍看起来浪费，但 200 条文本也就十几 KB，
     * `apply()` 是异步落盘，远没有到影响列表滑动的量级；
     * 反过来攒着批量写，就要处理"进程被杀时丢最后几条"这种没人愿意调的情况。
     */
    private fun persist() {
        val text = ring.joinToString(LINE_SEP) { entry ->
            listOf(
                entry.atMs.toString(),
                entry.status.toString(),
                entry.ip,
                entry.path
            ).joinToString(FIELD_SEP)
        }
        prefs.edit().putString(KEY_LOG, text).apply()
    }

    /**
     * 从 prefs 恢复日志。
     *
     * 逐行宽容解析：任何一行字段不足或数字解析失败就跳过整行，而不是让整个功能起不来。
     * 日志是排查材料，不是业务数据——为一条坏记录丢掉其余 199 条是明显的赔本。
     */
    private fun restore() {
        val text = prefs.getString(KEY_LOG, null)
        if (text.isNullOrEmpty()) return

        for (line in text.split(LINE_SEP)) {
            if (line.isEmpty()) continue
            val parts = line.split(FIELD_SEP)
            if (parts.size < 4) continue
            val atMs = parts[0].toLongOrNull() ?: continue
            val status = parts[1].toIntOrNull() ?: continue
            val ip = parts[2]
            // 路径里理论上不含 \t，但万一有，后面的部分原样接回去
            val path = parts.drop(3).joinToString(FIELD_SEP)
            ring.addLast(AccessLog(ip = ip, path = sanitizePath(path), status = status, atMs = atMs))
            if (ring.size >= CAPACITY) break
        }
    }

    private fun line(entry: AccessLog): String =
        "${formatFull(entry.atMs)}  ${entry.ip}  ${entry.status}  ${entry.path}"
}
