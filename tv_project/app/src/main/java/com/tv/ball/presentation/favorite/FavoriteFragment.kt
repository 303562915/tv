package com.tv.ball.presentation.favorite

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.tv.ball.R
import com.tv.ball.databinding.FragmentFavoriteBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.presentation.detail.VideoDetailActivity
import com.tv.ball.presentation.player.VideoPlayerActivity
import com.tv.ball.widget.SwipeRevealLayout
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FavoriteFragment : Fragment() {

    private var _binding: FragmentFavoriteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FavoriteViewModel by lazy {
        ViewModelProvider(this)[FavoriteViewModel::class.java]
    }
    private lateinit var adapter: FavoriteAdapter

    /** 当前挂着的滑删 helper；切换删除方式时要先摘掉，再决定挂不挂 */
    private var swipeHelper: ItemTouchHelper? = null

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
        setupDeleteModeEntry()
        setupClearButton()
        observeViewModel()
        // 订阅已在 ViewModel init 里启动，这里只是保证语义不变（重复调用不会多起 collector）
        viewModel.loadFavorites()
    }

    private fun setupRecyclerView() {
        adapter = FavoriteAdapter(
            onItemClick = { favoriteItem ->
                // 如果有播放列表，直接跳转到播放器
                if (!favoriteItem.episodesJson.isNullOrEmpty()) {
                    val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
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
            },
            onItemLongClick = { favoriteItem ->
                showItemMenu(favoriteItem)
            }
        )

        binding.rvFavorite.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@FavoriteFragment.adapter
        }
    }

    private fun setupSwipeToDelete() {
        // 列表一开始滚动就把露出来的删除区收起，保证同时只有一项展开
        binding.rvFavorite.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) closeRevealedRows()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) closeRevealedRows()
            }
        })
        applySwipeMode()
    }

    /**
     * 按当前删除方式挂 / 摘滑删手势：
     * - 露出按钮（默认）：不挂 ItemTouchHelper，横向手势交给 SwipeRevealLayout；
     * - 滑动即删除：挂上 ItemTouchHelper，左右滑走直接删。
     * 模式先写进 adapter 再刷绑定，避免两套手势抢同一次左滑。
     */
    private fun applySwipeMode() {
        val direct = Dependencies.configRepository.isSwipeDeleteDirect()

        adapter.swipeEnabled = !direct
        // ListAdapter 上不能 notifyDataSetChanged，只让可见行按 payload 重刷滑动状态
        adapter.notifyItemRangeChanged(0, adapter.itemCount, FavoriteAdapter.PAYLOAD_SWIPE)

        swipeHelper?.attachToRecyclerView(null)
        swipeHelper = null
        closeRevealedRows()

        if (direct) attachSwipeHelper()
    }

    private fun attachSwipeHelper() {
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
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val favorite = adapter.currentList.getOrNull(position) ?: return
                deleteFavoriteItem(favorite)
            }
        })
        swipeHelper = itemTouchHelper
        itemTouchHelper.attachToRecyclerView(binding.rvFavorite)
    }

    /** 收起所有已经滑出删除区的条目 */
    private fun closeRevealedRows() {
        val recyclerView = binding.rvFavorite
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (child is SwipeRevealLayout && child.isOpened()) child.close()
        }
    }

    private fun setupDeleteModeEntry() {
        binding.btnDeleteMode.setOnClickListener { showDeleteModeDialog() }
    }

    private fun showDeleteModeDialog() {
        val direct = Dependencies.configRepository.isSwipeDeleteDirect()
        val options = arrayOf(
            getString(R.string.fav_delete_mode_reveal),
            getString(R.string.fav_delete_mode_direct)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fav_delete_mode)
            .setSingleChoiceItems(options, if (direct) 1 else 0) { dialog, which ->
                Dependencies.configRepository.setSwipeDeleteDirect(which == 1)
                dialog.dismiss()
                // 选完立刻生效：重挂手势，不让用户重进页面
                applySwipeMode()
            }
            .show()
    }

    private fun setupClearButton() {
        binding.btnClearAll.setOnClickListener {
            if (!viewModel.hasFavorites.value) {
                snack(getString(R.string.fav_empty_all))
                return@setOnClickListener
            }

            AlertDialog.Builder(requireContext())
                .setTitle("清空收藏")
                .setMessage("确定要清空所有收藏吗？")
                .setPositiveButton("确定") { _, _ ->
                    viewModel.clearAllFavorites()
                    snack("已清空收藏")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun deleteFavoriteItem(favoriteItem: FavoriteItem) {
        // 按收藏项主键 id 删这一条，不按 videoId 批量删
        viewModel.deleteFavorite(favoriteItem)
        // 删除即给「撤销」：把原实体原样写回，位置、分类、解析缓存都不变
        Snackbar.make(binding.root, "已取消收藏", Snackbar.LENGTH_LONG)
            .setAction(UNDO_LABEL) { viewModel.restoreFavorite(favoriteItem) }
            .show()
    }

    private fun observeViewModel() {
        // 分类条：分类列表 / 是否存在未分类 / 当前选择
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.categories,
                viewModel.hasUncategorized,
                viewModel.selectedCategory
            ) { categories, hasUncategorized, selected ->
                Triple(categories, hasUncategorized, selected)
            }.collect { (categories, hasUncategorized, selected) ->
                renderCategoryBar(categories, hasUncategorized, selected)
            }
        }

        // 列表 + 空状态文案
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.favoriteList, viewModel.selectedCategory) { list, selected ->
                list to selected
            }.collect { (favorites, selected) ->
                adapter.submitList(favorites)
                binding.tvEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
                // 计数 + 删除方式入口：空列表时整行不显示
                binding.layoutFavModeRow.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
                binding.tvFavHint.text = getString(R.string.fav_count, favorites.size)
                binding.tvEmptyText.setText(
                    if (selected == null) R.string.fav_empty_all else R.string.fav_empty_category
                )
            }
        }

        // 清空按钮：只要有收藏就可用（筛选后为空也算有）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hasFavorites.collect { hasFavorites ->
                binding.btnClearAll.isEnabled = hasFavorites
            }
        }
    }

    // ===== 分类条 =====

    private fun renderCategoryBar(
        categories: List<String>,
        hasUncategorized: Boolean,
        selected: String?
    ) {
        val bar = binding.categoryBar
        bar.removeAllViews()

        addChip(bar, getString(R.string.fav_category_all), selected == null) {
            viewModel.selectCategory(null)
        }
        if (hasUncategorized) {
            addChip(
                bar,
                getString(R.string.fav_category_uncategorized),
                selected == FavoriteViewModel.UNCATEGORIZED
            ) {
                viewModel.selectCategory(FavoriteViewModel.UNCATEGORIZED)
            }
        }
        categories.forEach { category ->
            addChip(bar, category, selected == category) {
                viewModel.selectCategory(category)
            }
        }
        // 最后一个：新建分类（分类需要挂在一条收藏上，所以这里只是入口，真正落库靠长按选中的那条）
        addChip(bar, getString(R.string.fav_category_new), false, accent = true) {
            showNewCategoryDialog(null)
        }
    }

    private fun addChip(
        bar: LinearLayout,
        label: String,
        selected: Boolean,
        accent: Boolean = false,
        onClick: () -> Unit
    ) {
        val context = requireContext()
        val chip = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        selected -> R.color.theme_on_primary
                        accent -> R.color.theme_primary
                        else -> R.color.theme_text_secondary
                    }
                )
            )
            setBackgroundResource(
                if (selected) R.drawable.bg_fav_chip_selected else R.drawable.bg_fav_chip_normal
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // 分类切换也算换了一屏，先把滑出来的删除区收起
                closeRevealedRows()
                onClick()
            }
        }
        bar.addView(
            chip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        )
    }

    // ===== 长按菜单 =====

    private fun showItemMenu(item: FavoriteItem) {
        val options = arrayOf(
            getString(R.string.fav_menu_move_to_category),
            getString(R.string.fav_menu_new_category),
            getString(R.string.fav_menu_unfavorite)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMoveToCategoryDialog(item)
                    1 -> showNewCategoryDialog(item)
                    2 -> deleteFavoriteItem(item)
                }
            }
            .show()
    }

    /** 二级对话框选分类：未分类 / 已有分类 / 末尾「新建分类…」。 */
    private fun showMoveToCategoryDialog(item: FavoriteItem) {
        val categories = viewModel.categories.value
        val labels = ArrayList<String>(categories.size + 2).apply {
            add(getString(R.string.fav_category_uncategorized))
            addAll(categories)
            add(getString(R.string.fav_dialog_category_new_in_list))
        }
        val newEntryIndex = labels.size - 1

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fav_dialog_pick_category_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == newEntryIndex -> showNewCategoryDialog(item)
                    which == 0 -> {
                        viewModel.assignCategory(item.videoId, null)
                        snack(getString(R.string.fav_toast_moved_to_uncategorized))
                    }
                    else -> {
                        val name = categories[which - 1]
                        viewModel.assignCategory(item.videoId, name)
                        snack(getString(R.string.fav_toast_category_assigned, name))
                    }
                }
            }
            .show()
    }

    /**
     * 新建分类。[item] 不为空时「新建即移入」：把这条收藏放进新分类并切到新分类；
     * 为空（从分类条的「＋ 新建」进来）时只提示要先长按一条收藏——分类没有独立表，空分类无处可存。
     */
    private fun showNewCategoryDialog(item: FavoriteItem?) {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.fav_dialog_category_hint)
            filters = arrayOf<InputFilter>(
                InputFilter.LengthFilter(FavoriteViewModel.MAX_CATEGORY_NAME_LENGTH)
            )
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_input)
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(context)
            .setTitle(R.string.fav_dialog_new_category_title)
            .setView(container)
            .setPositiveButton(R.string.fav_dialog_confirm) { _, _ ->
                val trimmed = input.text.toString().trim()
                if (trimmed.isEmpty()) {
                    snack(getString(R.string.fav_toast_category_name_empty))
                    return@setPositiveButton
                }
                if (trimmed.length > FavoriteViewModel.MAX_CATEGORY_NAME_LENGTH) {
                    snack(getString(R.string.fav_toast_category_name_too_long))
                    return@setPositiveButton
                }
                val name = viewModel.createCategory(trimmed)
                if (name == null) {
                    snack(getString(R.string.fav_toast_category_name_empty))
                    return@setPositiveButton
                }
                if (item == null) {
                    snack(getString(R.string.fav_toast_new_category_needs_item))
                    return@setPositiveButton
                }
                viewModel.assignCategory(item.videoId, name)
                viewModel.selectCategory(name)
                snack(getString(R.string.fav_toast_category_assigned, name))
            }
            .setNegativeButton(R.string.fav_dialog_cancel, null)
            .show()
    }

    private fun snack(message: String) {
        com.tv.ball.util.CustomToast.show(requireContext(), message)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** 撤销按钮文案：不新增 string 资源，跟本页其它就地文案保持一致。 */
        const val UNDO_LABEL = "撤销"
    }
}