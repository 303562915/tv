package com.tv.ball.presentation.shortdrama.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tv.ball.R
import com.tv.ball.databinding.ItemShortDramaEpisodeChipBinding
import com.tv.ball.databinding.SheetShortDramaEpisodesBinding

/**
 * 竖屏播放器的选集面板。
 *
 * 为什么不用 Activity 里的普通 View 做面板
 * ---------------------------------------
 * 竖屏播放页是「整屏手势都在翻页」的页面，任何自己画的面板都要额外处理拖动冲突；
 * BottomSheetDialogFragment 自带手势与返回键处理，并且模板里已经有现成的圆角浅色底
 * （bg_bottom_sheet），和详情页的选集网格观感一致。
 *
 * 回调为什么用属性而不是 Fragment factory
 * ---------------------------------------
 * 面板只活在当前 Activity 里，点击结果直接回给 Activity 翻页即可；
 * 进程被回收后重建的面板点不动，但用户在竖屏里本来就是靠上下滑，这个取舍可以接受。
 */
class ShortDramaEpisodeSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ShortDramaEpisodeSheet"

        private const val ARG_LINE = "sd_arg_line"
        private const val ARG_NUMS = "sd_arg_nums"
        private const val ARG_CURRENT = "sd_arg_current"

        /** 一行 5 个：手机宽度下 5 列的字号还能保持 14sp，6 列就会挤 */
        private const val SPAN_COUNT = 5

        /** 最多铺 6 行（30 个），再多的集数就在面板里滚动，避免面板顶满全屏 */
        private const val MAX_ROWS = 6

        /** 与 item_short_drama_episode_chip.xml 的 44dp 高度 + 8dp 上下 margin 对齐 */
        private const val CHIP_HEIGHT_DP = 44
        private const val CHIP_MARGIN_DP = 8

        fun newInstance(lineName: String, episodeNums: IntArray, currentIndex: Int): ShortDramaEpisodeSheet =
            ShortDramaEpisodeSheet().apply {
                arguments = bundleOf(
                    ARG_LINE to lineName,
                    ARG_NUMS to episodeNums,
                    ARG_CURRENT to currentIndex
                )
            }
    }

    /** 点某集：参数是线路内的下标（不是集号），调用方直接 setCurrentItem */
    var onEpisodeClick: ((Int) -> Unit)? = null

    private var binding: SheetShortDramaEpisodesBinding? = null
    private var chipAdapter: EpisodeChipAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = SheetShortDramaEpisodesBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return

        val nums = arguments?.getIntArray(ARG_NUMS) ?: IntArray(0)
        val currentIndex = arguments?.getInt(ARG_CURRENT, 0) ?: 0
        val lineName = arguments?.getString(ARG_LINE).orEmpty()

        viewBinding.tvSheetCount.text = "共${nums.size}集"
        viewBinding.tvSheetLine.text = if (lineName.isBlank()) "" else "线路：$lineName"
        viewBinding.tvSheetLine.visibility = if (lineName.isBlank()) View.GONE else View.VISIBLE

        val adapter = EpisodeChipAdapter(nums, currentIndex) { index ->
            onEpisodeClick?.invoke(index)
            dismiss()
        }
        chipAdapter = adapter
        viewBinding.episodeGrid.layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT)
        viewBinding.episodeGrid.adapter = adapter

        applyGridHeight(nums.size)

        // 打开时就停在当前集：集数多的剧不这样的话，用户要先自己翻几十格
        viewBinding.episodeGrid.post {
            val manager = viewBinding.episodeGrid.layoutManager as? GridLayoutManager
            manager?.scrollToPositionWithOffset(currentIndex.coerceAtLeast(0), 0)
        }
    }

    /**
     * 网格高度按「行数 x 固定行高」算死。
     * 用 wrap_content 的话 RecyclerView 会为了算高度把所有格子都测量一遍，
     * 200 集的剧打开面板会明显顿一下，而这里的格子高度本来就是固定的，没必要全量测量。
     */
    private fun applyGridHeight(count: Int) {
        val viewBinding = binding ?: return
        if (count <= 0) return
        val rows = ((count + SPAN_COUNT - 1) / SPAN_COUNT).coerceIn(1, MAX_ROWS)
        val density = resources.displayMetrics.density
        val rowHeightPx = ((CHIP_HEIGHT_DP + CHIP_MARGIN_DP) * density).toInt()
        val params = viewBinding.episodeGrid.layoutParams
        params.height = rows * rowHeightPx
        viewBinding.episodeGrid.layoutParams = params
    }

    override fun onStart() {
        super.onStart()
        // 占满宽度（默认会左右留边），并把 Material 自带的那层白底去掉，
        // 否则我们自己画的圆角会被它盖成直角
        val sheet = dialog as? BottomSheetDialog
        sheet?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sheet?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDestroyView() {
        // RecyclerView 的 adapter 持有 Fragment 的 lambda，不在这里断开会拖住整页面
        binding?.episodeGrid?.adapter = null
        chipAdapter = null
        binding = null
        super.onDestroyView()
    }

    /** 选集格子。当前集只换底色和文字色，不做额外图标，和详情页的选集保持同一套视觉 */
    private class EpisodeChipAdapter(
        private val episodeNums: IntArray,
        private val currentIndex: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeChipAdapter.ChipViewHolder>() {

        class ChipViewHolder(val binding: ItemShortDramaEpisodeChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = episodeNums.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder =
            ChipViewHolder(
                ItemShortDramaEpisodeChipBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
            val chip = holder.binding.chipText
            val selected = position == currentIndex
            chip.text = "第${episodeNums[position]}集"
            chip.isSelected = selected
            chip.setTextColor(
                ContextCompat.getColor(
                    chip.context,
                    if (selected) R.color.episode_chip_text_selected else R.color.episode_chip_text
                )
            )
            chip.setOnClickListener { onClick(position) }
        }
    }
}
