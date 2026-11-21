package com.tv.ball.presentation.shortdrama

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.tv.ball.databinding.ActivityShortDramaBinding
import kotlinx.coroutines.launch

class ShortDramaActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityShortDramaBinding
    private val viewModel: ShortDramaViewModel by lazy {
        ViewModelProvider(this)[ShortDramaViewModel::class.java]
    }
    private lateinit var adapter: ShortDramaAdapter
    
    private val categories = listOf(
        "霸总", "逆袭", "现代言情", "打脸虐渣", "豪门恩怨", 
        "神豪", "马甲", "都市日常", "战神归来", "小人物"
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式与其它页一致：必须在 super.onCreate 之前应用
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityShortDramaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

        binding.btnBack.setOnClickListener { finish() }

        setupTabs()
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
        
        viewModel.loadDramas(categories[0], page = 1, refresh = true)
    }
    
    private fun setupTabs() {
        categories.forEach { category ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText(category)
            )
        }
        
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let {
                    val category = categories[it.position]
                    viewModel.loadDramas(category, page = 1, refresh = true)
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun setupRecyclerView() {
        adapter = ShortDramaAdapter { drama ->
            val intent = Intent(this, ShortDramaDetailActivity::class.java)
            intent.putExtra("BOOK_ID", drama.bookId)
            intent.putExtra("TITLE", drama.title)
            intent.putExtra("COVER", drama.cover)
            startActivity(intent)
        }
        
        val layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                val totalItemCount = layoutManager.itemCount
                
                if (lastVisiblePosition >= totalItemCount - 4 && dy > 0) {
                    viewModel.loadMore()
                }
            }
        })
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val selectedTab = binding.tabLayout.selectedTabPosition
            val category = categories[selectedTab]
            viewModel.loadDramas(category, page = 1, refresh = true)
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.dramas.collect { dramas ->
                Log.d("ShortDramaActivity", "Dramas updated: ${dramas.size} items")
                adapter.submitList(dramas)
                binding.emptyView.visibility = if (dramas.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (dramas.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                Log.d("ShortDramaActivity", "Loading state: $isLoading")
                binding.swipeRefresh.isRefreshing = isLoading
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    Log.e("ShortDramaActivity", "Error: $it")
                    Toast.makeText(this@ShortDramaActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
