package com.tv.ball.presentation.detail

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.tv.ball.R
import com.tv.ball.util.ImageLoader
import com.tv.ball.databinding.ActivityVideoDetailBinding
import com.tv.ball.presentation.player.VideoPlayerActivity
import kotlinx.coroutines.launch

class VideoDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityVideoDetailBinding
    private val viewModel: VideoDetailViewModel by lazy {
        ViewModelProvider(this)[VideoDetailViewModel::class.java]
    }
    private lateinit var episodeAdapter: EpisodeAdapter
    private var episodeGroups: List<EpisodeGroup> = emptyList()
    private var originalEpisodes: List<com.tv.ball.domain.model.Episode> = emptyList() // 保存原始剧集列表（带线路前缀）
    private var currentGroupIndex = 0
    
    data class EpisodeGroup(
        val sourceName: String,
        val episodes: List<com.tv.ball.domain.model.Episode>
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置透明沉浸式状态栏
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
        
        val videoIdOrUrl = intent.getStringExtra("DETAIL_URL") 
            ?: intent.getStringExtra("videoUrl") ?: ""
        val coverUrl = intent.getStringExtra("COVER_URL") 
            ?: intent.getStringExtra("coverUrl") ?: ""
        val sourceKey = intent.getStringExtra("SOURCE_KEY") ?: "" // 获取来源信息
        
        setupRecyclerView()
        observeViewModel()
        
        // Load cover image
        ImageLoader.load(
            context = this,
            url = coverUrl,
            imageView = binding.ivCover,
            placeholder = R.drawable.ic_placeholder
        )
        
        // 智能判断URL类型并构建详情URL
        val currentSource = if (sourceKey.isNotEmpty()) {
            // 如果有明确的来源信息，使用该来源
            android.util.Log.d("VideoDetailActivity", "使用指定来源: $sourceKey")
            sourceKey
        } else {
            // 否则使用当前选择的源
            com.tv.ball.data.VideoSourceManager.getCurrentSource()
        }
        
        val detailUrl = if (videoIdOrUrl.startsWith("http")) {
            // 如果已经是完整URL（Web源），直接使用
            android.util.Log.d("VideoDetailActivity", "使用完整URL: $videoIdOrUrl")
            videoIdOrUrl
        } else {
            // 如果是视频ID（API源），需要转换为完整URL
            val url = com.tv.ball.data.VideoSourceManager.getDetailUrl(currentSource, videoIdOrUrl)
            android.util.Log.d("VideoDetailActivity", "从ID构建URL: $videoIdOrUrl -> $url (源: $currentSource)")
            url
        }
        
        // Load video detail
        viewModel.loadVideoDetail(detailUrl)
    }
    
    private fun setupRecyclerView() {
        episodeAdapter = EpisodeAdapter { episode ->
            // 检查是否是网盘资源
            if (episode.isCloudStorage) {
                showCloudStorageDialog(episode)
            } else {
                playEpisode(episode)
            }
        }

        binding.rvEpisodes.apply {
            // 使用固定6列网格布局，和播放器保持一致
            layoutManager = GridLayoutManager(this@VideoDetailActivity, 6)
            adapter = episodeAdapter
        }
        
        // 设置Tab切换监听
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                tab?.position?.let { position ->
                    currentGroupIndex = position
                    showEpisodesForCurrentGroup()
                }
            }
            
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }
    
    /**
     * 显示当前选中线路的剧集
     */
    private fun showEpisodesForCurrentGroup() {
        android.util.Log.d("VideoDetailActivity", "showEpisodesForCurrentGroup: currentGroupIndex=$currentGroupIndex, episodeGroups.size=${episodeGroups.size}")
        if (episodeGroups.isNotEmpty() && currentGroupIndex < episodeGroups.size) {
            val group = episodeGroups[currentGroupIndex]
            android.util.Log.d("VideoDetailActivity", "显示线路: ${group.sourceName}, 剧集数: ${group.episodes.size}")
            group.episodes.forEachIndexed { index, episode ->
                android.util.Log.d("VideoDetailActivity", "  [$index] ${episode.name}")
            }
            episodeAdapter.submitList(group.episodes)
        } else {
            android.util.Log.e("VideoDetailActivity", "无法显示剧集: episodeGroups为空或索引越界")
        }
    }
    
    /**
     * 显示网盘资源处理对话框
     */
    private fun showCloudStorageDialog(episode: com.tv.ball.domain.model.Episode) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_cloud_storage, null)
        
        // 设置标题和链接
        view.findViewById<android.widget.TextView>(R.id.cloudTitle).text = "${episode.cloudType} 网盘资源"
        view.findViewById<android.widget.TextView>(R.id.cloudSubtitle).text = "检测到这是网盘资源，请选择打开方式"
        view.findViewById<android.widget.TextView>(R.id.cloudUrl).text = episode.url
        
        // 设置图标颜色
        val iconColor = when {
            episode.cloudType.contains("UC") -> 0xFFFF9800.toInt()
            episode.cloudType.contains("夸克") -> 0xFF2196F3.toInt()
            episode.cloudType.contains("百度") -> 0xFF4CAF50.toInt()
            episode.cloudType.contains("阿里") -> 0xFFFF5722.toInt()
            else -> 0xFF9E9E9E.toInt()
        }
        view.findViewById<android.widget.ImageView>(R.id.cloudIcon).setColorFilter(iconColor)
        
        // 浏览器打开按钮
        view.findViewById<android.view.View>(R.id.btnOpenBrowser).setOnClickListener {
            openInBrowser(episode.url)
            bottomSheetDialog.dismiss()
        }
        
        // 复制链接按钮
        view.findViewById<android.view.View>(R.id.btnCopyLink).setOnClickListener {
            copyToClipboard(episode.url)
            bottomSheetDialog.dismiss()
        }
        
        // 取消按钮
        view.findViewById<android.view.View>(R.id.btnCancel).setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }
    
    /**
     * 在浏览器中打开
     */
    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(this, com.tv.ball.presentation.webview.WebViewActivity::class.java)
            intent.putExtra("url", url)
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 复制链接到剪贴板
     */
    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("视频链接", url)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(this, "链接已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 选中上次播放的集数
     */
    private fun selectLastPlayedEpisode(videoTitle: String, allEpisodes: List<com.tv.ball.domain.model.Episode>) {
        lifecycleScope.launch {
            try {
                // 从历史记录中获取上次播放的集数
                val historyRepository = com.tv.ball.domain.repository.HistoryRepository(
                    com.tv.ball.data.local.LocalDataSource(
                        this@VideoDetailActivity,
                        com.tv.ball.di.Dependencies.database.videoDao(),
                        com.tv.ball.di.Dependencies.database.historyDao(),
                        com.tv.ball.di.Dependencies.database.playlistDao()
                    )
                )
                
                val history = historyRepository.getHistoryByTitle(videoTitle)
                
                if (history != null && !history.currentEpisode.isNullOrEmpty()) {
                    // 查找匹配的集数（在所有剧集中查找）
                    val matchedEpisode = allEpisodes.find { episode ->
                        episode.name.contains(history.currentEpisode) || 
                        episode.url == history.videoUrl
                    }
                    
                    if (matchedEpisode != null) {
                        // 找到匹配的集数，切换到对应的线路
                        episodeGroups.forEachIndexed { index, group ->
                            if (group.episodes.any { it.url == matchedEpisode.url }) {
                                // 切换到这个线路
                                currentGroupIndex = index
                                binding.tabLayout.getTabAt(index)?.select()
                                showEpisodesForCurrentGroup()
                                
                                // 设置选中状态
                                episodeAdapter.setSelectedEpisode(matchedEpisode)
                                
                                // 滚动到选中的位置
                                val position = group.episodes.indexOfFirst { it.url == matchedEpisode.url }
                                if (position != -1) {
                                    binding.rvEpisodes.scrollToPosition(position)
                                }
                                return@launch
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoDetailActivity", "Failed to select last played episode", e)
            }
        }
    }
    
    /**
     * 播放剧集
     */
    private fun playEpisode(episode: com.tv.ball.domain.model.Episode) {
        // 更新选中状态
        episodeAdapter.setSelectedEpisode(episode)
        
        // 使用原始剧集列表（带线路前缀）来构建JSON和查找索引
        // 需要通过URL匹配找到原始剧集
        val originalEpisode = originalEpisodes.find { it.url == episode.url }
        val currentIndex = originalEpisodes.indexOf(originalEpisode)

        // 构建播放列表JSON（使用原始剧集列表）
        val episodesJson = buildEpisodesJson(originalEpisodes)

        // 获取封面URL和详情URL
        val coverUrl = intent.getStringExtra("COVER_URL")
            ?: intent.getStringExtra("coverUrl") ?: ""
        val detailUrl = intent.getStringExtra("DETAIL_URL")
            ?: intent.getStringExtra("videoUrl") ?: ""

        // Play selected episode
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", System.currentTimeMillis())
            putExtra("VIDEO_TITLE", binding.tvTitle.text.toString())
            putExtra("VIDEO_URL", episode.url)
            putExtra("THUMBNAIL_URL", coverUrl)
            putExtra("DETAIL_URL", detailUrl)
            putExtra("EPISODES_JSON", episodesJson)
            putExtra("START_INDEX", currentIndex)
        }
        startActivity(intent)
    }
    
    private fun buildEpisodesJson(episodes: List<com.tv.ball.domain.model.Episode>): String {
        val jsonBuilder = StringBuilder("[")
        episodes.forEachIndexed { index, episode ->
            if (index > 0) jsonBuilder.append(",")
            jsonBuilder.append("{")
            jsonBuilder.append("\"name\":\"${episode.name}\",")
            jsonBuilder.append("\"url\":\"${episode.url}\"")
            jsonBuilder.append("}")
        }
        jsonBuilder.append("]")
        return jsonBuilder.toString()
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.videoDetail.collect { detail ->
                detail?.let {
                    binding.tvTitle.text = it.title
                    binding.tvDescription.text = it.description
                    
                    // 保存原始剧集列表（带线路前缀）
                    originalEpisodes = it.episodes
                    
                    // 显示年份
                    if (it.year.isNotEmpty()) {
                        binding.tvYear.text = it.year
                        binding.tvYear.visibility = View.VISIBLE
                    } else {
                        binding.tvYear.visibility = View.GONE
                    }
                    
                    // 显示地区
                    if (it.area.isNotEmpty()) {
                        binding.tvArea.text = it.area
                        binding.tvArea.visibility = View.VISIBLE
                    } else {
                        binding.tvArea.visibility = View.GONE
                    }
                    
                    // 显示类型
                    if (it.type.isNotEmpty()) {
                        binding.tvType.text = it.type
                        binding.tvType.visibility = View.VISIBLE
                    } else {
                        binding.tvType.visibility = View.GONE
                    }
                    
                    // 显示导演
                    if (it.director.isNotEmpty()) {
                        binding.tvDirector.text = "导演: ${it.director}"
                        binding.tvDirector.visibility = View.VISIBLE
                    } else {
                        binding.tvDirector.visibility = View.GONE
                    }
                    
                    // 显示主演
                    if (it.actor.isNotEmpty()) {
                        binding.tvActor.text = "主演: ${it.actor}"
                        binding.tvActor.visibility = View.VISIBLE
                    } else {
                        binding.tvActor.visibility = View.GONE
                    }
                    
                    // 按线路分组
                    val grouped = it.episodes.groupBy { episode ->
                        // 从 "[线路1] 第1集" 中提取 "线路1"
                        val match = """\[([^\]]+)\]""".toRegex().find(episode.name)
                        match?.groupValues?.get(1) ?: "默认线路"
                    }.map { (sourceName, episodeList) ->
                        // 移除剧集名称中的线路前缀
                        val cleanedEpisodes = episodeList.map { episode ->
                            val cleanName = episode.name.replace("""\[[^\]]+\]\s*""".toRegex(), "")
                            episode.copy(name = cleanName)
                        }
                        EpisodeGroup(sourceName, cleanedEpisodes)
                    }.sortedByDescending { group ->
                        // 线路排序：优先m3u8线路
                        var score = 0
                        val lowerName = group.sourceName.lowercase()
                        
                        // M3U8线路最高优先级
                        if (lowerName.contains("m3u8")) {
                            score += 1000
                            android.util.Log.d("VideoDetailActivity", "✓✓✓ M3U8线路优先: ${group.sourceName}")
                        }
                        
                        // 高清线路次优先
                        if (lowerName.contains("hd") || lowerName.contains("高清") || 
                            lowerName.contains("1080p") || lowerName.contains("720p")) {
                            score += 500
                        }
                        
                        // MP4线路第三优先
                        if (lowerName.contains("mp4")) {
                            score += 300
                        }
                        
                        score
                    }
                    
                    episodeGroups = grouped
                    
                    // 设置Tab
                    binding.tabLayout.removeAllTabs()
                    grouped.forEach { group ->
                        binding.tabLayout.addTab(
                            binding.tabLayout.newTab().setText(group.sourceName)
                        )
                    }
                    
                    // 显示第一个线路的剧集
                    if (grouped.isNotEmpty()) {
                        currentGroupIndex = 0
                        showEpisodesForCurrentGroup()
                        
                        // 列表提交完成后,查找并选中上次播放的集数
                        selectLastPlayedEpisode(it.title, it.episodes)
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }
        
        lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    // Show error message
                    binding.tvDescription.text = "加载失败: $it"
                }
            }
        }
    }
}
