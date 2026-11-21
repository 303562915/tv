package com.tv.ball.presentation.shortdrama

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.tv.ball.databinding.FragmentShortDramaBinding
import kotlinx.coroutines.launch

class ShortDramaFragment : Fragment() {
    
    private var _binding: FragmentShortDramaBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ShortDramaViewModel by lazy {
        ViewModelProvider(this)[ShortDramaViewModel::class.java]
    }
    private lateinit var adapter: ShortDramaAdapter
    
    // 分类由数据源提供，和列表页共用同一套词表
    private val categories = ShortDramaRepository.tabs.map { it.label }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShortDramaBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
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
            val intent = Intent(requireContext(), ShortDramaDetailActivity::class.java)
            intent.putExtra("BOOK_ID", drama.bookId)
            intent.putExtra("TITLE", drama.title)
            intent.putExtra("COVER", drama.cover)
            startActivity(intent)
        }
        
        val layoutManager = GridLayoutManager(requireContext(), 3)
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.dramas.collect { dramas ->
                        adapter.submitList(dramas)
                        binding.emptyView.visibility = if (dramas.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.swipeRefresh.isRefreshing = isLoading
                    }
                }

                launch {
                    viewModel.error.collect { error ->
                        error?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
