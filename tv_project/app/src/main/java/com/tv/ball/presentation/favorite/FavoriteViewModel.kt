package com.tv.ball.presentation.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.data.local.FavoriteDao
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FavoriteItem(
    val id: Long,
    val videoId: Long,
    val title: String,
    val thumbnailUrl: String?,
    val detailUrl: String?,
    val episodesJson: String?,
    val parsedVideoUrl: String?,
    val videoHeaders: String?,
    val addedAt: Long
)

class FavoriteViewModel(
    private val favoriteDao: FavoriteDao = Dependencies.database.favoriteDao()
) : ViewModel() {
    
    private val _favoriteList = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val favoriteList: StateFlow<List<FavoriteItem>> = _favoriteList
    
    fun loadFavorites() {
        viewModelScope.launch {
            favoriteDao.getAllFavorites().collect { favoriteEntities ->
                _favoriteList.value = favoriteEntities.map { entity ->
                    FavoriteItem(
                        id = entity.id,
                        videoId = entity.videoId,
                        title = entity.title,
                        thumbnailUrl = entity.thumbnailUrl,
                        detailUrl = entity.detailUrl,
                        episodesJson = entity.episodesJson,
                        parsedVideoUrl = entity.parsedVideoUrl,
                        videoHeaders = entity.videoHeaders,
                        addedAt = entity.addedAt
                    )
                }
            }
        }
    }
    
    fun deleteFavorite(videoId: Long) {
        viewModelScope.launch {
            favoriteDao.deleteFavoriteByVideoId(videoId)
        }
    }
    
    fun clearAllFavorites() {
        viewModelScope.launch {
            favoriteDao.deleteAllFavorites()
        }
    }
}
