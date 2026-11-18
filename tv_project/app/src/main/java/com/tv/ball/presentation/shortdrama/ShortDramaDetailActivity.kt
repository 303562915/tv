package com.tv.ball.presentation.shortdrama

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.tv.ball.R
import com.tv.ball.util.ImageLoader
import com.tv.ball.databinding.ActivityShortDramaDetailBinding
import com.tv.ball.presentation.player.VideoPlayerActivity
import kotlinx.coroutines.launch

class ShortDramaDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityShortDramaDetailBinding
    private lateinit var viewModel: ShortDramaDetailViewModel
    private lateinit var episodeAdapter: EpisodeAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShortDramaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        viewModel = ViewModelProvider(this)[ShortDramaDetailViewModel::class.java]
        
        val bookId = intent.getStringExtra("BOOK_ID") ?: return finish()
        val title = intent.getStringExtra("TITLE") ?: ""
        val cover = intent.getStringExtra("COVER") ?: ""
        
        setupUI(title, cover)
        setupRecyclerView()
        observeViewModel()
        
        viewModel.loadDetail(bookId)
    }
    
    private fun setupUI(title: String, cover: String) {
        binding.tvTitle.text = title
        
        ImageLoader.load(
            context = this,
            url = cover,
            imageView = binding.ivCover,
            placeholder = R.drawable.ic_placeholder
        )
    }
    
    private fun setupRecyclerView() {
        episodeAdapter = EpisodeAdapter { episode ->
            playEpisode(episode)
        }
        
        // 使用6列网格布局，和播放器保持一致
        binding.recyclerView.layoutManager = GridLayoutManager(this, 6)
        binding.recyclerView.adapter = episodeAdapter
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.detail.collect { detail ->
                detail?.let {
                    binding.tvAuthor.text = "作者: ${it.author}"
                    binding.tvType.text = it.type
                    binding.tvPlayCount.text = "${formatPlayCount(it.playCnt)}播放"
                    binding.tvEpisodeCount.text = "共${it.episodeCnt}集"
                    binding.tvIntro.text = it.intro
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.episodes.collect { episodes ->
                episodeAdapter.submitList(episodes)
                binding.emptyView.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
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
                    Toast.makeText(this@ShortDramaDetailActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun playEpisode(episode: EpisodeItem) {
        // 显示加载提示
        Toast.makeText(this, "正在获取播放地址...", Toast.LENGTH_SHORT).show()
        
        // 获取视频播放地址
        viewModel.getVideoUrl(episode.videoId) { videoUrl ->
            if (videoUrl.isNotEmpty()) {
                // 构建剧集列表 JSON
                val episodesJson = buildEpisodesJson()
                val currentIndex = viewModel.episodes.value.indexOf(episode)
                
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", System.currentTimeMillis())
                    putExtra("VIDEO_URL", videoUrl)
                    putExtra("VIDEO_TITLE", binding.tvTitle.text.toString())
                    putExtra("THUMBNAIL_URL", intent.getStringExtra("COVER"))
                    putExtra("EPISODES_JSON", episodesJson)
                    putExtra("START_INDEX", currentIndex)
                    putExtra("CURRENT_EPISODE", "第${episode.episodeNum}集")
                    putExtra("IS_SHORT_DRAMA", true)  // 标记为短剧
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "获取播放地址失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 构建剧集列表 JSON
     */
    private fun buildEpisodesJson(): String {
        val episodes = viewModel.episodes.value
        val jsonBuilder = StringBuilder("[")
        
        android.util.Log.d("ShortDramaDetail", "构建剧集列表，共 ${episodes.size} 集")
        
        episodes.forEachIndexed { index, episode ->
            if (index > 0) jsonBuilder.append(",")
            jsonBuilder.append("{")
            jsonBuilder.append("\"name\":\"第${episode.episodeNum}集\",")
            jsonBuilder.append("\"videoId\":\"${episode.videoId}\",")
            jsonBuilder.append("\"url\":\"\"")  // URL 需要动态获取
            jsonBuilder.append("}")
            
            android.util.Log.d("ShortDramaDetail", "剧集[$index]: 第${episode.episodeNum}集, videoId=${episode.videoId}")
        }
        
        jsonBuilder.append("]")
        val result = jsonBuilder.toString()
        android.util.Log.d("ShortDramaDetail", "生成的JSON: $result")
        return result
    }
    
    private fun formatPlayCount(count: String): String {
        return try {
            val num = count.toLong()
            when {
                num >= 100000000 -> String.format("%.1f亿", num / 100000000.0)
                num >= 10000 -> String.format("%.1f万", num / 10000.0)
                else -> count
            }
        } catch (e: Exception) {
            count
        }
    }
}
