package com.tv.ball.feature.f064

import kotlin.math.abs

/**
 * 「二维码分享地址」的编码内核：把一段文本编成 QR 码模块矩阵。
 *
 * 为什么自己写：项目里没有二维码依赖，为了画一张黑白图去引一整个库不划算；
 * QR 的规则是完全公开的，照着标准逐条实现即可。这里只做「生成」，不做「识别」。
 *
 * 固定参数（够放一个播放地址，手机也好扫）：
 * - 模式：Byte（0100）。地址都是 ASCII，UTF-8 编码后逐字节塞进数据段，中文地址也能编；
 * - 纠错：M（约 15% 冗余）。L 太脆，Q/H 会把容量砍掉一半，M 是分享链接的常规选择；
 * - 版本：1~6 自动挑（21×21 ~ 41×41 模块）。版本 6 的 M 级能放 106 字节，
 *   一个局域网播放地址远到不了这个上限；
 * - 掩码：0~7 全画一遍，按标准罚分取最低的那张。
 *
 * 五步流程和 QR 标准的分层一一对应（[encode] 里就是按这个顺序展开的）：
 * 1. 数据编码：模式指示符 + 字符计数 + 文本字节 → 位流 → 终止符 / 补齐 → 数据码字；
 * 2. 分块 Reed-Solomon：数据码字按版本规定的块数切开，每块用 GF(256) 生成多项式算纠错码字；
 * 3. 交织：各块数据码字逐列穿插，再接各块纠错码字逐列穿插，得到最终码字序列；
 * 4. 放置：画定位 / 定时 / 校正图形和格式信息，再把码字按「右下角起、两列一组、上下蛇形」填进空模块；
 * 5. 掩码：对非功能模块异或 8 种掩码之一，格式信息同步写对应掩码号，罚分最低者胜出。
 *
 * 矩阵约定：`modules[row][col]`，true = 深色（黑），false = 浅色（白）；
 * 矩阵本身不含静区，画图时由调用方自己留 4 个模块的白边。
 */
object F064Engine {

    /** 生成结果：边长（模块数）与模块矩阵。 */
    class Qr(val size: Int, val modules: Array<BooleanArray>) {

        /** 版本号：1 → 21×21，6 → 41×41。 */
        val version: Int get() = (size - 17) / 4

        fun isDark(row: Int, col: Int): Boolean = modules[row][col]
    }

    /** 文本超出版本 6 / 纠错 M 的容量。界面按 [OverflowException.limit] 写提示。 */
    class OverflowException(val byteCount: Int, val limit: Int) :
        IllegalArgumentException("$byteCount 字节超过二维码上限 $limit 字节")

    /** 版本 6、纠错 M 单张能放的最大字节数。 */
    val maxBytes: Int get() = capacityBytes(MAX_VERSION)

    /** 某个版本、纠错 M 下字节模式能放的字节数：容量位减去模式 4 位和计数 8 位，再按整字节取。 */
    fun capacityBytes(version: Int): Int {
        val spec = specOf(version) ?: return 0
        return (spec.dataCodewords * 8 - MODE_BITS - COUNT_BITS) / 8
    }

    /**
     * 把 [text] 编成二维码。
     *
     * @throws OverflowException 文本超过版本 6 / 纠错 M 的容量（106 字节）。
     */
    fun encode(text: String): Qr {
        val payload = text.toByteArray(Charsets.UTF_8)
        val spec = SPECS.firstOrNull { capacityBytes(it.version) >= payload.size }
            ?: throw OverflowException(payload.size, maxBytes)

        // 1) 数据编码
        val data = encodeData(payload, spec)

        // 2) 分块 + Reed-Solomon：每块单独算纠错码字
        val blocks = splitBlocks(data, spec)
        val ecDivisor = rsDivisor(spec.ecPerBlock)
        val ecBlocks = blocks.map { rsRemainder(it, ecDivisor) }

        // 3) 交织
        val codewords = interleave(blocks, ecBlocks)

        // 4) 放置 + 5) 掩码：8 种掩码各画一张完整矩阵（含格式信息），取罚分最低的
        var bestModules: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val modules = buildMatrix(spec.version, codewords, mask)
            val penalty = penaltyScore(modules)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestModules = modules
            }
        }
        val modules = bestModules ?: error("F064: 二维码矩阵没有生成出来")

        // 交出去之前核对一次边长，防止版本表和矩阵构建走岔
        check(modules.size == spec.version * 4 + 17) { "F064: 矩阵边长与版本不符" }
        return Qr(modules.size, modules)
    }

    // ===== 版本表（纠错等级 M） =====

    /**
     * 每个版本在 M 级下的码字结构。
     *
     * 数值来自 ISO/IEC 18004 的表：版本 1~6 的 M 级每块都是等长的，
     * 没有「长短块」之分（那种情况要到版本 7 以后才出现）。
     */
    private class Spec(
        val version: Int,
        val ecPerBlock: Int,
        val blocks: Int,
        val dataCodewords: Int
    ) {
        val dataPerBlock: Int get() = dataCodewords / blocks
    }

    private val SPECS = listOf(
        //        version 纠错/块 块数 数据码字
        Spec(1, 10, 1, 16),
        Spec(2, 16, 1, 28),
        Spec(3, 26, 1, 44),
        Spec(4, 18, 2, 64),
        Spec(5, 24, 2, 86),
        Spec(6, 16, 4, 108)
    )

    private fun specOf(version: Int): Spec? = SPECS.firstOrNull { it.version == version }

    // ===== 1) 数据编码 =====

    /**
     * 位流：模式指示符 0100 → 8 位字符计数 → 每个字节 8 位 → 4 位终止符 → 补到字节边界
     * → 剩下用 0xEC / 0x11 交替填满，正好凑满该版本的数据码字数。
     *
     * 版本 1~9 的字节模式计数固定 8 位，所以这里不用按版本换宽度。
     */
    private fun encodeData(payload: ByteArray, spec: Spec): ByteArray {
        val capacityBits = spec.dataCodewords * 8
        val out = ByteArray(spec.dataCodewords)
        var bitPos = 0

        fun put(value: Int, length: Int) {
            var i = length - 1
            while (i >= 0 && bitPos < capacityBits) {
                if ((value ushr i) and 1 == 1) {
                    val index = bitPos ushr 3
                    out[index] = (out[index].toInt() or (0x80 ushr (bitPos and 7))).toByte()
                }
                bitPos++
                i--
            }
        }

        put(MODE_BYTE, MODE_BITS)
        put(payload.size, COUNT_BITS)
        for (b in payload) put(b.toInt() and 0xFF, 8)

        // 终止符最多 4 位；容量刚好用完时一位都不写
        put(0, minOf(TERMINATOR_BITS, capacityBits - bitPos))
        if (bitPos % 8 != 0) put(0, 8 - bitPos % 8)

        var pad = PAD_FIRST
        while (bitPos < capacityBits) {
            put(pad, 8)
            pad = pad xor PAD_SWITCH
        }
        return out
    }

    /** 把数据码字按块切开：版本 1~6 的 M 级各块等长，顺序切就行。 */
    private fun splitBlocks(data: ByteArray, spec: Spec): List<IntArray> {
        val per = spec.dataPerBlock
        return (0 until spec.blocks).map { block ->
            IntArray(per) { i -> data[block * per + i].toInt() and 0xFF }
        }
    }

    // ===== 2) Reed-Solomon（GF(256)，本原多项式 0x11D） =====

    /** GF(256) 乘法：俄罗斯农民乘法，每一步顺带对 0x11D 取模。 */
    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        var i = 7
        while (i >= 0) {
            z = (z shl 1) xor ((z ushr 7) * GF_PRIMITIVE)
            z = z xor (((y ushr i) and 1) * x)
            i--
        }
        return z and 0xFF
    }

    /** 生成多项式 (x-α^0)(x-α^1)…(x-α^(degree-1)) 展开后的系数，首项隐含为 1。 */
    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    /** 多项式除法取余：结果就是这一块的纠错码字。 */
    private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b xor result[0]) and 0xFF
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
        }
        return result
    }

    // ===== 3) 交织 =====

    /**
     * 标准的交织顺序：先把所有块的「第 0 个数据码字」排完，再排「第 1 个」……
     * 纠错码字同理。这样单块连续损坏时，被打散到整个码字序列里，纠错才有余量。
     */
    private fun interleave(blocks: List<IntArray>, ecBlocks: List<IntArray>): ByteArray {
        val dataPerBlock = blocks.firstOrNull()?.size ?: 0
        val ecPerBlock = ecBlocks.firstOrNull()?.size ?: 0
        val out = ByteArray(blocks.size * dataPerBlock + ecBlocks.size * ecPerBlock)
        var pos = 0
        for (i in 0 until dataPerBlock) {
            for (block in blocks) out[pos++] = block[i].toByte()
        }
        for (i in 0 until ecPerBlock) {
            for (block in ecBlocks) out[pos++] = block[i].toByte()
        }
        return out
    }

    // ===== 4) 放置 =====

    /**
     * 画好所有功能图形（定位 / 定时 / 校正 / 格式信息），再把码字填进剩下的模块，
     * 最后按 [mask] 异或掩码。功能图形一旦落笔就记在 `function` 里，
     * 后面填码字和掩码都会跳过它们。
     */
    private fun buildMatrix(version: Int, codewords: ByteArray, mask: Int): Array<BooleanArray> {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        // 三个定位图形（7×7 同心方框）+ 它们外侧一圈浅色分隔符，一并在 drawFinder 里画完
        drawFinder(modules, function, 3, 3)
        drawFinder(modules, function, 3, size - 4)
        drawFinder(modules, function, size - 4, 3)

        // 定时图形：第 6 行、第 6 列的深浅交替，偶数位是深色
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            plot(modules, function, 6, i, dark)
            plot(modules, function, i, 6, dark)
        }

        // 校正图形：版本 2 起才有，跳过与三个定位图形重叠的组合
        val centers = alignmentCenters(version)
        if (centers.isNotEmpty()) {
            for (row in centers) {
                for (col in centers) {
                    val overlapsFinder = (row == 6 && col == 6) ||
                        (row == 6 && col == centers.last()) ||
                        (row == centers.last() && col == 6)
                    if (!overlapsFinder) drawAlignment(modules, function, row, col)
                }
            }
        }

        // 格式信息：纠错等级 + 掩码号，BCH(15,5) 校验后异或 0x5412，两份一起写
        drawFormat(modules, function, mask)

        // 码字
        placeData(modules, function, codewords)

        // 掩码：只翻非功能模块
        applyMask(modules, function, mask)
        return modules
    }

    /** 落笔并标记为功能模块；越界（定位图形的分隔符有一圈在矩阵外）直接忽略。 */
    private fun plot(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        row: Int,
        col: Int,
        dark: Boolean
    ) {
        if (row !in modules.indices || col !in modules.indices) return
        modules[row][col] = dark
        function[row][col] = true
    }

    /**
     * 以 ([centerRow], [centerCol]) 为中心画定位图形。
     *
     * 切比雪夫距离 0/1 是实心的 3×3，2 是白环，3 是黑环，4 是白分隔符——一个 9×9 的循环就够了。
     */
    private fun drawFinder(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        centerRow: Int,
        centerCol: Int
    ) {
        for (dr in -4..4) {
            for (dc in -4..4) {
                val distance = maxOf(abs(dr), abs(dc))
                plot(modules, function, centerRow + dr, centerCol + dc, distance != 2 && distance != 4)
            }
        }
    }

    /** 校正图形：5×5，中心一个深色点 + 一圈白 + 一圈黑。 */
    private fun drawAlignment(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        centerRow: Int,
        centerCol: Int
    ) {
        for (dr in -2..2) {
            for (dc in -2..2) {
                val distance = maxOf(abs(dr), abs(dc))
                plot(modules, function, centerRow + dr, centerCol + dc, distance != 1)
            }
        }
    }

    /**
     * 格式信息 15 位：2 位纠错等级（M = 00）+ 3 位掩码号，后面接 10 位 BCH 校验（生成多项式 0x537），
     * 整体再异或 0x5412（标准规定，避免全 0 掩码号产生全白格式区）。
     *
     * 两份副本：一份绕着左上角的定位图形，一份拆在右上（8 位）和左下（7 位）。
     * 最后那个「固定深色模块」永远是黑的，不参与数据。
     */
    private fun drawFormat(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        mask: Int
    ) {
        val size = modules.size
        val data = (EC_LEVEL_M shl 3) or mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * FORMAT_GENERATOR) }
        val bits = ((data shl 10) or remainder) xor FORMAT_MASK

        fun bit(index: Int): Boolean = ((bits ushr index) and 1) == 1

        // 第一份：第 8 列自上而下放 0~5 位，拐到第 8 行再往左放 9~14 位
        for (i in 0..5) plot(modules, function, i, 8, bit(i))
        plot(modules, function, 7, 8, bit(6))
        plot(modules, function, 8, 8, bit(7))
        plot(modules, function, 8, 7, bit(8))
        for (i in 9..14) plot(modules, function, 8, 14 - i, bit(i))

        // 第二份：第 8 行右端起 0~7 位，第 8 列底部 8~14 位
        for (i in 0 until 8) plot(modules, function, 8, size - 1 - i, bit(i))
        for (i in 8 until 15) plot(modules, function, size - 15 + i, 8, bit(i))

        // 固定深色模块
        plot(modules, function, size - 8, 8, true)
    }

    /**
     * 码字放置：从右下角开始，每次取两列，在一列里上下蛇形走；遇到功能模块跳过。
     * 第 6 列整条是定时图形，走到它时直接跳到第 5 列。
     *
     * 走完码字后若还剩模块（每个版本末尾都有几位「剩余位」），保持浅色即可。
     */
    private fun placeData(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        codewords: ByteArray
    ) {
        val size = modules.size
        val totalBits = codewords.size * 8
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vertical in 0 until size) {
                for (j in 0 until 2) {
                    val col = right - j
                    val row = if (upward) size - 1 - vertical else vertical
                    if (function[row][col]) continue
                    if (bitIndex < totalBits) {
                        val byte = codewords[bitIndex ushr 3].toInt() and 0xFF
                        modules[row][col] = ((byte ushr (7 - (bitIndex and 7))) and 1) == 1
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
    }

    // ===== 5) 掩码 =====

    /** 八种掩码的翻转条件，下标就是格式信息里写的掩码号。 */
    private fun maskCondition(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        else -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
    }

    private fun applyMask(
        modules: Array<BooleanArray>,
        function: Array<BooleanArray>,
        mask: Int
    ) {
        for (row in modules.indices) {
            for (col in modules.indices) {
                if (function[row][col]) continue
                if (maskCondition(mask, row, col)) modules[row][col] = !modules[row][col]
            }
        }
    }

    /**
     * 标准罚分：四条规则叠加，分数越低越容易被扫码器认出来。
     *
     * 1. 同行／同列连续同色 ≥5 个：每次 3 分，之后每多一个 +1；
     * 2. 任意 2×2 全同色：3 分；
     * 3. 看起来像定位图形的 1:1:3:1:1 序列（前面或后面跟 4 个浅色）：40 分；
     * 4. 深色比例偏离 50% 每 5%：10 分。
     */
    private fun penaltyScore(modules: Array<BooleanArray>): Int {
        val size = modules.size
        var score = 0
        val line = BooleanArray(size)

        for (row in 0 until size) {
            for (col in 0 until size) line[col] = modules[row][col]
            score += linePenalty(line)
        }
        for (col in 0 until size) {
            for (row in 0 until size) line[row] = modules[row][col]
            score += linePenalty(line)
        }

        for (row in 0 until size - 1) {
            for (col in 0 until size - 1) {
                val color = modules[row][col]
                if (color == modules[row][col + 1] &&
                    color == modules[row + 1][col] &&
                    color == modules[row + 1][col + 1]
                ) {
                    score += PENALTY_BLOCK
                }
            }
        }

        var dark = 0
        for (row in 0 until size) {
            for (col in 0 until size) if (modules[row][col]) dark++
        }
        val total = size * size
        val steps = abs(dark * 2 - total) * 10 / total
        score += steps * PENALTY_BALANCE
        return score
    }

    /** 单行／单列的规则 1 与规则 3。 */
    private fun linePenalty(line: BooleanArray): Int {
        var score = 0
        var run = 1
        for (i in 1 until line.size) {
            if (line[i] == line[i - 1]) {
                run++
            } else {
                if (run >= 5) score += PENALTY_RUN + (run - 5)
                run = 1
            }
        }
        if (run >= 5) score += PENALTY_RUN + (run - 5)

        for (start in 0..line.size - FINDER_LIKE_LENGTH) {
            if (matchesFinderLike(line, start)) score += PENALTY_FINDER
        }
        return score
    }

    private fun matchesFinderLike(line: BooleanArray, start: Int): Boolean {
        for (pattern in FINDER_LIKE_PATTERNS) {
            var same = true
            for (i in pattern.indices) {
                if (line[start + i] != pattern[i]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }

    /** 校正图形中心坐标（版本 1 没有）。 */
    private fun alignmentCenters(version: Int): IntArray = when (version) {
        1 -> IntArray(0)
        2 -> intArrayOf(6, 18)
        3 -> intArrayOf(6, 22)
        4 -> intArrayOf(6, 26)
        5 -> intArrayOf(6, 30)
        else -> intArrayOf(6, 34)
    }

    // ===== 常量 =====

    private const val MAX_VERSION = 6

    /** 字节模式指示符 0100。 */
    private const val MODE_BYTE = 0b0100
    private const val MODE_BITS = 4

    /** 版本 1~9 字节模式的字符计数宽度。 */
    private const val COUNT_BITS = 8
    private const val TERMINATOR_BITS = 4

    /** 填充字节：0xEC 与 0x11 交替，异或 0xFD 正好来回切换。 */
    private const val PAD_FIRST = 0xEC
    private const val PAD_SWITCH = 0xFD

    /** 纠错等级 M 在格式信息里的两位编码是 00。 */
    private const val EC_LEVEL_M = 0b00

    /** BCH(15,5) 的生成多项式和标准规定的固定掩码。 */
    private const val FORMAT_GENERATOR = 0x537
    private const val FORMAT_MASK = 0x5412

    /** GF(256) 的本原多项式（x^8 + x^4 + x^3 + x^2 + 1）。 */
    private const val GF_PRIMITIVE = 0x11D

    private const val PENALTY_RUN = 3
    private const val PENALTY_BLOCK = 3
    private const val PENALTY_FINDER = 40
    private const val PENALTY_BALANCE = 10

    /** 类定位图形：深-浅-深深深-浅-深，两侧之一跟 4 个浅色，合起来 11 个模块。 */
    private const val FINDER_LIKE_LENGTH = 11

    private val FINDER_LIKE_PATTERNS = arrayOf(
        booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false),
        booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
    )
}
