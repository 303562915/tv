package com.tv.ball.presentation.home

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.databinding.ItemPageFooterBinding

/** 列表底部翻页状态：空闲（不占位）/ 加载中 / 失败可点 */
enum class PageFooterState { HIDDEN, LOADING, ERROR }

/**
 * 翻页 footer。
 *
 * 固定占用 1 个 item（HIDDEN 时高度 0），只改状态不增删 item：
 * 这样不会在 RecyclerView 正在布局时做 insert/remove 通知，也就不用担心
 * 「Cannot call this method while RecyclerView is computing a layout」。
 */
class PageFooterAdapter(
    private val onRetry: () -> Unit
) : RecyclerView.Adapter<PageFooterAdapter.ViewHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = PageFooterState.HIDDEN

    fun setState(newState: PageFooterState) {
        if (state == newState) return
        state = newState
        // 延后一帧再通知：调用点常在数据 diff 回调里，直接通知可能正撞上布局
        mainHandler.post { notifyItemChanged(0) }
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemPageFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(state, onRetry)

    class ViewHolder(
        private val binding: ItemPageFooterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(state: PageFooterState, onRetry: () -> Unit) {
            when (state) {
                PageFooterState.HIDDEN -> {
                    binding.root.visibility = View.GONE
                    binding.root.setOnClickListener(null)
                }
                PageFooterState.LOADING -> {
                    binding.root.visibility = View.VISIBLE
                    binding.footerProgress.visibility = View.VISIBLE
                    binding.footerText.text = "加载中…"
                    binding.root.setOnClickListener(null)
                }
                PageFooterState.ERROR -> {
                    binding.root.visibility = View.VISIBLE
                    binding.footerProgress.visibility = View.INVISIBLE
                    binding.footerText.text = "加载失败，点击重试"
                    binding.root.setOnClickListener { onRetry() }
                }
            }
        }
    }
}