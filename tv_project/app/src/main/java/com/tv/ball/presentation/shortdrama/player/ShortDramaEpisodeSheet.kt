package com.tv.ball.presentation.shortdrama.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tv.ball.R
import com.tv.ball.databinding.ItemShortDramaEpisodeChipBinding
import com.tv.ball.databinding.SheetShortDramaEpisodesBinding

/**
 * 竖屏播放器的选集面板（对齐抖音短剧的竖列表）。
 *
 * 为什么不用 Activity 里的普通 View 做面板
 * ---------------------------------------
 * 竖屏播放页是「整屏手势都在翻页」的页面，任何自己画的面板都要额外处理拖动冲突；
 * BottomSheetDialogFragment 自带手势与返回键处理，并且模板里已经有现成的圆角浅色底
 * （bg_bottom_sheet），和详情页的选集保持同一套视觉。
 *
 * 为什么从网格改成竖列表
 * ---------------------
 * 网格只能塞下「第N集」，用户看不出这一集是什么内容；竖列表一行能同时给
 * 缩略图 + 集号 + 剧名 + 线路 + 总集数，接近抖音的面板，也省掉在长网格里找集号的成本。
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
        private const val ARG_COVER = "sd_arg_cover"
        private const val ARG_TITLE = "sd_arg_title"
        private const val ARG_TOTAL = "sd_arg_total"

        /** 列表最多铺 5 行，再多就在面板里滚动，避免面板顶满全屏 */
        private const val MAX_ROWS = 5

        /** 与 item_short_drama_episode_chip.xml 的 72dp 行高 + 8dp 下边距对齐 */
        private const val ROW_HEIGHT_DP = 80

        /**
         * @param cover 剧封面，用作每行左侧缩略图（同一部剧集封面相同，这是抖音的做法）
         * @param title 剧名，拼在每行第一行「第N集 · 剧名」里
         * @param totalEpisodes 当前线路的总集数，用于第二行「线路名 · 共M集」
         */
        fun newInstance(
            lineName: String,
            episodeNums: IntArray,
            currentIndex: Int,
            cover: String,
            title: String,
            totalEpisodes: Int
        ): ShortDramaEpisodeSheet = ShortDramaEpisodeSheet().apply {
            arguments = bundleOf(
                ARG_LINE to lineName,
                ARG_NUMS to episodeNums,
                ARG_CURRENT to currentIndex,
                ARG_COVER to cover,
                ARG_TITLE to title,
                ARG_TOTAL to totalEpisodes
            )
        }
    }

    /** 点某集：参数是线路内的下标（不是集号），调用方直接 setCurrentItem */
    var onEpisodeClick: ((Int) -> Unit)? = null

    private var binding: SheetShortDramaEpisodesBinding? = null
    private var rowAdapter: EpisodeRowAdapter? = null

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
        val cover = arguments?.getString(ARG_COVER).orEmpty()
        val title = arguments?.getString(ARG_TITLE).orEmpty()
        // 调用方没给总集数时退回「当前线路的分集数」，否则第二行会写成「共0集」
        val total = (arguments?.getInt(ARG_TOTAL, 0) ?: 0).let { if (it > 0) it else nums.size }

        val adapter = EpisodeRowAdapter(
            episodeNums = nums,
            currentIndex = currentIndex,
            lineName = lineName,
            title = title,
            totalEpisodes = total,
            cover = cover
        ) { index ->
            onEpisodeClick?.invoke(index)
            dismiss()
        }
        rowAdapter = adapter
        viewBinding.episodeList.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.episodeList.adapter = adapter

        applyListHeight(nums.size)

        // 「更多推荐」是本地占位：不请求推荐接口、不落库，只给一句提示，
        // 免得用户以为按钮坏了（详情页的推荐位是另一套链路，这里不复用）
        viewBinding.tvMoreLike.setOnClickListener {
            Toast.makeText(requireContext(), "暂未开放", Toast.LENGTH_SHORT).show()
        }

        // 回到第 1 集：回调 0 号下标再关面板，和点第一行是同一条路径
        viewBinding.btnFirstEpisode.setOnClickListener {
            onEpisodeClick?.invoke(0)
            dismiss()
        }

        // 打开时就停在当前集：集数多的剧不这样的话，用户要先自己翻几十行
        viewBinding.episodeList.post {
            (viewBinding.episodeList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(currentIndex.coerceAtLeast(0), 0)
        }
    }

    /**
     * 列表高度按「行数 x 固定行高」算死。
     * 用 wrap_content 的话 RecyclerView 会为了算高度把所有行都测量一遍，
     * 200 集的剧打开面板会明显顿一下，而这里的行高本来就是固定的，没必要全量测量。
     */
    private fun applyListHeight(count: Int) {
        val viewBinding = binding ?: return
        val rows = count.coerceIn(1, MAX_ROWS)
        val density = resources.displayMetrics.density
        val params = viewBinding.episodeList.layoutParams
        params.height = (rows * ROW_HEIGHT_DP * density).toInt()
        viewBinding.episodeList.layoutParams = params
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
        binding?.episodeList?.adapter = null
        rowAdapter = null
        binding = null
        super.onDestroyView()
    }

    /**
     * 选集行：缩略图 + 「第N集 · 剧名」+「线路名 · 共M集」。
     * 当前集靠 isSelected 触发 sd_bg_episode_row 的描边，文字另外染成主色——
     * 只换底色在浅色面板上不够醒目，只换文字又容易被缩略图抢走注意力，两个一起才一眼能认出来。
     */
    private class EpisodeRowAdapter(
        private val episodeNums: IntArray,
        private val currentIndex: Int,
        private val lineName: String,
        private val title: String,
        private val totalEpisodes: Int,
        private val cover: String,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeRowAdapter.RowViewHolder>() {

        class RowViewHolder(val binding: ItemShortDramaEpisodeChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = episodeNums.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder =
            RowViewHolder(
                ItemShortDramaEpisodeChipBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val row = holder.binding
            val num = episodeNums[position]
            val selected = position == currentIndex

            row.root.isSelected = selected
            row.chipTitle.text = if (title.isBlank()) "第${num}集" else "第${num}集 · $title"
            row.chipTitle.setTextColor(
                ContextCompat.getColor(
                    row.root.context,
                    if (selected) R.color.theme_primary else R.color.theme_text_primary
                )
            )
            row.chipMeta.text = if (lineName.isBlank()) {
                "共${totalEpisodes}集"
            } else {
                "$lineName · 共${totalEpisodes}集"
            }
            row.chipMeta.setTextColor(
                ContextCompat.getColor(row.root.context, R.color.theme_text_secondary)
            )

            // 封面为空就只留 sd_bg_thumb 那层灰底：不编造占位图片，也不清成透明（会露出白块）
            if (cover.isBlank()) {
                row.ivThumb.setImageDrawable(null)
            } else {
                Glide.with(row.ivThumb)
                    .load(cover)
                    .centerCrop()
                    .into(row.ivThumb)
            }

            row.root.contentDescription = buildString {
                append(row.chipTitle.text)
                append("，")
                append(row.chipMeta.text)
                if (selected) append("，当前集")
            }
            row.root.setOnClickListener { onClick(position) }
        }
    }
}
