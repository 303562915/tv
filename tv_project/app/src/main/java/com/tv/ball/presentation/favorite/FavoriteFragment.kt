package com.tv.ball.presentation.favorite

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.tv.ball.databinding.FragmentFavoriteBinding
import com.tv.ball.presentation.detail.VideoDetailActivity
import com.tv.ball.presentation.player.VideoPlayerActivity
import kotlinx.coroutines.launch

class FavoriteFragment : Fragment() {
    
    private var _binding: FragmentFavoriteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FavoriteViewModel by lazy {
        ViewModelProvider(this)[FavoriteViewModel::class.java]
    }
    private lateinit var adapter: FavoriteAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoriteBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeToDelete()
        setupClearButton()
        observeViewModel()
        viewModel.loadFavorites()
    }
    
    private fun setupRecyclerView() {
        adapter = FavoriteAdapter(
            onItemClick = { favoriteItem ->
                // 如果有播放列表，直接跳转到播放器
                if (!favoriteItem.episodesJson.isNullOrEmpty()) {
                    val intent = Intent(requireContext(), com.tv.ball.presentation.player.VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_ID", favoriteItem.videoId)
                        putExtra("VIDEO_TITLE", favoriteItem.title)
                        putExtra("VIDEO_URL", favoriteItem.detailUrl)
                        putExtra("THUMBNAIL_URL", favoriteItem.thumbnailUrl)
                        putExtra("DETAIL_URL", favoriteItem.detailUrl)
                        putExtra("EPISODES_JSON", favoriteItem.episodesJson)
                        putExtra("START_INDEX", 0)
                        // 传递缓存的解析URL和请求头
                        favoriteItem.parsedVideoUrl?.let { putExtra("PARSED_VIDEO_URL", it) }
                        favoriteItem.videoHeaders?.let { putExtra("VIDEO_HEADERS", it) }
                    }
                    startActivity(intent)
                } else {
                    // 没有播放列表，跳转到详情页重新加载
                    val intent = Intent(requireContext(), VideoDetailActivity::class.java).apply {
                        putExtra("DETAIL_URL", favoriteItem.detailUrl)
                        putExtra("COVER_URL", favoriteItem.thumbnailUrl)
                    }
                    startActivity(intent)
                }
            },
            onItemDelete = { favoriteItem ->
                deleteFavoriteItem(favoriteItem)
            }
        )
        
        binding.rvFavorite.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@FavoriteFragment.adapter
        }
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
                val favorite = adapter.currentList[position]
                deleteFavoriteItem(favorite)
            }
        })
        
        itemTouchHelper.attachToRecyclerView(binding.rvFavorite)
    }
    
    private fun setupClearButton() {
        binding.btnClearAll.setOnClickListener {
            if (adapter.currentList.isEmpty()) {
                Snackbar.make(binding.root, "暂无收藏", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("清空收藏")
                .setMessage("确定要清空所有收藏吗？")
                .setPositiveButton("确定") { _, _ ->
                    viewModel.clearAllFavorites()
                    Snackbar.make(binding.root, "已清空收藏", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    private fun deleteFavoriteItem(favoriteItem: FavoriteItem) {
        viewModel.deleteFavorite(favoriteItem.videoId)
        Snackbar.make(binding.root, "已取消收藏", Snackbar.LENGTH_SHORT)
            .setAction("撤销") {
                // 撤销功能可以在后续版本中实现
            }
            .show()
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.favoriteList.collect { favorites ->
                adapter.submitList(favorites)
                binding.tvEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
                binding.btnClearAll.isEnabled = favorites.isNotEmpty()
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
