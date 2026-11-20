package com.tv.ball.feature.f096

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F096 字幕解析单元测试（JUnit4）。
 * 覆盖：正常两段、BOM 头、CRLF、空文本、坏时间轴（断言行号）、end < start、多行字幕文本。
 */
class F096SrtParserTest {

    private fun okOf(result: Result): Result.Ok {
        assertTrue("期望 Result.Ok，实际为: " + result, result is Result.Ok)
        return result as Result.Ok
    }

    private fun failOf(result: Result): Result.Fail {
        assertTrue("期望 Result.Fail，实际为: " + result, result is Result.Fail)
        return result as Result.Fail
    }

    // 1) 正常两段
    @Test
    fun parse_normalTwoCues_returnsTimesAndText() {
        val srt = "1\n" +
            "00:00:01,000 --> 00:00:03,500\n" +
            "第一行字幕\n" +
            "\n" +
            "2\n" +
            "00:01:04,250 --> 00:02:06,750\n" +
            "Second cue\n"

        val cues = okOf(F096SrtParser.parse(srt)).cues

        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("第一行字幕", cues[0].text)
        // 00:01:04,250 = 64250ms; 00:02:06,750 = 126750ms
        assertEquals(64250L, cues[1].startMs)
        assertEquals(126750L, cues[1].endMs)
        assertEquals("Second cue", cues[1].text)
    }

    // 2) 带 BOM 头
    @Test
    fun parse_withBomHeader_returnsOk() {
        val srt = "\uFEFF1\n" +
            "00:00:00,500 --> 00:00:02,000\n" +
            "带 BOM 的字幕\n"

        val cues = okOf(F096SrtParser.parse(srt)).cues

        assertEquals(1, cues.size)
        assertEquals(500L, cues[0].startMs)
        assertEquals(2000L, cues[0].endMs)
        assertEquals("带 BOM 的字幕", cues[0].text)
    }

    // 3) CRLF 换行
    @Test
    fun parse_crlfLineEndings_returnsOk() {
        val srt = "1\r\n" +
            "00:00:01,000 --> 00:00:02,000\r\n" +
            "CRLF 字幕\r\n" +
            "\r\n" +
            "2\r\n" +
            "00:00:03,000 --> 00:00:04,000\r\n" +
            "第二段"

        val cues = okOf(F096SrtParser.parse(srt)).cues

        assertEquals(2, cues.size)
        assertEquals("CRLF 字幕", cues[0].text)
        assertEquals(3000L, cues[1].startMs)
        assertEquals(4000L, cues[1].endMs)
        assertEquals("第二段", cues[1].text)
    }

    // 4) 空文本 → Fail(0, ...)
    @Test
    fun parse_emptyText_returnsFailAtLineZero() {
        val fail = failOf(F096SrtParser.parse(""))
        assertEquals(0, fail.line)
        assertTrue("reason 不应为空", fail.reason.isNotEmpty())

        // 只有 BOM / 纯空白同样视为空文件
        val blank = failOf(F096SrtParser.parse("\uFEFF \r\n \n"))
        assertEquals(0, blank.line)
    }

    // 5) 坏时间轴 → 断言行号（1 基）
    @Test
    fun parse_badTimeline_reportsFailingLineNumber() {
        // 第 1 行序号，第 2 行时间轴损坏
        val single = "1\n00:0X:01,000 --> 00:00:03,500\n坏时间轴\n"
        val firstFail = failOf(F096SrtParser.parse(single))
        assertEquals(2, firstFail.line)
        assertTrue(firstFail.reason.isNotEmpty())

        // 坏时间轴出现在第二段：1 序号 / 2 时间轴 / 3 文本 / 4 空行 / 5 序号 / 6 坏时间轴
        val second = "1\n" +
            "00:00:01,000 --> 00:00:02,000\n" +
            "正常段\n" +
            "\n" +
            "2\n" +
            "00:00:0A,000 --> 00:00:04,000\n" +
            "坏段\n"
        val midFail = failOf(F096SrtParser.parse(second))
        assertEquals(6, midFail.line)
    }

    // 6) end < start → Fail
    @Test
    fun parse_endBeforeStart_returnsFail() {
        val srt = "1\n" +
            "00:00:05,000 --> 00:00:02,000\n" +
            "时间倒流\n"

        val fail = failOf(F096SrtParser.parse(srt))

        assertEquals(2, fail.line)
        assertTrue("reason 应说明时间顺序问题", fail.reason.isNotEmpty())
    }

    // 7) 多行字幕文本保留换行
    @Test
    fun parse_multilineText_keepsNewlines() {
        val srt = "1\n" +
            "00:00:01,000 --> 00:00:03,000\n" +
            "第一行\n" +
            "第二行\n" +
            "第三行\n" +
            "\n" +
            "2\n" +
            "00:00:04,000 --> 00:00:05,000\n" +
            "单行\n"

        val cues = okOf(F096SrtParser.parse(srt)).cues

        assertEquals(2, cues.size)
        assertEquals("第一行\n第二行\n第三行", cues[0].text)
        assertEquals(3, cues[0].text.lines().size)
        assertEquals("单行", cues[1].text)
    }

    // 附加：序号行可缺
    @Test
    fun parse_missingIndexLine_returnsOk() {
        val srt = "00:00:01,000 --> 00:00:02,000\n" +
            "没有序号\n" +
            "\n" +
            "00:00:03,000 --> 00:00:04,000\n" +
            "同样没有序号\n"

        val cues = okOf(F096SrtParser.parse(srt)).cues

        assertEquals(2, cues.size)
        assertEquals("没有序号", cues[0].text)
        assertEquals(3000L, cues[1].startMs)
        assertEquals("同样没有序号", cues[1].text)
    }

    // 附加："." 毫秒分隔符
    @Test
    fun parse_dotMillisecondSeparator_returnsOk() {
        val srt = "1\n" +
            "00:00:01.250 --> 00:00:02.750\n" +
            "点分隔\n"

        val cues = okOf(F096SrtParser.parse(srt)).cues

        assertEquals(1, cues.size)
        assertEquals(1250L, cues[0].startMs)
        assertEquals(2750L, cues[0].endMs)
        assertEquals("点分隔", cues[0].text)
    }
}
