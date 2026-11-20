package com.tv.ball.feature.f018

/**
 * 字幕全文检索：纯 Kotlin，无 Android 依赖，可以脱离运行时直接做 JVM 单测。
 *
 * 匹配思路：把原文折叠成一份「可比较串」——全角转半角、繁转简、可选忽略大小写、
 * 可选忽略空白；折叠的同时记录每个折叠字符对应的原文下标。命中后按映射把区间
 * 还原成原文下标，界面拿到这段下标就能直接用 SpannableString 高亮，不需要再
 * 自己猜哪里是关键字。
 */
object F018Engine {

    /** 一条字幕。时间单位毫秒，[index] 是这条字幕在整轨里的序号（从 1 开始，界面直接显示）。 */
    data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

    /** 一处命中：命中的条目 + 原文里的命中区间（左闭右开，可直接交给 Spannable）。 */
    data class Hit(val cue: Cue, val matchStart: Int, val matchEnd: Int)

    /**
     * 全文检索。
     *
     * @param ignoreCase 忽略大小写（英文台词用得上）
     * @param ignoreSpace 忽略空白容错：字幕里断行、双空格、全角空格都能搜到
     * @return 按条目顺序排列的命中，同一条字幕里出现多次会给出多条（单条有上限，避免刷屏）
     */
    fun search(
        cues: List<Cue>,
        keyword: String,
        ignoreCase: Boolean = true,
        ignoreSpace: Boolean = true
    ): List<Hit> {
        val foldedKeyword = fold(keyword, ignoreCase, ignoreSpace)
        if (foldedKeyword.isEmpty()) return emptyList()

        val result = ArrayList<Hit>()
        for (cue in cues) {
            if (cue.text.isEmpty()) continue
            val folded = foldWithMap(cue.text, ignoreCase, ignoreSpace)
            if (folded.text.isEmpty()) continue
            var from = 0
            var found = 0
            while (found < MAX_HITS_PER_CUE) {
                val at = folded.text.indexOf(foldedKeyword, from)
                if (at < 0) break
                val start = folded.origin[at]
                val end = folded.origin[at + foldedKeyword.length - 1] + 1
                result += Hit(cue, start, end)
                found++
                from = at + foldedKeyword.length
            }
        }
        return result
    }

    /** 毫秒 → `hh:mm:ss.SSS`，跳转目标与列表时间列共用。 */
    fun format(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val h = safe / 3_600_000
        val m = safe % 3_600_000 / 60_000
        val s = safe % 60_000 / 1_000
        val f = safe % 1_000
        return String.format("%02d:%02d:%02d.%03d", h, m, s, f)
    }

    /**
     * 折叠后的串 + 每个折叠字符对应的原文下标。
     *
     * 用 IntArray 而不是 List<Int>：长字幕轨下命中扫描是热路径，装箱会拖慢检索。
     */
    private class Folded(val text: String, val origin: IntArray)

    private fun fold(text: String, ignoreCase: Boolean, ignoreSpace: Boolean): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ignoreSpace && isSpace(ch)) continue
            sb.append(foldChar(ch, ignoreCase))
        }
        return sb.toString()
    }

    private fun foldWithMap(text: String, ignoreCase: Boolean, ignoreSpace: Boolean): Folded {
        val sb = StringBuilder(text.length)
        val origin = IntArray(text.length)
        var kept = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ignoreSpace && isSpace(ch)) continue
            sb.append(foldChar(ch, ignoreCase))
            origin[kept] = i
            kept++
        }
        return Folded(sb.toString(), origin.copyOf(kept))
    }

    private fun foldChar(ch: Char, ignoreCase: Boolean): Char {
        var c = ch
        // 全角 ASCII（U+FF01..U+FF5E）折到半角：中文输入法混着敲也能搜到
        if (c.code in 0xFF01..0xFF5E) c = (c.code - 0xFEE0).toChar()
        val simplified = TRAD_TO_SIMP[c]
        if (simplified != null) c = simplified
        return if (ignoreCase) c.lowercaseChar() else c
    }

    private fun isSpace(ch: Char): Boolean = ch.isWhitespace() || ch == '\u3000'

    /** 单条字幕最多返回的命中数。 */
    private const val MAX_HITS_PER_CUE = 8

    /**
     * 常用繁体 → 简体字表。
     *
     * 只收字幕里高频的字，不做完整字形转换（那需要整张映射表）：搜「这个」能命中「這個」，
     * 但生僻异体字仍会漏，属于已知限制。
     */
    private val TRAD_TO_SIMP: Map<Char, Char> = mapOf(
        '個' to '个', '們' to '们', '這' to '这', '說' to '说', '話' to '话',
        '時' to '时', '間' to '间', '對' to '对', '於' to '于', '會' to '会',
        '來' to '来', '裡' to '里', '裏' to '里', '麼' to '么', '為' to '为',
        '沒' to '没', '現' to '现', '開' to '开', '關' to '关', '讓' to '让',
        '覺' to '觉', '過' to '过', '還' to '还', '進' to '进', '遠' to '远',
        '點' to '点', '條' to '条', '終' to '终', '與' to '与', '從' to '从',
        '動' to '动', '學' to '学', '實' to '实', '應' to '应', '該' to '该',
        '樣' to '样', '種' to '种', '經' to '经', '歷' to '历', '變' to '变',
        '愛' to '爱', '歡' to '欢', '樂' to '乐', '傷' to '伤', '憶' to '忆',
        '記' to '记', '誰' to '谁', '邊' to '边', '處' to '处', '聲' to '声',
        '響' to '响', '聽' to '听', '見' to '见', '嗎' to '吗', '專' to '专',
        '業' to '业', '師' to '师', '醫' to '医', '車' to '车', '機' to '机',
        '網' to '网', '絡' to '络', '線' to '线', '腦' to '脑', '視' to '视',
        '頻' to '频', '號' to '号', '節' to '节', '劇' to '剧', '員' to '员',
        '團' to '团', '隊' to '队', '幫' to '帮', '殺' to '杀', '戰' to '战',
        '爭' to '争', '軍' to '军', '敵' to '敌', '錢' to '钱', '買' to '买',
        '賣' to '卖', '費' to '费', '價' to '价', '單' to '单', '雙' to '双',
        '數' to '数', '據' to '据', '圖' to '图', '書' to '书', '頁' to '页',
        '檔' to '档', '傳' to '传', '輸' to '输', '連' to '连', '續' to '续',
        '斷' to '断', '鎖' to '锁', '鑰' to '钥', '門' to '门', '陽' to '阳',
        '陰' to '阴', '雲' to '云', '風' to '风', '電' to '电', '燈' to '灯',
        '燒' to '烧', '熱' to '热', '溫' to '温', '濕' to '湿', '歲' to '岁',
        '齡' to '龄', '舊' to '旧', '亂' to '乱', '靜' to '静', '麗' to '丽',
        '醜' to '丑', '壞' to '坏', '錯' to '错', '誤' to '误', '認' to '认',
        '識' to '识', '讀' to '读', '寫' to '写', '講' to '讲', '談' to '谈',
        '論' to '论', '議' to '议', '訊' to '讯', '訓' to '训', '練' to '练',
        '習' to '习', '慣' to '惯', '態' to '态', '願' to '愿', '夢' to '梦',
        '氣' to '气', '運' to '运', '轉' to '转', '載' to '载', '飛' to '飞',
        '遊' to '游', '戲' to '戏', '賭' to '赌', '擊' to '击', '鬥' to '斗',
        '勝' to '胜', '敗' to '败', '贏' to '赢', '搶' to '抢', '奪' to '夺',
        '護' to '护', '衛' to '卫', '藥' to '药', '針' to '针', '療' to '疗',
        '髮' to '发', '發' to '发', '頭' to '头', '臉' to '脸', '腳' to '脚',
        '體' to '体', '膚' to '肤', '彈' to '弹', '鬆' to '松', '緊' to '紧',
        '剛' to '刚', '長' to '长', '幾' to '几', '銀' to '银', '鐘' to '钟'
    )
}