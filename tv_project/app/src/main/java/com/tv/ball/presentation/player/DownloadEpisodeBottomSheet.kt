package com.tv.ball.presentation.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tv.ball.R
import com.tv.ball.domain.model.Episode

class DownloadEpisodeBottomSheet : BottomSheetDialogFragment() {

    private var episodes: List<Episode> = emptyList()
    private var currentIndex: Int = 0
    private var onEpisodeClickListener: ((Episode) -> Unit)? = null
    private var onDownloadCurrentListener: (() -> Unit)? = null
    private var onBatchDownloadListener: ((List<Episode>) -> Unit)? = null
    
    private lateinit var adapter: DownloadEpisodeAdapter
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var selectAllLayout: View
    private lateinit var toggleModeButton: TextView
    private lateinit var downloadCurrentButton: TextView
    private lateinit var batchDownloadButton: TextView
    private var isMultiSelectMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_download_episode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleText = view.findViewById<TextView>(R.id.titleText)
        val recyclerView = view.findViewById<RecyclerView>(R.id.episodesRecyclerView)
        selectAllCheckbox = view.findViewById(R.id.selectAllCheckbox)
        selectAllLayout = view.findViewById(R.id.selectAllLayout)
        toggleModeButton = view.findViewById(R.id.toggleModeButton)
        downloadCurrentButton = view.findViewById(R.id.downloadCurrentButton)
        batchDownloadButton = view.findViewById(R.id.batchDownloadButton)
        val cancelButton = view.findViewById<TextView>(R.id.cancelButton)

        // 设置标题
        titleText.text = "选择要下载的剧集"

        // 设置 RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = DownloadEpisodeAdapter(
            episodes = episodes,
            currentIndex = currentIndex,
            multiSelectMode = false, // 默认单选模式
            onEpisodeClick = { episode ->
                // 单选模式：直接下载
                onEpisodeClickListener?.invoke(episode)
                dismiss()
            },
            onSelectionChanged = { selectedCount ->
                // 更新批量下载按钮文本
                if (selectedCount > 0) {
                    batchDownloadButton.text = "批量下载 ($selectedCount)"
                    batchDownloadButton.visibility = View.VISIBLE
                } else {
                    batchDownloadButton.visibility = View.GONE
                }
                
                // 更新全选状态
                selectAllCheckbox.isChecked = selectedCount == episodes.size
            }
        )
        recyclerView.adapter = adapter

        // 模式切换按钮
        toggleModeButton.setOnClickListener {
            isMultiSelectMode = !isMultiSelectMode
            updateMode()
        }

        // 全选/取消全选
        selectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
            adapter.selectAll(isChecked)
        }

        // 下载当前按钮
        downloadCurrentButton.setOnClickListener {
            onDownloadCurrentListener?.invoke()
            dismiss()
        }

        // 批量下载按钮
        batchDownloadButton.setOnClickListener {
            val selectedEpisodes = adapter.getSelectedEpisodes()
            if (selectedEpisodes.isNotEmpty()) {
                onBatchDownloadListener?.invoke(selectedEpisodes)
                dismiss()
            }
        }

        // 取消按钮
        cancelButton.setOnClickListener {
            dismiss()
        }
        
        // 初始化UI状态
        updateMode()
    }
    
    private fun updateMode() {
        if (isMultiSelectMode) {
            // 多选模式
            toggleModeButton.text = "单选"
            selectAllLayout.visibility = View.VISIBLE
            downloadCurrentButton.visibility = View.GONE
            batchDownloadButton.visibility = View.GONE
            adapter.setMultiSelectMode(true)
        } else {
            // 单选模式
            toggleModeButton.text = "多选"
            selectAllLayout.visibility = View.GONE
            downloadCurrentButton.visibility = View.VISIBLE
            batchDownloadButton.visibility = View.GONE
            adapter.setMultiSelectMode(false)
        }
    }

    fun setEpisodes(episodes: List<Episode>, currentIndex: Int) {
        this.episodes = episodes
        this.currentIndex = currentIndex
    }

    fun setOnEpisodeClickListener(listener: (Episode) -> Unit) {
        this.onEpisodeClickListener = listener
    }

    fun setOnDownloadCurrentListener(listener: () -> Unit) {
        this.onDownloadCurrentListener = listener
    }
    
    fun setOnBatchDownloadListener(listener: (List<Episode>) -> Unit) {
        this.onBatchDownloadListener = listener
    }

    companion object {
        const val TAG = "DownloadEpisodeBottomSheet"

        fun newInstance(
            episodes: List<Episode>,
            currentIndex: Int,
            onEpisodeClick: (Episode) -> Unit,
            onDownloadCurrent: () -> Unit,
            onBatchDownload: (List<Episode>) -> Unit
        ): DownloadEpisodeBottomSheet {
            return DownloadEpisodeBottomSheet().apply {
                setEpisodes(episodes, currentIndex)
                setOnEpisodeClickListener(onEpisodeClick)
                setOnDownloadCurrentListener(onDownloadCurrent)
                setOnBatchDownloadListener(onBatchDownload)
            }
        }
    }
}
