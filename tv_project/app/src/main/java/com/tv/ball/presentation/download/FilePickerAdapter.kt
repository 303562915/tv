package com.tv.ball.presentation.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.databinding.ItemFilePickerBinding
import java.text.SimpleDateFormat
import java.util.*

class FilePickerAdapter(
    private val onItemClick: (java.io.File) -> Unit
) : ListAdapter<FileItem, FilePickerAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFilePickerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemFilePickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.tvFileName.text = item.name

            // 设置图标
            if (item.isDirectory) {
                binding.ivIcon.setImageResource(android.R.drawable.ic_menu_view)
            } else {
                binding.ivIcon.setImageResource(android.R.drawable.ic_menu_info_details)
            }

            // 显示修改时间
            if (item.lastModified > 0) {
                val dateFormat = SimpleDateFormat("M月 dd, yyyy HH:mm 下午", Locale.CHINA)
                binding.tvFileDate.text = dateFormat.format(Date(item.lastModified))
            } else {
                binding.tvFileDate.text = ""
            }

            // 点击事件
            binding.root.setOnClickListener {
                onItemClick(item.file)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.file.absolutePath == newItem.file.absolutePath
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem == newItem
        }
    }
}
