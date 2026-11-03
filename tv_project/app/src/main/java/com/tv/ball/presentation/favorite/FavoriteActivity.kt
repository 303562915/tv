package com.tv.ball.presentation.favorite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.databinding.ActivityFavoriteBinding

class FavoriteActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityFavoriteBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoriteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainer.id, FavoriteFragment())
                .commit()
        }
    }
}
