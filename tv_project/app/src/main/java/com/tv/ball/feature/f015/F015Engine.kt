package com.tv.ball.feature.f015

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 一次编码探测的结果。
 *
 * @param charset    判定的编码名，取值来自 [F015Engine.CANDIDATES]
 * @param confidence 0~1 的可信度，低于 [F015Engine.MIN_CONFIDENCE] 就不该拿它去解码
 * @param triedOrder 实际按序尝试过的编码，界面把这条链路原样展示给用户看
 */
data class Detection(
    val charset: String,
    val confidence: Float,
    val triedOrder: List<String>
) {
    /** 探测是否可信：可信度够才算成功，否则界面提示"疑似乱码"。 */
    val reliable: Boolean get() = confidence >= F015Engine.MIN_CONFIDENCE
}

/**
 * 字幕编码探测引擎。
 *
 * 要解决的问题：字幕文件不带编码元数据，GBK / Big5 的文件被当 UTF-8 读就是一堆乱码。
 * 策略分两层：
 * 1. BOM 优先 —— 命中 `EF BB BF` / `FF FE` / `FE FF` 就直接按 BOM 定编码，不再打分；
 * 2. 无 BOM 就按 [CANDIDATES] 的顺序逐个严格解码并打分，取最高分，同分时靠前的赢。
 *    这样纯 ASCII 会落在 UTF-8 而不是 GBK，和"UTF-8 兼容 ASCII"的常识一致。
 *
 * 打分 = 严格解码是否通过 × (1 - 可疑字符比例)。可疑字符指替换字符、控制字符、私用区字符，
 * 这些正好是字节被错位解释时的典型落点（UTF-8 中文按 UTF-16LE 读，大半会落进私用区）。
 * 任一候选的可疑字符比例超过 [GARBLED_RATIO]，分数会被压到 [MIN_CONFIDENCE] 以下——
 * 宁可告诉用户"疑似乱码"，也不要给出一个看起来有值但错误的编码。
 *
 * 纯 Kotlin，只用 `java.nio.charset`，不依赖 android.*，可以脱离设备单测。
 */
class F015Engine {

    /**
     * 按 BOM 与候选顺序探测编码；空输入返回可信度 0。
     *
     * 注意 GBK 与 Big5 的字节区间有重叠，个别 Big5 文本可能被判成 GBK（GBK 在前），
     * 所以结果必须允许人工覆盖：界面上的候选编码按钮就是干这个的。
     */
    fun detect(bytes: ByteArray): Detection {
        if (bytes.isEmpty()) return Detection(UTF8, 0f, emptyList())

        val bom = sniffBom(bytes)
        if (bom != null) {
            // BOM 是文件自己声明的编码，比任何打分都可靠；只有解出来仍是乱码时才降可信度
            val text = decode(bytes, bom)
            val confidence = if (looksGarbled(text)) 0.50f else 0.99f
            return Detection(bom, confidence, listOf(bom))
        }

        var bestCharset = UTF8
        var bestScore = -1f
        val tried = ArrayList<String>(CANDIDATES.size)
        for (name in CANDIDATES) {
            val charset = charsetOrNull(name) ?: continue
            tried += name
            val score = scoreOf(attempt(bytes, charset, 0))
            if (score > bestScore) {
                bestScore = score
                bestCharset = name
            }
        }
        return Detection(bestCharset, bestScore.coerceIn(0f, 1f), tried)
    }

    /**
     * 用指定编码解码。
     *
     * 坏字节走替换字符（U+FFFD）而不是抛异常：调用方拿 [looksGarbled] 判断要不要显示正文，
     * 这比在界面层捕获 CharacterCodingException 更好用。开头的 BOM 会被剥掉。
     */
    fun decode(bytes: ByteArray, charset: String): String {
        val cs = charsetOrNull(charset) ?: charsetOrNull(UTF8) ?: return ""
        val skip = bomLength(bytes, charset)
        return stripLeadingBom(attempt(bytes, cs, skip).text)
    }

    /**
     * 文本是否疑似乱码：替换字符、控制字符、私用区字符占比达到 [GARBLED_RATIO] 即算。
     *
     * 纯空白不算乱码，否则空字幕会被误判。
     */
    fun looksGarbled(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        return suspiciousRatio(body) >= GARBLED_RATIO
    }

    /** 可疑字符比例（0~1），[looksGarbled] 的量化依据，也方便界面展示。 */
    fun suspiciousRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        var bad = 0
        var total = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // 代理对整体算一个字符，别把合法增补平面内容当成孤立代理项
            if (Character.isHighSurrogate(ch) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) {
                total++
                i += 2
                continue
            }
            total++
            if (isSuspicious(ch)) bad++
            i++
        }
        return bad.toFloat() / total
    }

    /** BOM 声明的编码，没有 BOM 返回 null。 */
    fun sniffBom(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> UTF8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
        else -> null
    }

    // ===== 内部实现 =====

    private data class Attempt(val text: String, val strict: Boolean)

    private fun attempt(bytes: ByteArray, charset: Charset, offset: Int): Attempt {
        val length = bytes.size - offset
        if (length <= 0) return Attempt("", true)
        return try {
            val strict = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Attempt(strict.decode(ByteBuffer.wrap(bytes, offset, length)).toString(), true)
        } catch (e: CharacterCodingException) {
            // 严格解码失败就退回替换字符模式，让上层按替换字符比例判乱码，而不是直接抛出去
            val lenient = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            Attempt(lenient.decode(ByteBuffer.wrap(bytes, offset, length)).toString(), false)
        }
    }

    private fun scoreOf(attempt: Attempt): Float {
        if (attempt.text.isEmpty()) return 0f
        val bad = suspiciousRatio(attempt.text)
        if (bad >= GARBLED_RATIO) {
            // 解出来大部分是可疑字符：压到门槛以下，宁可报"疑似乱码"也不给错的编码
            return (0.30f - bad * 0.30f).coerceIn(0.02f, 0.30f)
        }
        val base = if (attempt.strict) STRICT_BASE else LENIENT_BASE
        return (base * (1f - bad * 1.5f)).coerceIn(0f, 1f)
    }

    private fun isSuspicious(ch: Char): Boolean {
        val code = ch.code
        return when {
            ch == '\uFFFD' -> true                                    // 解码器替换字符
            code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> true // 换行/制表以外的控制字符
            code in 0x7F..0x9F -> true                                // DEL 与 C1 控制区
            code in 0xE000..0xF8FF -> true                            // 私用区：字节错位最常见的落点
            code in 0xD800..0xDFFF -> true                            // 孤立代理项
            code in 0xFDD0..0xFDEF -> true                            // 非字符
            (code and 0xFFFE) == 0xFFFE -> true                       // 每平面末两位非字符
            else -> false
        }
    }

    private fun bomLength(bytes: ByteArray, charset: String): Int = when {
        charset.equals(UTF8, ignoreCase = true) && bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
        charset.equals("UTF-16LE", ignoreCase = true) && bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> 2
        charset.equals("UTF-16BE", ignoreCase = true) && bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> 2
        else -> 0
    }

    private fun stripLeadingBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (e: IllegalArgumentException) {
        // IllegalCharsetNameException 与 UnsupportedCharsetException 都是它的子类
        null
    }

    companion object {
        /** 无需 BOM 时的探测顺序，界面候选按钮也按这个顺序排。 */
        val CANDIDATES: List<String> = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GBK", "Big5")

        /** 可信度门槛：低于它一律按"疑似乱码"处理。 */
        const val MIN_CONFIDENCE = 0.45f

        /** 可疑字符比例达到这个值就算乱码。 */
        const val GARBLED_RATIO = 0.05f

        private const val UTF8 = "UTF-8"

        /** 严格解码通过时的基础分。 */
        private const val STRICT_BASE = 0.92f

        /** 严格解码失败时的基础分，已在门槛以下。 */
        private const val LENIENT_BASE = 0.34f
    }
}