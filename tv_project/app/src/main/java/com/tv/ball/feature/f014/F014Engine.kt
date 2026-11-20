package com.tv.ball.feature.f014

/**
 * 一条字幕匹配结果。
 *
 * @param subtitlePath 命中的字幕文件，原样带回调用方传进来的那个字符串（可能只是文件名，也可能是相对路径）
 * @param language 从文件名后缀推断出的语言 tag（zh-Hans / zh-Hant / en …）；文件名里没有语言标记时是空串
 * @param confidence 置信度 0~100：完全同名 100，带语言后缀 -2，带拷贝序号 -6，落在字幕子目录 -10
 */
data class MatchResult(
    val subtitlePath: String,
    val language: String,
    val confidence: Int
)

/**
 * 字幕语言标记与排序位。
 *
 * 排序位越小越优先：完全同名（未标注且没有拷贝序号）→ zh-Hans → zh-Hant → en → 其它。
 * 「完全同名」单独拎到最前，是因为 movie.srt 和 movie.mp4 文件名一模一样，是最强命中，
 * 不该被 movie.en.srt 顶掉；带语言标记的候选之间严格按 zh-Hans > zh-Hant > en > 其它 排，
 * 而「未标注但带 (1) 序号」这种重复下载的拷贝归到「其它」桶 —— 它对得上的只是同名前缀，不是同一个文件。
 */
enum class F014Language(val tag: String, val label: String, val rank: Int) {
    /** 文件名里没有任何语言标记。 */
    UNTAGGED("", "未标注", 0),
    ZH_HANS("zh-Hans", "简体中文", 1),
    ZH_HANT("zh-Hant", "繁体中文", 2),
    EN("en", "英语", 3),
    JA("ja", "日语", 4),
    KO("ko", "韩语", 5),
    FR("fr", "法语", 6),
    DE("de", "德语", 7),
    ES("es", "西班牙语", 8),
    RU("ru", "俄语", 9);

    companion object {
        /** 认得出是语言码、但没有单独列进枚举的语言，统一落到这个排序位。 */
        const val OTHER_RANK = 10

        /**
         * 文件名后缀别名 → 规范 tag（键一律小写）。
         *
         * 只用来吃掉「文件名尾部」的标记，所以 it / no / is 这类本身也是片名单词的
         * 两字母码放在这里不会误伤片名中间的部分。
         */
        private val ALIASES: Map<String, String> = buildMap {
            fun aliases(tag: String, vararg names: String) {
                for (name in names) put(name, tag)
            }
            aliases(
                "zh-Hans", "zh-hans", "zh-hans-cn", "zh-cn", "zhcn", "zhs", "chs", "hans",
                "sc", "cn", "简体", "简中", "简体中文"
            )
            aliases(
                "zh-Hant", "zh-hant", "zh-hant-tw", "zh-tw", "zh-hk", "zhhk", "zht", "cht",
                "hant", "tc", "tw", "hk", "繁体", "繁中", "繁体中文"
            )
            aliases("en", "en", "eng", "english", "en-us", "en-gb", "英语", "英文")
            aliases("ja", "ja", "jp", "jpn", "japanese", "日语", "日文")
            aliases("ko", "ko", "kor", "korean", "韩语", "韩文")
            aliases("fr", "fr", "fra", "fre", "french", "法语")
            aliases("de", "de", "deu", "ger", "german", "德语")
            aliases("es", "es", "spa", "spanish", "西班牙语")
            aliases("ru", "ru", "rus", "russian", "俄语")
        }

        /** 其它常见字幕语言码：认得出、会被剥掉，但排在 en 之后（「其它」桶）。 */
        private val OTHER_CODES: Set<String> = setOf(
            "pt", "por", "it", "ita", "nl", "nld", "dut", "pl", "pol",
            "tr", "tur", "ar", "ara", "he", "heb", "hi", "hin",
            "th", "tha", "vi", "vie", "id", "ind", "ms", "may",
            "sv", "swe", "no", "nor", "da", "dan", "fi", "fin",
            "cs", "ces", "el", "ell", "hu", "hun", "ro", "ron",
            "uk", "ukr", "ca", "cat", "hr", "hrv", "sk", "slk",
            "sl", "slv", "bg", "bul", "sr", "srp", "lt", "lit",
            "lv", "lav", "et", "est", "fa", "per", "ur", "urd"
        )

        /** 尾部小写 token → 规范 tag；认不出来返回 null。 */
        fun canonicalTag(token: String): String? {
            if (token.isEmpty()) return null
            ALIASES[token]?.let { return it }
            return if (token in OTHER_CODES) token else null
        }

        fun byTag(tag: String): F014Language? =
            values().firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        /** 语言 tag 的排序位；空串（未标注）与不认识的语言各归各的桶。 */
        fun rankOf(language: String): Int {
            if (language.isEmpty()) return UNTAGGED.rank
            return byTag(language)?.rank ?: OTHER_RANK
        }

        /** 界面上的语言名；不认识的 tag 也统一显示「其它语言」。 */
        fun labelOf(language: String): String {
            if (language.isEmpty()) return UNTAGGED.label
            return byTag(language)?.label ?: "其它语言"
        }
    }
}

/**
 * 「同名字幕自动匹配」引擎。
 *
 * 纯 Kotlin：不 import android.*，也不碰文件系统 —— 入参只有「视频文件名 + 同目录文件名列表」，
 * 真正的目录遍历由调用方在 IO 线程做完再喂进来，所以这段逻辑可以脱离设备单测。
 *
 * 匹配口径（同目录 + 同名）：
 * 1. 只认 srt / ass / ssa / vtt 四种字幕扩展名；
 * 2. 字幕要和视频在同一目录，或落在同一父目录下的字幕子目录（Subs / Subtitles / SUB / 字幕）；
 * 3. 字幕主干名剥掉尾部语言标记（.zh / .chs / .zh-Hans / .eng …）和 (1) / [2] / -copy3 这类拷贝序号后，
 *    必须与视频主干名完全一致 —— movie.mp4 命中 movie.srt、movie.zh.srt、movie.chs.srt、movie(1).srt；
 * 4. 多个候选按「未标注（完全同名）→ zh-Hans → zh-Hant → en → 其它」排序，同档再比置信度，取第一；
 * 5. 一个都对不上就返回 null（[candidates] 返回空列表），调用方静默跳过，不弹任何错误。
 *
 * 播放页接线：开播时在 IO 线程 `File(videoPath).parentFile?.list()`，结果喂给 [match]；
 * 非 null 就把 [MatchResult.subtitlePath] 交给 ExoPlayer 的 MediaItem.SubtitleConfiguration 自动加载，null 就什么都不做。
 */
class F014Engine {

    /** 取优先级最高的那一条；没有匹配时返回 null（静默跳过，不抛异常）。 */
    fun match(videoPath: String, siblingFiles: List<String>): MatchResult? =
        candidates(videoPath, siblingFiles).firstOrNull()

    /**
     * 全部候选，已按「语言优先级 → 置信度 → 文件名」排好序；没有候选时是空列表而不是 null，
     * 界面用它画候选排序表，[match] 直接取第一项。
     */
    fun candidates(videoPath: String, siblingFiles: List<String>): List<MatchResult> {
        val videoName = fileNameOf(videoPath)
        if (videoName.isEmpty()) return emptyList()

        // 视频名也走同一套归一化：movie(1).mp4 / movie.en.mp4 这种命名才能和字幕对上
        val videoStem = splitStem(withoutExtension(videoName)) ?: return emptyList()
        val videoBase = baseKey(videoStem.base)
        if (videoBase.isEmpty()) return emptyList()

        val videoDir = directoryOf(videoPath)
        return siblingFiles.asSequence()
            .mapNotNull { parseCandidate(it, videoDir, videoBase) }
            .distinctBy { it.result.subtitlePath }
            .sortedWith(ORDER)
            .map { it.result }
            .toList()
    }

    /** 文件名后缀 → 语言 tag；认不出来返回空串。界面上的语言标签、单测都用这个口径。 */
    fun inferLanguage(fileName: String): String =
        splitStem(withoutExtension(fileNameOf(fileName)))?.language.orEmpty()

    // ------------------------------------------------------------------ 匹配

    private fun parseCandidate(path: String, videoDir: String, videoBase: String): Scored? {
        val fileName = fileNameOf(path)
        if (fileName.isEmpty()) return null
        if (extensionOf(fileName) !in SUBTITLE_EXTENSIONS) return null

        // 目录先过一遍：不同目录 / 非字幕子目录的字幕不参与匹配
        val dirPenalty = directoryPenalty(directoryOf(path), videoDir) ?: return null

        val stem = splitStem(withoutExtension(fileName)) ?: return null
        if (baseKey(stem.base) != videoBase) return null

        var confidence = MAX_CONFIDENCE - dirPenalty
        if (stem.language.isNotEmpty()) confidence -= LANGUAGE_SUFFIX_PENALTY
        if (stem.copyIndex != null) confidence -= COPY_INDEX_PENALTY
        confidence = confidence.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)

        return Scored(
            MatchResult(path, stem.language, confidence),
            F014Language.rankOf(stem.language)
        )
    }

    /**
     * 目录是否算「同目录」：同目录返回 0，同级字幕子目录返回 [SUBDIRECTORY_PENALTY]，其它返回 null（淘汰）。
     *
     * @param subtitleDir 字幕所在目录，带结尾斜杠；没有目录时是空串
     * @param videoDir 视频所在目录，格式同上
     */
    private fun directoryPenalty(subtitleDir: String, videoDir: String): Int? {
        if (subtitleDir == videoDir) return 0
        val trimmed = subtitleDir.removeSuffix("/")
        val folder = trimmed.substringAfterLast('/')
        val parent = trimmed.substringBeforeLast('/', "")
        val parentDir = if (parent.isEmpty()) "" else "$parent/"
        if (parentDir != videoDir) return null
        return if (folder.lowercase() in SUBDIRECTORY_NAMES) SUBDIRECTORY_PENALTY else null
    }

    /**
     * 把主干名拆成「基名 + 语言 + 拷贝序号」。
     *
     * 语言标记只从尾部一路往前吃（movie.zh-Hans.srt、movie.chs.srt、movie.zh-Hant.forced.srt），
     * 片名中间碰巧长得像语言码的单词不会被误吃；基名必须还剩至少一个 token，
     * 这样视频就叫 en.mp4 时也不会把自己吃空。
     */
    private fun splitStem(stem: String): Stem? {
        val (withoutIndex, copyIndex) = stripCopyIndex(stem)
        val tokens = withoutIndex.split('.', '_').filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return null

        var language = ""
        while (tokens.size > 1) {
            val token = tokens.last().trim().trim('[', ']', '(', ')').lowercase()
            if (token in FLAG_TOKENS) {
                tokens.removeAt(tokens.lastIndex)
                continue
            }
            val tag = F014Language.canonicalTag(token)
            if (tag == null) break
            if (language.isEmpty()) language = tag
            tokens.removeAt(tokens.lastIndex)
        }

        val base = tokens.joinToString(" ")
        if (base.isBlank()) return null
        return Stem(base, language, copyIndex)
    }

    /**
     * 摘掉 (1) / [2] / -copy3 这类拷贝序号，返回剩余部分与序号。
     *
     * 括号序号可能出现在语言标记前面（movie(1).zh），也可能在后面（movie.zh(1)），所以先整体摘掉再看语言。
     */
    private fun stripCopyIndex(stem: String): Pair<String, Int?> {
        var index: Int? = null
        val withoutBracket = BRACKET_INDEX.replace(stem) { match ->
            index = match.groupValues[1].toIntOrNull()
            ""
        }
        val copyMatch = COPY_SUFFIX.find(withoutBracket)
        if (copyMatch != null) {
            index = copyMatch.groupValues[1].toIntOrNull() ?: index
            return withoutBracket.substring(0, copyMatch.range.first) to index
        }
        return withoutBracket to index
    }

    /** 比较用的键：大小写、分隔符、多余空格都不参与比较。 */
    private fun baseKey(base: String): String =
        SEPARATORS.replace(base.lowercase(), " ").trim()

    // ------------------------------------------------------------------ 路径

    private fun fileNameOf(path: String): String =
        path.replace('\\', '/').substringAfterLast('/').trim()

    private fun directoryOf(path: String): String {
        val normalized = path.replace('\\', '/')
        val cut = normalized.lastIndexOf('/')
        return if (cut < 0) "" else normalized.substring(0, cut + 1)
    }

    private fun withoutExtension(name: String): String =
        if (name.contains('.')) name.substringBeforeLast('.') else name

    private fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""

    /** 候选 + 排序位：语言优先级要参与排序，但又不想把它塞进给外部用的 [MatchResult]。 */
    private data class Scored(val result: MatchResult, val rank: Int)

    private data class Stem(val base: String, val language: String, val copyIndex: Int?)

    private companion object {
        /** 支持的字幕扩展名。 */
        val SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "vtt")

        /** 同级字幕子目录的常见命名（大小写不敏感）。 */
        val SUBDIRECTORY_NAMES: Set<String> = setOf("subs", "subtitles", "sub", "字幕", "字幕文件")

        /** 尾部这些 token 是字幕标记而不是语言，剥掉但不参与语言推断。 */
        val FLAG_TOKENS: Set<String> = setOf("forced", "sdh", "cc", "default")

        /** 完全同名的置信度上限。 */
        const val MAX_CONFIDENCE = 100
        const val MIN_CONFIDENCE = 50

        /** 带语言后缀：仍然是同名族，但比完全同名弱一点。 */
        const val LANGUAGE_SUFFIX_PENALTY = 2

        /** 带 (1) 这类拷贝序号：多人各下一份的常见结果，可信度再低一点。 */
        const val COPY_INDEX_PENALTY = 6

        /** 落在字幕子目录里：同名同目录规则的延伸，但不如同目录直接。 */
        const val SUBDIRECTORY_PENALTY = 10

        val BRACKET_INDEX = Regex("""\s*[\(\[](\d{1,2})[\)\]]""")
        val COPY_SUFFIX = Regex("""[ ._-]*copy[ ._-]?\d{1,2}\s*$""", RegexOption.IGNORE_CASE)
        val SEPARATORS = Regex("""[\s._\-]+""")

        /** 语言优先级 → 置信度高低 → 文件名，稳定且可复现。 */
        val ORDER: Comparator<Scored> = compareBy<Scored>(
            { it.rank },
            { -it.result.confidence },
            { it.result.subtitlePath.lowercase() }
        )
    }
}
