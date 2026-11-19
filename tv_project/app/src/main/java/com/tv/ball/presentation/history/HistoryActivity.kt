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
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)

        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtils.setupTransparentStatusBar(this)

        binding.btnBack.setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.historyContainer, HistoryFragment())
                .commit()
        }
    }
}
