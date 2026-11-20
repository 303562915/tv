package com.tv.ball.feature.f038

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF038Binding

/**
 * 多播放列表切换。
 *
 * 列表切换条是动态生成的（列表数量不固定），当前项用主色描边标出来；
 * 内容区只显示条目 id，因为这个功能负责「换列表」，条目详情归列表详情页。
 */
class F038Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF038Binding
    private lateinit var store: F038Store

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityF038Binding.inflate(layoutInflater)
        setContentView(binding.root)

        store = F038Store.create(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnNew.setOnClickListener { createList() }
        binding.btnRename.setOnClickListener { renameCurrent() }
        render()
    }

    private fun render() {
        val lists = store.all()
        val currentId = store.current()?.id
        val row = binding.listSwitchRow
        row.removeAllViews()

        if (lists.isEmpty()) {
            binding.tvCurrent.text = getString(R.string.f038_current_empty)
            binding.itemContainer.text = getString(R.string.f038_items_empty)
            return
        }

        val inflater = LayoutInflater.from(this)
        lists.forEach { list ->
            val item = inflater.inflate(R.layout.item_feature_row, row, false)
            val title = item.findViewById<TextView>(R.id.itemTitle)
            val desc = item.findViewById<TextView>(R.id.itemDesc)
            val badge = item.findViewById<TextView>(R.id.itemId)
            val isCurrent = list.id == currentId
            title.text = if (isCurrent) {
                getString(R.string.f038_row_current, list.name, list.items.size)
            } else {
                getString(R.string.f038_row, list.name, list.items.size)
            }
            desc.text = if (list.items.isEmpty()) getString(R.string.f038_items_empty)
            else getString(R.string.f038_items, list.items.size, list.items.joinToString("、"))
            badge.text = if (isCurrent) getString(R.string.f038_badge_current) else ""
            item.setOnClickListener {
                store.switchTo(list.id)
                render()
            }
            row.addView(item)
        }

        val current = store.current() ?: return
        binding.tvCurrent.text = getString(R.string.f038_current, current.name)
        binding.itemContainer.text = if (current.items.isEmpty()) {
            getString(R.string.f038_items_empty)
        } else {
            getString(R.string.f038_items, current.items.size, current.items.joinToString("、"))
        }
    }

    private fun createList() {
        val created = store.create("列表 " + (store.all().size + 1), System.currentTimeMillis())
        store.switchTo(created.id)
        toast(getString(R.string.f038_toast_created, created.name))
        render()
    }

    private fun renameCurrent() {
        val current = store.current()
        if (current == null) {
            toast(getString(R.string.f038_current_empty))
            return
        }
        val input = EditText(this).apply {
            setText(current.name)
            hint = getString(R.string.f038_rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.f038_rename_title)
            .setView(input)
            .setPositiveButton(R.string.f038_rename_ok) { _, _ ->
                val name = input.text.toString()
                if (store.rename(current.id, name)) {
                    toast(getString(R.string.f038_toast_renamed, name.trim()))
                    render()
                } else {
                    toast(getString(R.string.f038_toast_blank))
                }
            }
            .setNegativeButton(R.string.f038_rename_cancel, null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 保留一个可复用的聚焦入口：真机上确认键会落到第一个列表行。 */
    private fun focusFirst() {
        val first = binding.listSwitchRow.getChildAt(0) ?: return
        first.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentFocus == null) focusFirst()
    }

    @Suppress("unused")
    private fun unusedView(): View? = binding.root
}
