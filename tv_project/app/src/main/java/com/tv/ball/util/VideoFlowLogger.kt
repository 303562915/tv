package com.tv.ball.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * 视频流程日志管理器
 * 记录从嗅探到播放的完整流程
 */
object VideoFlowLogger {
    private const val TAG = "VideoFlowLogger"
    
    // 日志条目
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val stage: FlowStage,
        val message: String,
        val details: String? = null,
        val error: Throwable? = null
    ) {
        fun getFormattedTime(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
        
        fun getFullLog(): String {
            val sb = StringBuilder()
            sb.append("[${getFormattedTime()}] [${level.name}] [${stage.displayName}]\n")
            sb.append(message)
            if (details != null) {
                sb.append("\n详情: $details")
            }
            if (error != null) {
                sb.append("\n错误: ${error.message}\n${error.stackTraceToString()}")
            }
            return sb.toString()
        }
    }
    
    // 日志级别
    enum class LogLevel {
        DEBUG, INFO, WARNING, ERROR, SUCCESS
    }
    
    // 流程阶段
    enum class FlowStage(val displayName: String) {
        INIT("初始化"),
        SNIFF("资源嗅探"),
        PARSE("链接解析"),
        NETWORK("网络请求"),
        PLAYER_INIT("播放器初始化"),
        PLAYER_LOAD("加载视频"),
        PLAYER_PLAY("开始播放"),
        PLAYER_ERROR("播放错误"),
        DOWNLOAD("下载"),
        OTHER("其他")
    }
    
    // 日志会话
    data class LogSession(
        val sessionId: String = UUID.randomUUID().toString(),
        val startTime: Long = System.currentTimeMillis(),
        val videoTitle: String? = null,
        val videoUrl: String? = null,
        val logs: MutableList<LogEntry> = mutableListOf()
    )
    
    private val _currentSession = MutableStateFlow<LogSession?>(null)
    val currentSession: StateFlow<LogSession?> = _currentSession.asStateFlow()
    
    private val _allSessions = MutableStateFlow<List<LogSession>>(emptyList())
    val allSessions: StateFlow<List<LogSession>> = _allSessions.asStateFlow()
    
    private val maxSessionsToKeep = 10
    
    /**
     * 开始新的日志会话
     */
    fun startSession(videoTitle: String? = null, videoUrl: String? = null) {
        val newSession = LogSession(
            videoTitle = videoTitle,
            videoUrl = videoUrl
        )
        _currentSession.value = newSession
        
        log(
            LogLevel.INFO,
            FlowStage.INIT,
            "开始新的视频流程",
            "标题: ${videoTitle ?: "未知"}\nURL: ${videoUrl ?: "未知"}"
        )
    }
    
    /**
     * 结束当前会话
     */
    fun endSession() {
        _currentSession.value?.let { session ->
            log(
                LogLevel.INFO,
                FlowStage.OTHER,
                "流程结束",
                "总计 ${session.logs.size} 条日志"
            )
            
            // 保存到历史会话
            val updatedSessions = (_allSessions.value + session)
                .takeLast(maxSessionsToKeep)
            _allSessions.value = updatedSessions
        }
        _currentSession.value = null
    }
    
    /**
     * 记录日志
     */
    fun log(
        level: LogLevel,
        stage: FlowStage,
        message: String,
        details: String? = null,
        error: Throwable? = null
    ) {
        val entry = LogEntry(
            level = level,
            stage = stage,
            message = message,
            details = details,
            error = error
        )
        
        // 添加到当前会话
        _currentSession.value?.let { session ->
            session.logs.add(entry)
            _currentSession.value = session.copy() // 触发更新
        }
        
        // 同时输出到 Logcat
        val logMessage = "[${stage.displayName}] $message ${details ?: ""}"
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, logMessage, error)
            LogLevel.INFO -> Log.i(TAG, logMessage, error)
            LogLevel.WARNING -> Log.w(TAG, logMessage, error)
            LogLevel.ERROR -> Log.e(TAG, logMessage, error)
            LogLevel.SUCCESS -> Log.i(TAG, "✓ $logMessage", error)
        }
    }
    
    // 便捷方法
    fun debug(stage: FlowStage, message: String, details: String? = null) {
        log(LogLevel.DEBUG, stage, message, details)
    }
    
    fun info(stage: FlowStage, message: String, details: String? = null) {
        log(LogLevel.INFO, stage, message, details)
    }
    
    fun warning(stage: FlowStage, message: String, details: String? = null) {
        log(LogLevel.WARNING, stage, message, details)
    }
    
    fun error(stage: FlowStage, message: String, details: String? = null, error: Throwable? = null) {
        log(LogLevel.ERROR, stage, message, details, error)
    }
    
    fun success(stage: FlowStage, message: String, details: String? = null) {
        log(LogLevel.SUCCESS, stage, message, details)
    }
    
    /**
     * 获取当前会话的完整日志文本
     */
    fun getSessionLogText(session: LogSession = _currentSession.value ?: LogSession()): String {
        val sb = StringBuilder()
        sb.append("==================================================\n")
        sb.append("视频流程日志\n")
        sb.append("==================================================\n")
        sb.append("会话ID: ${session.sessionId}\n")
        sb.append("开始时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(session.startTime))}\n")
        session.videoTitle?.let { sb.append("视频标题: $it\n") }
        session.videoUrl?.let { sb.append("视频URL: $it\n") }
        sb.append("==================================================\n\n")

        session.logs.forEach { entry ->
            sb.append(entry.getFullLog())
            sb.append("\n--------------------------------------------------\n")
        }

        return sb.toString()
    }
    
    /**
     * 复制日志到剪贴板
     */
    fun copyToClipboard(context: Context, session: LogSession? = _currentSession.value) {
        session?.let {
            val logText = getSessionLogText(it)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("视频流程日志", logText)
            clipboard.setPrimaryClip(clip)
        }
    }
    
    /**
     * 清除所有日志
     */
    fun clearAll() {
        _currentSession.value = null
        _allSessions.value = emptyList()
    }
}
