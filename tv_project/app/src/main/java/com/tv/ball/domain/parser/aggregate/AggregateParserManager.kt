package com.tv.ball.domain.parser.aggregate

import android.util.Log

/**
 * 聚合解析器管理器
 */
class AggregateParserManager {

    companion object {
        private const val TAG = "AggregateParser"
    }

    private val parsers = mutableListOf<PlatformParser>()

    init {
        // 注册各平台解析器
        registerParser(DouyinParser())
        registerParser(KuaishouParser())
        registerParser(BilibiliParser())
    }

    /**
     * 注册解析器
     */
    fun registerParser(parser: PlatformParser) {
        parsers.add(parser)
    }

    /**
     * 识别平台
     */
    fun detectPlatform(url: String): Platform? {
        return Platform.detectPlatform(url)
    }

    /**
     * 解析视频
     */
    suspend fun parse(url: String): ParsedVideo? {
        Log.d(TAG, "开始解析: $url")

        // 查找支持的解析器
        val parser = parsers.find { it.canParse(url) }
        if (parser == null) {
            Log.w(TAG, "未找到支持的解析器")
            return null
        }

        return try {
            Log.d(TAG, "使用解析器: ${parser.javaClass.simpleName}")
            parser.parse(url)
        } catch (e: Exception) {
            Log.e(TAG, "解析失败: ${e.message}", e)
            null
        }
    }
}
