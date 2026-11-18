package com.tv.ball.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.domain.model.VideoDetail
import com.tv.ball.domain.repository.VideoRepository
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VideoDetailViewModel(
    private val videoRepository: VideoRepository = Dependencies.videoRepository
) : ViewModel() {
    
    private val _videoDetail = MutableStateFlow<VideoDetail?>(null)
    val videoDetail: StateFlow<VideoDetail?> = _videoDetail
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    fun loadVideoDetail(url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val detail = videoRepository.getVideoDetail(url)
                _videoDetail.value = detail
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
