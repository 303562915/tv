package com.tv.ball.presentation.feature

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.databinding.ActivityFeatureRegistryBinding
import com.tv.ball.feature.FeatureCatalog
import com.tv.ball.utils.StatusBarUtils

/**
 * 功能中心：把 [FeatureCatalog] 里的功能按分组列出来，点一行进对应功能。
 *
 * 只做外壳，不认识任何具体功能；功能之间因此可以独立开发、独立提交。
 */
class FeatureRegistryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeatureRegistryBinding
    private lateinit var adapter: FeatureRegistryAdapter

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, FeatureRegistryActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityFeatureRegistryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarUtils.setupTransparentStatusBar(this)

        adapter = FeatureRegistryAdapter(FeatureCatalog.grouped()) { module ->
            module.open(this)
        }
        binding.featureList.adapter = adapter
        binding.featureList.itemAnimator = null

        val empty = FeatureCatalog.entries.isEmpty()
        binding.emptyHint.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        binding.featureList.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
    }
}
