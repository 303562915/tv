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
import org.json.JSONObject

class ShortDramaDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityShortDramaDetailBinding
    private lateinit var viewModel: ShortDramaDetailViewModel
    private lateinit var episodeAdapter: EpisodeAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式与其它页一致：必须在 super.onCreate 之前应用
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityShortDramaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

        // 详情页顶部是折叠 Toolbar：给它一个返回箭头，否则只能靠系统返回键
        binding.toolbar.setNavigationIcon(com.tv.ball.R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
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
                    binding.tvAuthor.text = "演员: ${it.author}"
                    binding.tvType.text = it.type
                    binding.tvPlayCount.text = if (it.playCnt == "0" || it.playCnt.isEmpty()) "" else "${formatPlayCount(it.playCnt)}播放"
                    binding.tvEpisodeCount.text = if (it.episodeCnt.isNotEmpty()) "共${it.episodeCnt}集" else ""
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
        // 详情接口已经把 m3u8 直链带回来了，直接用；不再「点一集再问接口要地址」
        val videoUrl = episode.videoUrl.ifEmpty { ShortDramaRepository.resolvePlayUrl(episode.videoId) }
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "这一集没有可用的播放地址", Toast.LENGTH_SHORT).show()
            return
        }

        // 构建剧集列表 JSON
        val episodesJson = buildEpisodesJson()
        val currentIndex = viewModel.episodes.value.indexOf(episode)

        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", System.currentTimeMillis())
            putExtra("VIDEO_URL", videoUrl)
            putExtra("VIDEO_TITLE", binding.tvTitle.text.toString())
            putExtra("THUMBNAIL_URL", getIntent().getStringExtra("COVER"))
            putExtra("EPISODES_JSON", episodesJson)
            putExtra("START_INDEX", currentIndex)
            putExtra("CURRENT_EPISODE", "第${episode.episodeNum}集")
            putExtra("IS_SHORT_DRAMA", true)  // 标记为短剧
        }
        startActivity(intent)
    }

    /**
     * 构建剧集列表 JSON（url 直接写直链，播放器切集不必再联网解析）
     */
    private fun buildEpisodesJson(): String {
        val episodes = viewModel.episodes.value
        val jsonBuilder = StringBuilder("[")

        episodes.forEachIndexed { index, episode ->
            if (index > 0) jsonBuilder.append(",")
            jsonBuilder.append("{")
            jsonBuilder.append("\"name\":").append(JSONObject.quote("第${episode.episodeNum}集")).append(",")
            jsonBuilder.append("\"videoId\":").append(JSONObject.quote(episode.videoId)).append(",")
            jsonBuilder.append("\"url\":").append(JSONObject.quote(episode.videoUrl))
            jsonBuilder.append("}")
        }

        jsonBuilder.append("]")
        return jsonBuilder.toString()
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
