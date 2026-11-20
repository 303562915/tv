package com.tv.ball.feature.f059

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 一条「按域名生效」的请求头规则。
 *
 * 要解决的问题：部分直链防盗链，请求头里没有对的 UA / Referer 就直接 403。
 * 这类站点往往一个 CDN 一个规矩，所以规则按域名（后缀）分组，同一次播放里
 * 不同地址可以各自带上各自的那一份头。
 *
 * @param domainPattern 域名或域名后缀，允许 `*.` 通配前缀，例如 `cdn.example.com` / `*.example.com` / `*`
 * @param userAgent     命中后要带的 UA，空串表示这条规则不管 UA
 * @param referer       命中后要带的 Referer，空串表示不管 Referer
 * @param enabled       是否参与匹配；停用的规则留在列表里但不会命中
 */
data class HeaderRule(
    val domainPattern: String,
    val userAgent: String = "",
    val referer: String = "",
    val enabled: Boolean = true
) {
    /** 归一化后的域名模式（小写、去掉协议 / 端口 / 路径）。非法时为空串。 */
    val pattern: String get() = normalize(domainPattern)

    val hasUserAgent: Boolean get() = userAgent.isNotBlank()
    val hasReferer: Boolean get() = referer.isNotBlank()

    /** 两个头都空 —— 这条规则命中也不会产生任何效果。 */
    val isEmpty: Boolean get() = !hasUserAgent && !hasReferer

    fun withEnabled(value: Boolean): HeaderRule = copy(enabled = value)

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_DOMAIN, domainPattern)
        put(KEY_UA, userAgent)
        put(KEY_REFERER, referer)
        put(KEY_ENABLED, enabled)
    }

    companion object {
        private const val KEY_DOMAIN = "domain"
        private const val KEY_UA = "userAgent"
        private const val KEY_REFERER = "referer"
        private const val KEY_ENABLED = "enabled"

        /** 合法主机名（含 `*.` 前缀以外的部分）：标签由字母数字连字符组成，点分段。 */
        private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

        /**
         * 归一化用户在输入框里写的域名模式。
         *
         * 允许直接粘整条地址（`https://cdn.example.com/live.m3u8?x=1`）：协议、端口、路径、查询串
         * 一律丢掉，只保留主机部分。`*.` 通配前缀保留，其余非法字符（空格、中文、下划线之外的东西）
         * 判定为空串，由调用方当作用户输错处理。
         *
         * @return 归一化后的模式；输入无法解析时返回空串
         */
        fun normalize(raw: String): String {
            var s = raw.trim().lowercase(Locale.ROOT)
            if (s.isEmpty()) return ""
            val schemeAt = s.indexOf("://")
            if (schemeAt >= 0) s = s.substring(schemeAt + 3)
            s = s.substringBefore('/')
            s = s.substringBefore('?')
            s = s.substringBefore('#')
            if (!s.startsWith("[")) s = s.substringBefore(':')
            s = s.trimEnd('.')
            if (s == "*") return "*"
            if (s.startsWith("*.")) {
                val tail = s.substring(2)
                return if (HOST_RE.matches(tail)) "*.$tail" else ""
            }
            return if (HOST_RE.matches(s)) s else ""
        }

        /**
         * 从播放地址里取主机名（小写、无端口）。
         *
         * @return 主机名；地址空或取不出主机时返回空串
         */
        fun hostOf(url: String): String {
            val raw = url.trim()
            if (raw.isEmpty()) return ""
            var s = raw.lowercase(Locale.ROOT)
            val schemeAt = s.indexOf("://")
            if (schemeAt >= 0) s = s.substring(schemeAt + 3)
            s = s.substringBefore('/')
            s = s.substringBefore('?')
            s = s.substringBefore('#')
            s = s.substringAfterLast('@')
            if (!s.startsWith("[")) s = s.substringBefore(':')
            return s.trimEnd('.')
        }

        /**
         * 主机是否落在模式上。
         *
         * 后缀匹配是刻意的：`example.com` 既要命中 `example.com`，也要命中 `a.b.example.com`；
         * `*.example.com` 则只命中子域，不命中裸域。`*` 匹配一切。
         */
        fun matches(host: String, pattern: String): Boolean {
            if (host.isEmpty() || pattern.isEmpty()) return false
            if (pattern == "*") return true
            if (pattern.startsWith("*.")) {
                val base = pattern.substring(2)
                return host == base || host.endsWith(".$base")
            }
            return host == pattern || host.endsWith(".$pattern")
        }

        fun fromJson(obj: JSONObject?): HeaderRule? {
            if (obj == null) return null
            val domain = obj.optString(KEY_DOMAIN, "")
            if (domain.isBlank()) return null
            return HeaderRule(
                domainPattern = domain,
                userAgent = obj.optString(KEY_UA, ""),
                referer = obj.optString(KEY_REFERER, ""),
                enabled = obj.optBoolean(KEY_ENABLED, true)
            )
        }
    }
}

/**
 * 一次「输入地址 → 命中规则 → 实际请求头」的预览结果。
 *
 * @param url     用户输入的原始地址
 * @param host    从地址里拆出来的主机名；拆不出来为空串
 * @param rule    命中的规则（停用的不算命中）
 * @param headers 命中后会真正发出去的请求头，键是头名，值是头值
 */
data class HeaderPreview(
    val url: String,
    val host: String,
    val rule: HeaderRule?,
    val headers: Map<String, String>
) {
    /** 地址能不能拆出主机。 */
    val valid: Boolean get() = host.isNotEmpty()

    /** 是否命中了某条规则。 */
    val matched: Boolean get() = rule != null

    /** 命中规则但两个头都空（规则没写完）。 */
    val emptyRule: Boolean get() = rule != null && headers.isEmpty()
}

/**
 * 请求头配置引擎：规则的匹配、请求头生成、增删改查与 JSON 落盘。
 *
 * 匹配规则按「最长模式优先」：同一次请求可能同时命中 `*.example.com` 与 `cdn.example.com`，
 * 更具体的那条说了算，这样通用兜底规则和站点专用规则可以共存，不用手动排序。
 * 长度相同的两条，列表里靠前的赢（手写的顺序就是优先级）。
 *
 * 文件落在 `filesDir/f059_headers.json`，形如：
 * `{"version":1,"rules":[{"domain":"cdn.example.com","userAgent":"…","referer":"…","enabled":true}]}`
 * 写入走「临时文件 + 覆盖」，避免写一半掉电留下半截 JSON。
 *
 * 全部方法都是轻量同步调用（只有 load / save 碰磁盘），调用方在 IO 线程跑即可。
 */
class F059Engine(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    /** 读盘。文件不存在、坏掉、字段缺失都当「没有规则」，不让配置读失败挡住页面打开。 */
    @Synchronized
    fun load(): List<HeaderRule> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray(KEY_RULES) ?: return emptyList()
        val out = ArrayList<HeaderRule>(array.length())
        for (i in 0 until array.length()) {
            HeaderRule.fromJson(array.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    /** 落盘。返回是否写成功（失败只影响下次启动，不影响当前会话）。 */
    @Synchronized
    fun save(rules: List<HeaderRule>): Boolean {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        val root = JSONObject().apply {
            put(KEY_VERSION, VERSION)
            put(KEY_RULES, array)
        }
        return writeAtomically(root.toString(2))
    }

    /**
     * 找出 [url] 命中的规则。
     *
     * @return 最长匹配的那条；没有命中返回 null
     */
    fun match(url: String, rules: List<HeaderRule>): HeaderRule? {
        val host = HeaderRule.hostOf(url)
        if (host.isEmpty()) return null
        var best: HeaderRule? = null
        var bestLength = -1
        rules.forEach { rule ->
            if (!rule.enabled) return@forEach
            val pattern = rule.pattern
            if (pattern.isEmpty()) return@forEach
            if (!HeaderRule.matches(host, pattern)) return@forEach
            if (pattern.length > bestLength) {
                best = rule
                bestLength = pattern.length
            }
        }
        return best
    }

    /**
     * 命中规则该带的请求头。没有命中、或规则里两个头都空，返回空 Map。
     *
     * 顺序固定：先 User-Agent 再 Referer，界面上直接按这个顺序展示。
     */
    fun headers(rule: HeaderRule?): Map<String, String> {
        if (rule == null) return emptyMap()
        val out = LinkedHashMap<String, String>(2)
        rule.userAgent.trim().takeIf { it.isNotEmpty() }?.let { out[HEADER_UA] = it }
        rule.referer.trim().takeIf { it.isNotEmpty() }?.let { out[HEADER_REFERER] = it }
        return out
    }

    /** 一次性算好预览：主机、命中规则、实际请求头。 */
    fun preview(url: String, rules: List<HeaderRule>): HeaderPreview {
        val host = HeaderRule.hostOf(url)
        val rule = if (host.isEmpty()) null else match(url, rules)
        return HeaderPreview(url = url, host = host, rule = rule, headers = headers(rule))
    }

    /** 新增：同域名的旧规则被顶掉（域名即身份）。 */
    fun add(rule: HeaderRule, rules: List<HeaderRule>): List<HeaderRule> {
        val pattern = rule.pattern
        val out = rules.filterNot { it.pattern == pattern }.toMutableList()
        out.add(rule)
        return out
    }

    /**
     * 修改：域名可能被一起改掉，所以旧域名要单独传。
     *
     * @param previousPattern 改之前那条规则的归一化模式；改域名时用它定位
     */
    fun update(previousPattern: String, rule: HeaderRule, rules: List<HeaderRule>): List<HeaderRule> {
        val keep = HeaderRule.normalize(previousPattern)
        val pattern = rule.pattern
        val out = rules.filterNot { it.pattern == keep || it.pattern == pattern }.toMutableList()
        out.add(rule)
        return out
    }

    /** 删除。 */
    fun remove(pattern: String, rules: List<HeaderRule>): List<HeaderRule> {
        val target = HeaderRule.normalize(pattern)
        return rules.filterNot { it.pattern == target }
    }

    /** 只翻转启用开关，别的字段原样保留。 */
    fun setEnabled(pattern: String, enabled: Boolean, rules: List<HeaderRule>): List<HeaderRule> {
        val target = HeaderRule.normalize(pattern)
        return rules.map { if (it.pattern == target) it.withEnabled(enabled) else it }
    }

    /** 按模式找规则。 */
    fun find(pattern: String, rules: List<HeaderRule>): HeaderRule? {
        val target = HeaderRule.normalize(pattern)
        return rules.firstOrNull { it.pattern == target }
    }

    /** 临时文件 -> 覆盖正式文件；失败回落到直接写。 */
    private fun writeAtomically(text: String): Boolean {
        val dir = file.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false
        val tmp = File(dir, file.name + ".tmp")
        return try {
            tmp.writeText(text)
            val moved = if (file.exists()) file.delete() && tmp.renameTo(file) else tmp.renameTo(file)
            if (!moved) {
                file.writeText(text)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            runCatching { file.writeText(text) }.isSuccess
        }
    }

    companion object {
        /** 规则文件名。 */
        const val FILE_NAME = "f059_headers.json"

        /** 请求头名：UA。 */
        const val HEADER_UA = "User-Agent"

        /** 请求头名：Referer。 */
        const val HEADER_REFERER = "Referer"

        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_RULES = "rules"
    }
}