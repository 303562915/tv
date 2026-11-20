package com.tv.ball.feature.f023

import android.content.Context

/**
 * F023「忽略目录与扩展名规则」· 规则集。
 *
 * 两条判定互相独立，命中任意一条即视为该路径应当跳过：
 *
 * - [dirPrefixes]：目录前缀，按路径**分段连续匹配**，大小写不敏感。
 *   写 `Android/data` 就能命中 `/storage/emulated/0/Android/data/com.foo/cache`，
 *   不必写全绝对路径；写 `.thumbnails` 命中任意层级下的同名目录；
 *   写 `DCIM/.thumbnails` 只命中这两段相邻出现的位置。
 * - [extBlacklist]：扩展名黑名单，只看最后一段文件名，大小写不敏感；
 *   输入 `.mp4` 与 `mp4` 等价，内部统一成带点的形式。
 *
 * 判定是纯函数：不碰 IO、不碰 View，单测直接构造实例即可。
 * 持久化见 [F023Engine.load] / [F023Engine.save]。
 */
data class RuleSet(
    val dirPrefixes: List<String> = DEFAULT_DIR_PREFIXES,
    val extBlacklist: Set<String> = DEFAULT_EXT_BLACKLIST
) {

    /** 目录前缀的比对形态：显示用原文 + 小写分段，按需算一次。 */
    private val dirLookup: List<Pair<String, List<String>>> by lazy {
        dirPrefixes.mapNotNull { raw ->
            val normalized = normalizeDir(raw) ?: return@mapNotNull null
            raw.trim() to segmentsOf(normalized)
        }
    }

    /** 扩展名黑名单的比对形态（全部小写带点）。 */
    private val extLookup: Set<String> by lazy {
        extBlacklist.mapNotNull { normalizeExt(it) }.toSet()
    }

    /** 这个路径是否应被忽略。 */
    fun shouldSkip(path: String): Boolean = explain(path).skipped

    /**
     * 完整判定：是否忽略 + 命中的是哪条规则。
     *
     * 目录规则优先于扩展名规则：目录前缀一旦命中，整棵子树都不再往下看，
     * 所以两者同时命中时只报目录规则。
     */
    fun explain(path: String): SkipDecision {
        val segments = segmentsOf(path)
        val dirHit = matchDir(segments)
        val extHit = matchExtIn(segments.lastOrNull())
        return SkipDecision(
            skipped = dirHit != null || extHit != null,
            dirRule = dirHit,
            extRule = extHit
        )
    }

    /** 命中的目录前缀（原文形态）；没命中返回 null。 */
    fun matchDir(path: String): String? = matchDir(segmentsOf(path))

    /** 命中的扩展名（小写带点）：没命中返回 null。 */
    fun matchExt(path: String): String? {
        val segments = segmentsOf(path)
        return matchExtIn(segments.lastOrNull())
    }

    /** 追加一条目录前缀；已存在（忽略大小写与首尾斜杠）时原样返回。 */
    fun withDir(raw: String): RuleSet {
        val normalized = normalizeDir(raw) ?: return this
        val exists = dirPrefixes.any { normalizeDir(it)?.equals(normalized, ignoreCase = true) == true }
        return if (exists) this else copy(dirPrefixes = dirPrefixes + normalized)
    }

    /** 删除一条目录前缀；不存在时原样返回。 */
    fun withoutDir(raw: String): RuleSet {
        val normalized = normalizeDir(raw) ?: return this
        val kept = dirPrefixes.filterNot { normalizeDir(it)?.equals(normalized, ignoreCase = true) == true }
        return if (kept.size == dirPrefixes.size) this else copy(dirPrefixes = kept)
    }

    /** 追加一条扩展名；已存在时原样返回。 */
    fun withExt(raw: String): RuleSet {
        val normalized = normalizeExt(raw) ?: return this
        val exists = extBlacklist.any { normalizeExt(it) == normalized }
        return if (exists) this else copy(extBlacklist = extBlacklist + normalized)
    }

    /** 删除一条扩展名；不存在时原样返回。 */
    fun withoutExt(raw: String): RuleSet {
        val normalized = normalizeExt(raw) ?: return this
        val kept = extBlacklist.filterNot { normalizeExt(it) == normalized }
        return if (kept.size == extBlacklist.size) this else copy(extBlacklist = kept.toSet())
    }

    /** 目录前缀列表恢复默认，扩展名黑名单保持用户当前设置。 */
    fun withDefaultDirs(): RuleSet = copy(dirPrefixes = DEFAULT_DIR_PREFIXES)

    /** 整体恢复默认。 */
    fun withDefaults(): RuleSet = RuleSet()

    // region 内部匹配

    /** 目录前缀命中判定：前缀分段在路径分段里连续出现即命中。 */
    private fun matchDir(segments: List<String>): String? {
        for ((raw, needle) in dirLookup) {
            if (containsRun(segments, needle)) return raw
        }
        return null
    }

    private fun matchExtIn(lastSegment: String?): String? {
        if (lastSegment == null) return null
        val dot = lastSegment.lastIndexOf('.')
        if (dot < 0) return null
        val ext = lastSegment.substring(dot).lowercase()
        if (!extLookup.contains(ext)) return null
        // 返回用户在列表里看到的形态（可能是 `mp4` 这种不带点的写法）。
        return extBlacklist.firstOrNull { normalizeExt(it) == ext } ?: ext
    }

    private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        val lastStart = haystack.size - needle.size
        for (start in 0..lastStart) {
            var matched = true
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    // endregion

    companion object {

        /**
         * 默认预置目录前缀。
         *
         * `Android/data` 是系统与第三方 App 的私有数据目录，里面不会有用户视频；
         * `.thumbnails` / `DCIM/.thumbnails` 是相册缩略图缓存，扫进去只会产生大量碎图。
         */
        val DEFAULT_DIR_PREFIXES: List<String> = listOf(
            "Android/data",
            ".thumbnails",
            "DCIM/.thumbnails"
        )

        /**
         * 扩展名黑名单默认为空：需求只预置了目录，扩展名由用户按自己的片源习惯加。
         */
        val DEFAULT_EXT_BLACKLIST: Set<String> = emptySet()

        /** 路径切成小写分段，同时吃掉 Windows 反斜杠与重复斜杠。 */
        internal fun segmentsOf(path: String): List<String> =
            path.replace('\\', '/')
                .split('/')
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }

        /**
         * 目录前缀规范化：去首尾斜杠、合并重复斜杠、反斜杠转正斜杠。
         *
         * 保留用户输入的大小写（显示用），比对时统一小写。
         * 全是斜杠 / 空串返回 null，调用方直接忽略这次输入。
         */
        fun normalizeDir(raw: String): String? {
            val cleaned = raw.trim()
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/")
            return cleaned.ifEmpty { null }
        }

        /** 扩展名规范化：小写、去通配符 `*`、补前导点。 */
        fun normalizeExt(raw: String): String? {
            var text = raw.trim().lowercase().removePrefix("*").trim()
            if (text.isEmpty()) return null
            if (!text.startsWith(".")) text = ".$text"
            return if (text == ".") null else text
        }
    }
}

/** 一次判定的结果：忽略了没有、哪条规则命中的。 */
data class SkipDecision(
    val skipped: Boolean,
    val dirRule: String?,
    val extRule: String?
) {

    /** 命中规则的显示文本，目录规则优先。 */
    val rule: String? get() = dirRule ?: extRule
}

/**
 * F023 规则集的读写 + 增删入口。
 *
 * 逻辑本身在 [RuleSet] 里，这里只做两件事：
 * 1. 给 Activity / 扫描器一个不带状态的调用壳；
 * 2. 用 SharedPreferences（文件名 `f023_rules`）做分隔符存储。
 *
 * 存储格式：一个键一串值，值之间用换行分隔。选分隔符而不是 JSON，
 * 是因为目录里本来就允许各种字符，换行既不会出现在路径里，读回来也不用处理转义。
 */
object F023Engine {

    /** 需求约定的 SharedPreferences 文件名。 */
    const val PREFS_NAME = "f023_rules"

    private const val KEY_DIRS = "dir_prefixes"
    private const val KEY_EXTS = "ext_blacklist"
    private const val SEPARATOR = "\n"

    /** 默认规则：预置的目录前缀 + 空的扩展名黑名单。 */
    fun defaultRules(): RuleSet = RuleSet()

    fun addDir(rules: RuleSet, raw: String): RuleSet = rules.withDir(raw)

    fun removeDir(rules: RuleSet, raw: String): RuleSet = rules.withoutDir(raw)

    fun addExt(rules: RuleSet, raw: String): RuleSet = rules.withExt(raw)

    fun removeExt(rules: RuleSet, raw: String): RuleSet = rules.withoutExt(raw)

    /** 判定入口。 */
    fun shouldSkip(rules: RuleSet, path: String): Boolean = rules.shouldSkip(path)

    /** 判定入口（带命中规则）。 */
    fun explain(rules: RuleSet, path: String): SkipDecision = rules.explain(path)

    /** 是否已经存过规则：没存过时首次进入应落到默认预置。 */
    fun hasSavedRules(context: Context): Boolean {
        val p = prefs(context)
        return p.contains(KEY_DIRS) || p.contains(KEY_EXTS)
    }

    /** 读规则；没存过就用默认预置。 */
    fun load(context: Context): RuleSet {
        val p = prefs(context)
        if (!p.contains(KEY_DIRS) && !p.contains(KEY_EXTS)) return RuleSet()
        val dirs = when {
            !p.contains(KEY_DIRS) -> RuleSet.DEFAULT_DIR_PREFIXES
            else -> decode(p.getString(KEY_DIRS, null))
        }
        val exts = when {
            !p.contains(KEY_EXTS) -> RuleSet.DEFAULT_EXT_BLACKLIST
            else -> decode(p.getString(KEY_EXTS, null)).toSet()
        }
        return RuleSet(dirs, exts)
    }

    /** 写规则；apply() 异步落盘，主线程不做磁盘 IO。 */
    fun save(context: Context, rules: RuleSet) {
        prefs(context).edit()
            .putString(KEY_DIRS, encode(rules.dirPrefixes))
            .putString(KEY_EXTS, encode(rules.extBlacklist))
            .apply()
    }

    /** 恢复默认并落盘，返回落盘后的规则，调用方直接拿去渲染。 */
    fun reset(context: Context): RuleSet {
        val rules = RuleSet()
        save(context, rules)
        return rules
    }

    /** 序列化：换行分隔。 */
    internal fun encode(values: Collection<String>): String = values.joinToString(SEPARATOR)

    /** 反序列化：空串 / 缺省都当空列表，不抛异常。 */
    internal fun decode(raw: String?): List<String> =
        if (raw.isNullOrEmpty()) emptyList() else raw.split(SEPARATOR).filter { it.isNotEmpty() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}