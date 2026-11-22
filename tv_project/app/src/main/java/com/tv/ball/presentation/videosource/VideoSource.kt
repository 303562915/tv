package com.tv.ball.presentation.videosource

/**
 * 视频源列表项模型。
 *
 * [responseTime] 与 [failureReason] 是互斥的两种测速结果：
 * 成功时只有 responseTime，失败时只有 failureReason（「超时」/「连接失败」/「HTTP 403」）。
 * 两者都为 null 表示这一轮还在测（界面显示「测试中…」）。
 */
data class VideoSource(
    val name: String,
    val iconUrl: String?,
    val sourceKey: String,
    val responseTime: Int? = null, // 响应时间（毫秒）
    val isSelected: Boolean = false,
    val failureReason: String? = null // 测速失败原因，短暂文案，例如「超时」
)