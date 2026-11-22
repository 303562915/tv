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
import com.tv.ball.R
import com.tv.ball.databinding.FragmentHistoryBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.widget.SwipeRevealLayout
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 观看历史内容。
 *
 * 列表结构：按「今天 / 昨天 / 更早」分段（分组由 ViewModel 给出），
 * 点击 = 接着上次的位置继续播，长按 = 菜单（继续播放 / 删除）。
 * 删除有两种左滑行为，入口是顶部说明行右侧的「删除方式」：
 * - 露出按钮（默认）：左滑把卡片平移出去露出右侧「删除」按钮，点按钮才删；
 * - 滑动即删除：ItemTouchHelper 左右滑走直接删。
 * 「清空历史」按钮在 [HistoryActivity] 的工具条上，通过回调驱动，不在这里重复造一条工具栏。
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by lazy {
        ViewModelProvider(this)[HistoryViewModel::class.java]
    }

    private lateinit var adapter: HistoryAdapter
    private var hasHistory = false

    /** 当前挂着的滑删 helper；切换删除方式时要先摘掉，再决定挂不挂 */
    private var swipeHelper: ItemTouchHelper? = null

    /** 上一次渲染出来的多选开关状态：只在真正切换时搬焦点，选中数变化不抢焦点 */
    private var selectionModeShown = false

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
        setupDeleteModeEntry()
        setupSelectionBar()
        setupEmptyState()
        bindHostToolbar()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { historyItem -> openItem(historyItem) },
            onItemLongClick = { historyItem -> showItemMenu(historyItem) },
            onItemDelete = { historyItem -> deleteItem(historyItem) },
            onItemToggleSelection = { historyItem ->
                viewModel.toggleItemSelection(historyDeleteKey(historyItem))
            },
            // 选中集与批量删除共用 ViewModel 的删除键口径，适配器里不再推一套 key
            selectionKeyOf = { historyItem -> historyDeleteKey(historyItem) }
        )
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }
    }

    private fun setupSwipeToDelete() {
        // 列表一开始滚动就把露出来的删除区收起，保证同时只有一项展开
        binding.rvHistory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) closeRevealedRows(recyclerView)
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) closeRevealedRows(recyclerView)
            }
        })
        applySwipeMode()
    }

    /**
     * 按当前删除方式挂 / 摘滑删手势：
     * - 露出按钮（默认）：不挂 ItemTouchHelper，横向手势交给 SwipeRevealLayout；
     * - 滑动即删除：挂上 ItemTouchHelper，左右滑走直接删。
     * 切换时原地重挂，不用退出页面。
     * 多选期间一律不挂：同一段横向手势会和「点条目切换选中」抢触摸，摘掉最干净，
     * 退出多选后这个方法会被再调一次，按当前删除方式原样挂回去。
     */
    private fun applySwipeMode() {
        val direct = Dependencies.configRepository.isSwipeDeleteDirect()

        // 先改模式再刷绑定：容器手势和 ItemTouchHelper 只能有一个生效，避免两套手势抢同一次左滑
        adapter.swipeEnabled = !direct
        // ListAdapter 上不能 notifyDataSetChanged（会把 DiffUtil 的动画和身份追踪一起废掉），
        // 只让可见行按 payload 重刷滑动状态
        adapter.notifyItemRangeChanged(0, adapter.itemCount, HistoryAdapter.PAYLOAD_SWIPE)

        swipeHelper?.attachToRecyclerView(null)
        swipeHelper = null
        closeRevealedRows(binding.rvHistory)

        if (direct && !viewModel.isSelectionMode.value) {
            attachSwipeToDeleteHelper()
        }
    }

    private fun setupDeleteModeEntry() {
        binding.btnDeleteMode.setOnClickListener { showDeleteModeDialog() }
    }

    /** 多选操作条：全选 / 删除 / 取消。按钮本身的可聚焦和高度写在布局里，这里只接动作。 */
    private fun setupSelectionBar() {
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
    }

    private fun showDeleteModeDialog() {
        val direct = Dependencies.configRepository.isSwipeDeleteDirect()
        val options = arrayOf(
            getString(R.string.history_delete_mode_reveal),
            getString(R.string.history_delete_mode_direct)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_delete_mode)
            .setSingleChoiceItems(options, if (direct) 1 else 0) { dialog, which ->
                Dependencies.configRepository.setSwipeDeleteDirect(which == 1)
                dialog.dismiss()
                // 选完立刻生效：重挂手势，不让用户重进页面
                applySwipeMode()
            }
            .show()
    }

    private fun attachSwipeToDeleteHelper() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            /**
             * 分组标题不参与滑删：不给任何方向，连「滑出去再弹回来」的抖动都不会发生。
             * 条目仍然左右都可滑，滑走时按 bindingAdapterPosition 取到的是条目本身。
             * 多选期间统一返回 0：helper 虽然已被摘掉，这里再守一道，
             * 防止切换模式那一刻还在飞行中的滑动把条目滑走删掉。
             */
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (viewModel.isSelectionMode.value) {
                makeMovementFlags(0, 0)
            } else if (adapter.isSwipeableHolder(viewHolder)) {
                makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
            } else {
                makeMovementFlags(0, 0)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                // 分组标题不可滑删：滑到它就把这一行还原回去
                when (val row = adapter.currentList.getOrNull(position)) {
                    is HistoryRow.Entry -> deleteItem(row.item)
                    else -> adapter.notifyItemChanged(position)
                }
            }
        })
        swipeHelper = itemTouchHelper
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)
    }

    /** 收起所有已经滑出删除区的条目 */
    private fun closeRevealedRows(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            if (child is SwipeRevealLayout && child.isOpened()) child.close()
        }
    }

    private fun setupEmptyState() {
        binding.btnGoHome.setOnClickListener {
            requireActivity().finish()
        }
    }

    private fun bindHostToolbar() {
        val host = activity as? HistoryActivity ?: return
        host.onClearAll = { confirmClearAll() }
        host.setClearAllEnabled(false)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 三个 Flow 一起收集：rows 与选中集必须来自同一帧，
            // 否则会出现「列表已经删了、操作条还显示旧选中数」这种错位
            combine(
                viewModel.groupedHistory,
                viewModel.isSelectionMode,
                viewModel.selectedItems
            ) { groups, inSelection, selected ->
                Triple(groups, inSelection, selected)
            }.collect { (groups, inSelection, selected) ->
                val rows = mutableListOf<HistoryRow>()
                groups.forEach { group ->
                    rows.add(HistoryRow.Header(group.title))
                    group.items.forEach { rows.add(HistoryRow.Entry(it)) }
                }

                // 多选里被「清空全部」掏空时一并退出：这时没有可选条目，
                // 操作条停在「已选 N 项」却没有一行可点。这一帧先按普通态渲染，下一帧 ViewModel 也跟上来
                val selectionActive = inSelection && rows.isNotEmpty()
                if (inSelection && !selectionActive) exitSelectionMode()

                // 多选状态要先落到适配器再提交列表：rows 不含选中态，
                // 光提交同样的列表 DiffUtil 不会重绑，勾选标记会停在旧值上
                adapter.applySelectionState(selectionActive, selected)
                adapter.submitList(rows)

                hasHistory = rows.isNotEmpty()
                binding.tvEmpty.visibility = if (hasHistory) View.GONE else View.VISIBLE
                binding.tvHint.visibility = if (hasHistory) View.VISIBLE else View.GONE
                binding.rvHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
                (activity as? HistoryActivity)?.setClearAllEnabled(hasHistory)

                renderSelectionBar(selectionActive, selected.size)
            }
        }
    }

    /**
     * 操作条的显示与焦点：
     * - 未进入多选整条 gone，且 GONE 不占位，列表位置和原来一致；
     * - 进入多选把焦点送到第一个按钮，退出时还给列表，遥控器不用先按一圈方向键。
     * 选中数为 0 时删除按钮仍然可聚焦，点了给一句提示：TV 上禁用态会被焦点导航跳过，反而更难解释。
     */
    private fun renderSelectionBar(inSelection: Boolean, selectedCount: Int) {
        val modeChanged = selectionModeShown != inSelection
        selectionModeShown = inSelection

        binding.selectionBar.visibility = if (inSelection) View.VISIBLE else View.GONE
        // 文案就地拼中文字面量，不新增 string 资源，和本页 UNDO_LABEL 的处理保持一致
        binding.tvSelectedCount.text = "已选 $selectedCount 项"
        binding.btnDeleteSelected.alpha = if (selectedCount > 0) 1f else 0.45f

        if (!modeChanged) return

        // 模式切换要重挂滑删手势：多选期间不挂，退出后按当前删除方式恢复
        applySwipeMode()

        if (inSelection) {
            // 焦点交给操作条第一个按钮
            val target = binding.btnSelectAll
            target.post {
                if (target.isAttachedToWindow) target.requestFocus()
            }
        } else {
            focusFirstHistoryRow()
        }
    }

    /**
     * 退出多选把焦点还给列表。
     *
     * 不直接 requestFocus 到 RecyclerView 本身：容器一旦可聚焦，
     * 遥控器要先「进容器」再「进条目」，等于无端多按一次方向键。
     * 这里直接点第一行卡片，拿不到（列表空了）就交给系统自己安置焦点。
     */
    private fun focusFirstHistoryRow() {
        val recyclerView = binding.rvHistory
        recyclerView.post {
            if (!recyclerView.isAttachedToWindow) return@post
            for (i in 0 until recyclerView.childCount) {
                val content = (recyclerView.getChildAt(i) as? SwipeRevealLayout)?.contentView
                if (content != null && content.requestFocus()) return@post
            }
        }
    }

    /**
     * 从长按菜单进多选。顺手把长按的那条勾上，免得进模式后还要再点一次；
     * 已经在多选里就别把模式又切回去。
     */
    private fun enterSelectionMode(historyItem: HistoryItem) {
        if (!viewModel.isSelectionMode.value) viewModel.toggleSelectionMode()

        val key = historyDeleteKey(historyItem)
        if (key !in viewModel.selectedItems.value) viewModel.toggleItemSelection(key)
    }

    /** 退出多选：统一走 ViewModel，选中集一起清空，界面由 isSelectionMode 的收集回落到普通态 */
    private fun exitSelectionMode() {
        if (viewModel.isSelectionMode.value) viewModel.toggleSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val count = viewModel.selectedItems.value.size
        if (count == 0) {
            com.tv.ball.util.CustomToast.show(requireContext(), HINT_SELECT_FIRST)
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(TITLE_DELETE_SELECTED)
            .setMessage("删除 $count 条观看历史，不可恢复")
            .setPositiveButton(LABEL_DELETE) { _, _ ->
                // deleteSelected 删完会把选中集清空并退出多选，操作条跟着 isSelectionMode 收起
                viewModel.deleteSelected()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openItem(historyItem: HistoryItem) {
        if (!historyItem.episodesJson.isNullOrEmpty()) {
            val startIndex = try {
                val episodes = parseEpisodesFromJson(historyItem.episodesJson)
                episodes.indexOfFirst { it.name == historyItem.currentEpisode }.takeIf { it >= 0 } ?: 0
            } catch (e: Exception) {
                0
            }

            val intent = Intent(
                requireContext(),
                com.tv.ball.presentation.player.VideoPlayerActivity::class.java
            ).apply {
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
            val intent = Intent(
                requireContext(),
                com.tv.ball.presentation.detail.VideoDetailActivity::class.java
            ).apply {
                putExtra("DETAIL_URL", historyItem.detailUrl ?: historyItem.videoItem.detailUrl)
                putExtra("COVER_URL", historyItem.videoItem.coverUrl)
            }
            startActivity(intent)
        }
    }

    private fun showItemMenu(historyItem: HistoryItem) {
        // 前两项保持原顺序不变，多选作为第三项追加，老习惯不用重新学
        val options = arrayOf(
            getString(R.string.history_continue),
            getString(R.string.history_delete_one),
            LABEL_SELECT_MODE
        )
        AlertDialog.Builder(requireContext())
            .setTitle(historyItem.videoItem.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openItem(historyItem)
                    1 -> deleteItem(historyItem)
                    2 -> enterSelectionMode(historyItem)
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        if (!hasHistory) {
            com.tv.ball.util.CustomToast.show(
                requireContext(),
                getString(R.string.history_nothing_to_clear)
            )
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.clearAllHistory()
                com.tv.ball.util.CustomToast.show(requireContext(), getString(R.string.history_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteItem(historyItem: HistoryItem) {
        // 下发唯一键（detailUrl + 集数）而不是标题：同名不同线路 / 不同集不会被一起删掉
        viewModel.deleteHistory(historyItem)
        // 删除即给「撤销」：撤销调 addHistory，用原观看时间写回，记录不会跳回列表最前
        Snackbar.make(
            binding.root,
            getString(R.string.history_deleted),
            Snackbar.LENGTH_LONG
        ).setAction(UNDO_LABEL) {
            viewModel.addHistory(historyItem)
        }.show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? HistoryActivity)?.onClearAll = null
        _binding = null
    }

    private companion object {
        /** 撤销按钮文案：不新增 string 资源，跟本页其它就地文案保持一致。 */
        const val UNDO_LABEL = "撤销"

        /** 长按菜单第三项：进多选 */
        const val LABEL_SELECT_MODE = "多选"

        /** 批量删除的二次确认文案 */
        const val TITLE_DELETE_SELECTED = "删除选中项"
        const val LABEL_DELETE = "删除"

        /** 一条都没选就点删除时的提示 */
        const val HINT_SELECT_FIRST = "先选几条"
    }
}
