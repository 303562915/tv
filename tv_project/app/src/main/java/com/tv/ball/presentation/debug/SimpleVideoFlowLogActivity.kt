package com.tv.ball.presentation.debug

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.util.VideoFlowLogger
import com.tv.ball.util.VideoFlowLoggerTest

/**
 * 简化版视频流程日志界面
 */
class SimpleVideoFlowLogActivity : AppCompatActivity() {
    
    private lateinit var tvContent: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_video_flow_log)
        
        tvContent = findViewById(R.id.tv_content)
        val btnClose = findViewById<Button>(R.id.btn_close)
        val btnCopy = findViewById<Button>(R.id.btn_copy)
        val btnClear = findViewById<Button>(R.id.btn_clear)
        val btnRefresh = findViewById<Button>(R.id.btn_refresh)
        
        btnClose.setOnClickListener {
            finish()
        }
        
        btnCopy.setOnClickListener {
            copyAllLogs()
        }
        
        btnClear.setOnClickListener {
            clearAllLogs()
        }
        
        btnRefresh.setOnClickListener {
            loadLogs()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }
        
        loadLogs()
    }
    
    private fun clearAllLogs() {
        try {
            VideoFlowLogger.clearAll()
            loadLogs()
            Toast.makeText(this, "已清空所有日志", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun copyAllLogs() {
        try {
            val sessions = mutableListOf<VideoFlowLogger.LogSession>()
            VideoFlowLogger.currentSession.value?.let { sessions.add(it) }
            sessions.addAll(VideoFlowLogger.allSessions.value)
            
            if (sessions.isEmpty()) {
                Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 复制最新的会话
            val latestSession = sessions.first()
            VideoFlowLogger.copyToClipboard(this, latestSession)
            Toast.makeText(this, "最新日志已复制到剪贴板", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun loadLogs() {
        try {
            val sessions = mutableListOf<VideoFlowLogger.LogSession>()
            
            // 获取当前会话
            VideoFlowLogger.currentSession.value?.let { sessions.add(it) }
            
            // 获取历史会话
            sessions.addAll(VideoFlowLogger.allSessions.value)
            
            if (sessions.isEmpty()) {
                tvContent.text = "暂无日志\n\n播放视频后会自动记录流程日志"
            } else {
                val logText = StringBuilder()
                sessions.forEach { session ->
                    logText.append("========================================\n")
                    logText.append("视频: ${session.videoTitle ?: "未知"}\n")
                    logText.append("时间: ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(session.startTime))}\n")
                    logText.append("日志数: ${session.logs.size}\n")
                    logText.append("========================================\n\n")
                    
                    session.logs.take(10).forEach { log ->
                        logText.append("[${log.getFormattedTime()}] ")
                        logText.append("[${log.level.name}] ")
                        logText.append("[${log.stage.displayName}]\n")
                        logText.append("${log.message}\n")
                        log.details?.let { logText.append("详情: $it\n") }
                        logText.append("\n")
                    }
                    
                    if (session.logs.size > 10) {
                        logText.append("... 还有 ${session.logs.size - 10} 条日志\n\n")
                    }
                }
                
                tvContent.text = logText.toString()
            }
        } catch (e: Exception) {
            tvContent.text = "加载日志失败: ${e.message}\n\n${e.stackTraceToString()}"
        }
    }
}
