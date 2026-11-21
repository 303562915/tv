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
import kotlinx.coroutines.launch

/**
 * 观看历史内容。
 *
 * 列表结构：按「今天 / 昨天 / 更早」分段（分组由 ViewModel 给出），
 * 点击 = 接着上次的位置继续播，长按 = 菜单（继续播放 / 删除），左右滑 = 删除。
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
        setupEmptyState()
        bindHostToolbar()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { historyItem -> openItem(historyItem) },
            onItemLongClick = { historyItem -> showItemMenu(historyItem) }
        )
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
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
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                // 分组标题不可滑删：滑到它就把这一行还原回去
                when (val row = adapter.currentList.getOrNull(position)) {
                    is HistoryRow.Entry -> deleteItem(row.item)
                    else -> adapter.notifyItemChanged(position)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvHistory)
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
            viewModel.groupedHistory.collect { groups ->
                val rows = mutableListOf<HistoryRow>()
                groups.forEach { group ->
                    rows.add(HistoryRow.Header(group.title))
                    group.items.forEach { rows.add(HistoryRow.Entry(it)) }
                }
                adapter.submitList(rows)

                hasHistory = rows.isNotEmpty()
                binding.tvEmpty.visibility = if (hasHistory) View.GONE else View.VISIBLE
                binding.tvHint.visibility = if (hasHistory) View.VISIBLE else View.GONE
                binding.rvHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
                (activity as? HistoryActivity)?.setClearAllEnabled(hasHistory)
            }
        }
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
        val options = arrayOf(
            getString(R.string.history_continue),
            getString(R.string.history_delete_one)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(historyItem.videoItem.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openItem(historyItem)
                    1 -> deleteItem(historyItem)
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        if (!hasHistory) {
            Snackbar.make(binding.root, R.string.history_nothing_to_clear, Snackbar.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.clearAllHistory()
                Snackbar.make(binding.root, R.string.history_cleared, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteItem(historyItem: HistoryItem) {
        viewModel.deleteHistory(historyItem.videoItem.title)

        Snackbar.make(binding.root, R.string.history_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.history_undo) {
                // 撤销：按原观看时间写回，位置不会因为这个操作跳到最前
                viewModel.addHistory(historyItem)
            }
            .show()
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
}
