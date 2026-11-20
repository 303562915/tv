package com.tv.ball.presentation.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.tv.ball.databinding.FragmentHistoryBinding
import com.tv.ball.presentation.detail.VideoDetailActivity
import com.tv.ball.presentation.home.VideoItem
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {
    
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HistoryViewModel by lazy {
        ViewModelProvider(this)[HistoryViewModel::class.java]
    }
    private lateinit var adapter: HistoryAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeToDelete()
        setupClearButton()
        observeViewModel()
        viewModel.loadHistory()
    }
    
    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { historyItem ->
                // 如果有播放列表，直接跳转到播放器
                if (!historyItem.episodesJson.isNullOrEmpty()) {
                    // 解析播放列表，找到当前集数的索引
                    val startIndex = try {
                        val episodes = parseEpisodesFromJson(historyItem.episodesJson)
                        episodes.indexOfFirst { it.name == historyItem.currentEpisode }.takeIf { it >= 0 } ?: 0
                    } catch (e: Exception) {
                        0
                    }
                    
                    val intent = Intent(requireContext(), com.tv.ball.presentation.player.VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_ID", System.currentTimeMillis())
                        putExtra("VIDEO_TITLE", historyItem.videoItem.title)
                        putExtra("VIDEO_URL", historyItem.videoItem.detailUrl)
                        putExtra("THUMBNAIL_URL", historyItem.videoItem.coverUrl)
                        putExtra("DETAIL_URL", historyItem.detailUrl)
                        putExtra("EPISODES_JSON", historyItem.episodesJson)
                        putExtra("SEEK_POSITION", historyItem.watchedPosition)
                        
                        // 传递缓存的解析URL、请求头和对应的集数名称
                        historyItem.parsedVideoUrl?.let { putExtra("PARSED_VIDEO_URL", it) }
                        historyItem.videoHeaders?.let { putExtra("VIDEO_HEADERS", it) }
                        historyItem.currentEpisode?.let { putExtra("CACHED_EPISODE_NAME", it) }
                        
                        // 从上次观看的集数开始播放
                        putExtra("START_INDEX", startIndex)
                    }
                    startActivity(intent)
                } else {
                    // 没有播放列表，跳转到详情页重新加载
                    val intent = Intent(requireContext(), com.tv.ball.presentation.detail.VideoDetailActivity::class.java).apply {
                        putExtra("DETAIL_URL", historyItem.detailUrl ?: historyItem.videoItem.detailUrl)
                        putExtra("COVER_URL", historyItem.videoItem.coverUrl)
                    }
                    startActivity(intent)
                }
            },
            onItemDelete = { historyItem ->
                deleteHistoryItem(historyItem)
            }
        )
        
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }
    }
    
    private fun parseEpisodesFromJson(json: String): List<com.tv.ball.domain.model.Episode> {
        val episodes = mutableListOf<com.tv.ball.domain.model.Episode>()
        try {
            val content = json.trim().removePrefix("[").removeSuffix("]")
            val items = content.split("},")
            
            for (item in items) {
                val cleanItem = item.trim().removePrefix("{").removeSuffix("}")
                val nameMatch = """"name"\s*:\s*"([^"]+)"""".toRegex().find(cleanItem)
                val urlMatch = """"url"\s*:\s*"([^"]+)"""".toRegex().find(cleanItem)
                
                if (nameMatch != null && urlMatch != null) {
                    episodes.add(
                        com.tv.ball.domain.model.Episode(
                            name = nameMatch.groupValues[1],
                            url = urlMatch.groupValues[1]
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HistoryFragment", "解析播放列表失败", e)
        }
        return episodes
    }
    
    private fun setupSwipeToDelete() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val video = adapter.currentList[position]
                deleteHistoryItem(video)
            }
        })
        
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)
    }
    
    private fun setupClearButton() {
        // 清空按钮：primary 文字由布局 token 提供，这里只挂点击

        binding.btnClearAll.setOnClickListener {
            if (adapter.currentList.isEmpty()) {
                Snackbar.make(binding.root, "暂无历史记录", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(requireContext())
                .setTitle("清空历史")
                .setMessage("确定要清空所有观看历史吗？")
                .setPositiveButton("确定") { _, _ ->
                    viewModel.clearAllHistory()
                    Snackbar.make(binding.root, "已清空历史记录", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    private fun deleteHistoryItem(historyItem: HistoryItem) {
        viewModel.deleteHistory(historyItem.videoItem.title)

        Snackbar.make(binding.root, "已删除", Snackbar.LENGTH_LONG)
            .setAction("撤销") {
                // 重新添加历史记录
                viewModel.addHistory(historyItem)
            }
            .show()
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.historyList.collect { history ->
                adapter.submitList(history)
                binding.tvEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
                binding.btnClearAll.isEnabled = history.isNotEmpty()
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
