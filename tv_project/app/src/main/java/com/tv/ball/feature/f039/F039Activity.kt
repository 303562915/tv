package com.tv.ball.feature.f039

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF039Binding

/**
 * 播放列表回收站。
 *
 * 正常列表和回收站各一区，删除走软删除（7 天保留），
 * 「把时间往后拨 8 天」把 nowMs 往前推，用来看过期清理真的会发生。
 */
class F039Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF039Binding
    private val engine = F039Engine()
    private var offsetMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityF039Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine.seedActive(
            listOf(
                TrashEntry("pl-1", "通勤电台", 12, 0L, 0L),
                TrashEntry("pl-2", "周末补番", 5, 0L, 0L),
                TrashEntry("pl-3", "本地试片", 0, 0L, 0L)
            )
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdvanceWeek.setOnClickListener {
            offsetMs += 8L * F039Engine.DAY_MS
            val cleared = engine.purgeExpired(now())
            toast(if (cleared.isEmpty()) getString(R.string.f039_toast_none_expired)
            else getString(R.string.f039_toast_expired, cleared.size))
            render()
        }
        render()
    }

    private fun now(): Long = System.currentTimeMillis() + offsetMs

    private fun render() {
        val inflater = LayoutInflater.from(this)
        val activeBox = binding.activeContainer
        val trashBox = binding.trashContainer
        activeBox.removeAllViews()
        trashBox.removeAllViews()

        val active = engine.snapshotActive()
        val trash = engine.snapshotTrash()

        binding.tvActiveEmpty.visibility = if (active.isEmpty()) View.VISIBLE else View.GONE
        binding.tvTrashEmpty.visibility = if (trash.isEmpty()) View.VISIBLE else View.GONE

        active.forEach { entry ->
            val row = row(inflater, getString(R.string.f039_row, entry.name, entry.itemCount))
            val delete = button(getString(R.string.f039_action_delete))
            delete.setOnClickListener {
                engine.softDelete(entry.playlistId, now())
                toast(getString(R.string.f039_toast_deleted))
                render()
            }
            row.addView(delete)
            activeBox.addView(row)
        }

        trash.forEach { entry ->
            val days = engine.remainingDays(entry, now())
            val row = row(inflater, getString(R.string.f039_row_trash, entry.name, entry.itemCount, days))
            val restore = button(getString(R.string.f039_action_restore))
            restore.setOnClickListener {
                if (engine.restore(entry.playlistId, now())) {
                    toast(getString(R.string.f039_toast_restored, entry.name))
                }
                render()
            }
            val purge = button(getString(R.string.f039_action_purge))
            purge.setOnClickListener {
                engine.purge(entry.playlistId)
                toast(getString(R.string.f039_toast_purged))
                render()
            }
            row.addView(restore)
            row.addView(purge)
            trashBox.addView(row)
        }

        binding.tvStatus.text = getString(R.string.f039_status, trash.size, active.size)
    }

    private fun row(inflater: LayoutInflater, text: String): LinearLayout {
        val row = inflater.inflate(R.layout.item_feature_row, binding.activeContainer, false) as LinearLayout
        row.findViewById<TextView>(R.id.itemTitle).text = text
        row.findViewById<TextView>(R.id.itemDesc).visibility = View.GONE
        row.findViewById<TextView>(R.id.itemId).visibility = View.GONE
        return row
    }

    private fun button(text: String): Button = Button(this).apply {
        this.text = text
        isFocusable = true
        setBackgroundResource(R.drawable.bg_button_secondary)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
