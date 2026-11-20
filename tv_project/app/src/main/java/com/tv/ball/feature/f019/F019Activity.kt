package com.tv.ball.feature.f019

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF019Binding

/**
 * F019 媒体库增量索引页面。
 *
 * 内置一份旧索引；当前快照可增 / 删 / 改，点「增量扫描」后按新增、变更、消失、
 * 未变四类给出计数与逐条明细，并用 [F019Engine.applyTo] 推算合并后的索引规模。
 */
class F019Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF019Binding
    private val oldIndex: Map<String, F019Engine.Entry> = buildOldIndex()
    private val snapshot = ArrayList<F019Engine.Entry>()
    private val labels = ArrayList<String>()
    private var adapter: ArrayAdapter<String>? = null
    private var selectedIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF019Binding.inflate(layoutInflater)
        setContentView(binding.root)

        snapshot.addAll(oldIndex.values)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.snapshotList.adapter = adapter
        binding.snapshotList.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            toast(getString(R.string.f019_toast_selected, snapshot[position].path))
        }

        binding.btnAdd.setOnClickListener { showEditDialog(null) }
        binding.btnEdit.setOnClickListener { selectedEntry()?.let { showEditDialog(it) } }
        binding.btnDelete.setOnClickListener { deleteSelected() }
        binding.btnReset.setOnClickListener { resetSnapshot() }
        binding.btnScan.setOnClickListener { scan() }

        binding.oldSummary.text = getString(R.string.f019_old_count, oldIndex.size)
        renderSnapshot()
        clearResult()
        selectedIndex = -1
    }

    // region 内置旧索引

    private fun buildOldIndex(): Map<String, F019Engine.Entry> {
        val index = LinkedHashMap<String, F019Engine.Entry>()
        val builtIn = listOf(
            F019Engine.Entry("/storage/emulated/0/Movies/流浪地球.mkv", 4_294_967_296L, 1_700_000_000_000L),
            F019Engine.Entry("/storage/emulated/0/Movies/星际穿越.mkv", 3_221_225_472L, 1_700_100_000_000L),
            F019Engine.Entry("/storage/emulated/0/TV/三体/S01E01.mp4", 734_003_200L, 1_700_200_000_000L),
            F019Engine.Entry("/storage/emulated/0/TV/三体/S01E02.mp4", 812_646_400L, 1_700_200_100_000L),
            F019Engine.Entry("/storage/emulated/0/Music/片头曲.flac", 41_943_040L, 1_700_300_000_000L)
        )
        for (entry in builtIn) index[entry.path] = entry
        return index
    }

    // endregion

    // region 快照渲染

    private fun renderSnapshot() {
        labels.clear()
        for (entry in snapshot) labels.add(formatEntry(entry))
        adapter?.notifyDataSetChanged()
        binding.snapshotCount.text = getString(R.string.f019_snapshot_count, snapshot.size)
        if (selectedIndex >= snapshot.size) selectedIndex = -1
    }

    private fun formatEntry(entry: F019Engine.Entry): String =
        getString(R.string.f019_entry_line, entry.path, entry.size, entry.mtimeMs)

    private fun selectedEntry(): F019Engine.Entry? {
        if (selectedIndex in snapshot.indices) return snapshot[selectedIndex]
        toast(getString(R.string.f019_toast_need_selection))
        return null
    }

    // endregion

    // region 增 / 删 / 改

    private fun showEditDialog(existing: F019Engine.Entry?) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val pathInput = makeInput(
            getString(R.string.f019_label_path),
            existing?.path.orEmpty(),
            InputType.TYPE_CLASS_TEXT
        )
        val sizeInput = makeInput(
            getString(R.string.f019_label_size),
            existing?.size?.toString() ?: "0",
            InputType.TYPE_CLASS_NUMBER
        )
        val mtimeInput = makeInput(
            getString(R.string.f019_label_mtime),
            existing?.mtimeMs?.toString() ?: "0",
            InputType.TYPE_CLASS_NUMBER
        )
        container.addView(pathInput)
        container.addView(sizeInput)
        container.addView(mtimeInput)

        val titleRes = if (existing == null) R.string.f019_dialog_add_title else R.string.f019_dialog_edit_title
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.f019_action_ok) { _, _ ->
                val path = pathInput.text.toString().trim()
                val size = sizeInput.text.toString().trim().toLongOrNull()
                val mtime = mtimeInput.text.toString().trim().toLongOrNull()
                if (path.isEmpty() || size == null || mtime == null || size < 0L || mtime < 0L) {
                    toast(getString(R.string.f019_toast_bad_input))
                    return@setPositiveButton
                }
                val entry = F019Engine.Entry(path, size, mtime)
                if (existing == null) {
                    snapshot.add(entry)
                } else {
                    val position = snapshot.indexOfFirst { it.path == existing.path }
                    if (position >= 0) snapshot[position] = entry else snapshot.add(entry)
                }
                selectedIndex = -1
                renderSnapshot()
                toast(getString(R.string.f019_toast_saved, entry.path))
            }
            .setNegativeButton(R.string.f019_action_cancel, null)
            .show()
    }

    private fun makeInput(hint: String, value: String, inputType: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            this.inputType = inputType
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun deleteSelected() {
        val entry = selectedEntry() ?: return
        snapshot.removeAt(selectedIndex)
        selectedIndex = -1
        renderSnapshot()
        toast(getString(R.string.f019_toast_deleted, entry.path))
    }

    private fun resetSnapshot() {
        snapshot.clear()
        snapshot.addAll(oldIndex.values)
        selectedIndex = -1
        renderSnapshot()
        clearResult()
    }

    // endregion

    // region 增量扫描

    private fun scan() {
        if (snapshot.isEmpty()) {
            toast(getString(R.string.f019_toast_empty_snapshot))
            return
        }
        val diff = F019Engine.diff(oldIndex, snapshot)
        val nextIndex = F019Engine.applyTo(oldIndex, diff)
        binding.countAdded.text = getString(R.string.f019_count_added, diff.addedCount)
        binding.countUpdated.text = getString(R.string.f019_count_updated, diff.updatedCount)
        binding.countRemoved.text = getString(R.string.f019_count_removed, diff.removedCount)
        binding.countUnchanged.text = getString(R.string.f019_count_unchanged, diff.unchangedCount)
        binding.detailAdded.text = describe(diff.added)
        binding.detailUpdated.text = describe(diff.updated)
        binding.detailRemoved.text = describe(diff.removed)
        binding.detailUnchanged.text = describe(diff.unchanged)
        binding.resultSummary.text = getString(R.string.f019_result_summary, nextIndex.size)
        val touched = diff.addedCount + diff.updatedCount + diff.removedCount
        toast(getString(R.string.f019_toast_scanned, touched))
    }

    private fun describe(entries: List<F019Engine.Entry>): String =
        if (entries.isEmpty()) getString(R.string.f019_empty_item)
        else entries.joinToString("\n") { formatEntry(it) }

    private fun clearResult() {
        binding.countAdded.text = getString(R.string.f019_count_added, 0)
        binding.countUpdated.text = getString(R.string.f019_count_updated, 0)
        binding.countRemoved.text = getString(R.string.f019_count_removed, 0)
        binding.countUnchanged.text = getString(R.string.f019_count_unchanged, 0)
        binding.detailAdded.text = getString(R.string.f019_empty_item)
        binding.detailUpdated.text = getString(R.string.f019_empty_item)
        binding.detailRemoved.text = getString(R.string.f019_empty_item)
        binding.detailUnchanged.text = getString(R.string.f019_empty_item)
        binding.resultSummary.text = getString(R.string.f019_result_hint)
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}