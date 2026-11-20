package com.tv.ball.presentation.feature

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.databinding.ItemFeatureGroupBinding
import com.tv.ball.databinding.ItemFeatureRowBinding
import com.tv.ball.feature.FeatureModule

/**
 * 功能中心的列表：分组标题 + 功能行。
 *
 * 行本身是 ListRow 样式（与「我的」页一致），D-Pad 上下即可遍历，
 * 点进去就是各功能自己的 Activity。
 */
class FeatureRegistryAdapter(
    private val groups: List<Pair<String, List<FeatureModule>>>,
    private val onOpen: (FeatureModule) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_GROUP = 0
        const val TYPE_ITEM = 1
    }

    /** 扁平化后的行：分组头 or 功能项。 */
    private val rows: List<Any> = buildList {
        groups.forEach { (group, modules) ->
            if (modules.isNotEmpty()) {
                add(group)
                addAll(modules)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is String) TYPE_GROUP else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GROUP) {
            GroupHolder(ItemFeatureGroupBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemFeatureRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is String -> (holder as GroupHolder).bind(row)
            is FeatureModule -> (holder as ItemHolder).bind(row)
        }
    }

    private class GroupHolder(private val binding: ItemFeatureGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(name: String) {
            binding.groupTitle.text = name
        }
    }

    private inner class ItemHolder(private val binding: ItemFeatureRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(module: FeatureModule) {
            binding.itemTitle.text = module.title
            binding.itemDesc.text = module.description
            binding.itemId.text = module.id
            binding.itemId.visibility = View.VISIBLE
            binding.root.setOnClickListener { onOpen(module) }
            binding.root.contentDescription =
                binding.root.context.getString(R.string.feature_lab_item, module.title)
        }
    }
}