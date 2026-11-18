package com.tv.ball.presentation.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tv.ball.R

class ParseLinksBottomSheet : BottomSheetDialogFragment() {

    private var links: List<ParsedLink> = emptyList()
    private var onLinkClickListener: ((ParsedLink) -> Unit)? = null
    private var onRefreshClickListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_parse_links, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleText = view.findViewById<TextView>(R.id.titleText)
        val recyclerView = view.findViewById<RecyclerView>(R.id.linksRecyclerView)
        val emptyView = view.findViewById<View>(R.id.emptyView)
        val refreshButton = view.findViewById<TextView>(R.id.refreshButton)

        // 设置标题
        titleText.text = "解析链接 (${links.size})"

        // 设置 RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        if (links.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE

            val adapter = ParseLinkAdapter(links) { link ->
                onLinkClickListener?.invoke(link)
                dismiss()
            }
            recyclerView.adapter = adapter
        }

        // 重新解析按钮
        refreshButton.setOnClickListener {
            onRefreshClickListener?.invoke()
            dismiss()
        }
    }

    fun setLinks(links: List<ParsedLink>) {
        this.links = links
    }

    fun setOnLinkClickListener(listener: (ParsedLink) -> Unit) {
        this.onLinkClickListener = listener
    }

    fun setOnRefreshClickListener(listener: () -> Unit) {
        this.onRefreshClickListener = listener
    }

    companion object {
        const val TAG = "ParseLinksBottomSheet"

        fun newInstance(
            links: List<ParsedLink>,
            onLinkClick: (ParsedLink) -> Unit,
            onRefresh: () -> Unit
        ): ParseLinksBottomSheet {
            return ParseLinksBottomSheet().apply {
                setLinks(links)
                setOnLinkClickListener(onLinkClick)
                setOnRefreshClickListener(onRefresh)
            }
        }
    }
}
