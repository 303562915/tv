package com.tv.ball.presentation.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.ball.databinding.ActivityVideoFlowLogBinding
import com.tv.ball.databinding.ItemLogEntryBinding
import com.tv.ball.databinding.ItemLogSessionBinding
import com.tv.ball.util.VideoFlowLogger

/**
 * 视频流程日志查看界面
 */
class VideoFlowLogActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityVideoFlowLogBinding
    private lateinit var adapter: LogSessionAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoFlowLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        loadLogs()
    }
    
    override fun onResume() {
        super.onResume()
        loadLogs()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "视频流程日志"
        }
        
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // 清除所有日志按钮
        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清除日志")
                .setMessage("确定要清除所有日志吗？")
                .setPositiveButton("确定") { _, _ ->
                    VideoFlowLogger.clearAll()
                    Toast.makeText(this, "已清除所有日志", Toast.LENGTH_SHORT).show()
                    loadLogs()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    private fun setupRecyclerView() {
        adapter = LogSessionAdapter(
            onCopyClick = { session ->
                VideoFlowLogger.copyToClipboard(this, session)
                Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@VideoFlowLogActivity)
            this.adapter = this@VideoFlowLogActivity.adapter
        }
    }
    
    private fun loadLogs() {
        val sessions = mutableListOf<VideoFlowLogger.LogSession>()
        
        // 当前会话放在最前面
        VideoFlowLogger.currentSession.value?.let { sessions.add(it) }
        
        // 添加历史会话
        VideoFlowLogger.allSessions.value
            .filter { it.sessionId != VideoFlowLogger.currentSession.value?.sessionId }
            .let { sessions.addAll(it) }
        
        adapter.submitList(sessions)
        
        // 显示/隐藏空状态
        binding.emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
    }
}

/**
 * 日志会话适配器
 */
class LogSessionAdapter(
    private val onCopyClick: (VideoFlowLogger.LogSession) -> Unit
) : RecyclerView.Adapter<LogSessionAdapter.SessionViewHolder>() {
    
    private var sessions = listOf<VideoFlowLogger.LogSession>()
    
    fun submitList(newSessions: List<VideoFlowLogger.LogSession>) {
        sessions = newSessions
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemLogSessionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionViewHolder(binding, onCopyClick)
    }
    
    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }
    
    override fun getItemCount() = sessions.size
    
    class SessionViewHolder(
        private val binding: ItemLogSessionBinding,
        private val onCopyClick: (VideoFlowLogger.LogSession) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var isExpanded = false
        private lateinit var session: VideoFlowLogger.LogSession
        private val logAdapter = LogEntryAdapter()
        
        init {
            binding.logsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = logAdapter
            }
            
            // 点击标题展开/收起
            binding.headerLayout.setOnClickListener {
                isExpanded = !isExpanded
                updateExpandState()
            }
            
            // 长按复制
            binding.root.setOnLongClickListener {
                onCopyClick(session)
                true
            }
            
            // 复制按钮
            binding.btnCopy.setOnClickListener {
                onCopyClick(session)
            }
        }
        
        fun bind(session: VideoFlowLogger.LogSession) {
            this.session = session
            
            binding.tvTitle.text = session.videoTitle ?: "未命名会话"
            binding.tvTime.text = java.text.SimpleDateFormat(
                "MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date(session.startTime))
            binding.tvLogCount.text = "${session.logs.size} 条日志"
            
            // 显示URL（如果有）
            session.videoUrl?.let {
                binding.tvUrl.visibility = View.VISIBLE
                binding.tvUrl.text = it
            } ?: run {
                binding.tvUrl.visibility = View.GONE
            }
            
            // 更新日志列表
            logAdapter.submitList(session.logs)
            
            // 恢复展开状态
            updateExpandState()
        }
        
        private fun updateExpandState() {
            binding.logsRecyclerView.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.ivExpand.rotation = if (isExpanded) 180f else 0f
        }
    }
}

/**
 * 日志条目适配器
 */
class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.LogViewHolder>() {
    
    private var logs = listOf<VideoFlowLogger.LogEntry>()
    
    fun submitList(newLogs: List<VideoFlowLogger.LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }
    
    override fun getItemCount() = logs.size
    
    class LogViewHolder(
        private val binding: ItemLogEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(entry: VideoFlowLogger.LogEntry) {
            binding.tvTime.text = entry.getFormattedTime()
            binding.tvStage.text = entry.stage.displayName
            binding.tvMessage.text = entry.message
            
            // 根据日志级别设置颜色
            val color = when (entry.level) {
                VideoFlowLogger.LogLevel.DEBUG -> android.graphics.Color.GRAY
                VideoFlowLogger.LogLevel.INFO -> android.graphics.Color.BLACK
                VideoFlowLogger.LogLevel.WARNING -> android.graphics.Color.rgb(255, 152, 0)
                VideoFlowLogger.LogLevel.ERROR -> android.graphics.Color.RED
                VideoFlowLogger.LogLevel.SUCCESS -> android.graphics.Color.rgb(76, 175, 80)
            }
            binding.tvLevel.setTextColor(color)
            binding.tvLevel.text = entry.level.name
            
            // 显示详情（如果有）
            if (entry.details != null || entry.error != null) {
                binding.tvDetails.visibility = View.VISIBLE
                val detailsText = buildString {
                    entry.details?.let { append(it) }
                    entry.error?.let {
                        if (isNotEmpty()) append("\n\n")
                        append("错误: ${it.message}")
                    }
                }
                binding.tvDetails.text = detailsText
            } else {
                binding.tvDetails.visibility = View.GONE
            }
        }
    }
}
