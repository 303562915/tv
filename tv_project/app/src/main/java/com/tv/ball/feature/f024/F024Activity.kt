package com.tv.ball.feature.f024

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF024Binding

/**
 * F024 SAF 目录授权管理页面。
 *
 * 点「选择目录」走 [ActivityResultContracts.OpenDocumentTree] 拿 tree Uri，
 * 交给 [F024Engine.persist] 转成持久授权；下方列表来自
 * [F024Engine.persistedGrants]，每条都能单独移除。
 */
class F024Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF024Binding
    private val grants = ArrayList<F024Engine.Grant>()

    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                toast(getString(R.string.f024_toast_cancelled))
                return@registerForActivityResult
            }
            handlePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF024Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickDirectory.setOnClickListener { pickDirectory.launch(null) }
        renderGrants()
    }

    // region 选目录 + 持久化

    private fun handlePicked(uri: Uri) {
        val outcome = F024Engine.persist(contentResolver, uri)
        if (outcome.ok) {
            toast(getString(R.string.f024_toast_persist_ok, uri.toString()))
        } else {
            toast(getString(R.string.f024_toast_persist_fail, outcome.reason))
        }
        renderGrants()
    }

    // endregion

    // region 列表渲染

    private fun renderGrants() {
        grants.clear()
        grants.addAll(F024Engine.persistedGrants(contentResolver))

        binding.grantContainer.removeAllViews()
        binding.grantCount.text = getString(R.string.f024_grant_count, grants.size)

        if (grants.isEmpty()) {
            binding.emptyHint.visibility = View.VISIBLE
            binding.grantContainer.visibility = View.GONE
            return
        }

        binding.emptyHint.visibility = View.GONE
        binding.grantContainer.visibility = View.VISIBLE

        val gaps = (resources.displayMetrics.density * 8).toInt()
        grants.forEachIndexed { index, grant ->
            binding.grantContainer.addView(buildRow(grant, index == 0, gaps))
        }
    }

    private fun buildRow(grant: F024Engine.Grant, first: Boolean, gap: Int): View {
        val density = resources.displayMetrics.density
        val padding = (density * 12).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_mode_row)
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (first) 0 else gap }
        }

        val label = TextView(this).apply {
            text = getString(R.string.f024_grant_line, grant.uriString, flagsText(grant))
            setTextColor(ContextCompat.getColor(this@F024Activity, R.color.theme_text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val remove = Button(this).apply {
            text = getString(R.string.f024_remove_action)
            isAllCaps = false
            setOnClickListener { removeGrant(grant) }
        }

        row.addView(label)
        row.addView(remove)
        return row
    }

    private fun flagsText(grant: F024Engine.Grant): String = when {
        grant.isRead && grant.isWrite -> getString(R.string.f024_flag_read_write)
        grant.isRead -> getString(R.string.f024_flag_read_only)
        else -> getString(R.string.f024_flag_write_only)
    }

    // endregion

    // region 撤销

    private fun removeGrant(grant: F024Engine.Grant) {
        val outcome = F024Engine.release(contentResolver, grant.uri)
        if (outcome.ok) {
            toast(getString(R.string.f024_toast_released, grant.uriString))
        } else {
            toast(getString(R.string.f024_toast_release_fail, outcome.reason))
        }
        renderGrants()
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}