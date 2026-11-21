package com.tv.ball.presentation.history

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityHistoryBinding
import com.tv.ball.utils.StatusBarUtils

/**
 * 观看历史独立页面。
 *
 * 历史不再占底栏位置，从「我的」或首页右上角进入。
 * 页面只保留一层工具条：返回 + 标题 + 清空。清空动作由 [HistoryFragment] 注册回调后执行，
 * 列表为空时按钮置灰，避免点进去只弹一句「暂无历史记录」。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    /** 由 [HistoryFragment] 注册；为空表示当前列表没有可清空的内容 */
    var onClearAll: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)

        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtils.setupTransparentStatusBar(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener { onClearAll?.invoke() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.historyContainer, HistoryFragment())
                .commit()
        }
    }

    fun setClearAllEnabled(enabled: Boolean) {
        if (!::binding.isInitialized) return
        binding.btnClearAll.isEnabled = enabled
        binding.btnClearAll.alpha = if (enabled) 1f else 0.4f
    }
}
