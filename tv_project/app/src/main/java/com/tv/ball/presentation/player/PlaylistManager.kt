package com.tv.ball.presentation.player

import com.tv.ball.domain.model.Episode

/**
 * 播放列表管理器
 */
class PlaylistManager {
    private var playlist: List<Episode> = emptyList()
    private var currentIndex: Int = 0
    
    fun setPlaylist(episodes: List<Episode>, startIndex: Int = 0) {
        playlist = episodes
        currentIndex = startIndex.coerceIn(0, episodes.size - 1)
    }
    
    fun getCurrentEpisode(): Episode? {
        return playlist.getOrNull(currentIndex)
    }
    
    fun getCurrentIndex(): Int = currentIndex
    
    fun getPlaylistSize(): Int = playlist.size
    
    fun hasNext(): Boolean = currentIndex < playlist.size - 1
    
    fun hasPrevious(): Boolean = currentIndex > 0
    
    fun next(): Episode? {
        if (hasNext()) {
            currentIndex++
            return getCurrentEpisode()
        }
        return null
    }
    
    fun previous(): Episode? {
        if (hasPrevious()) {
            currentIndex--
            return getCurrentEpisode()
        }
        return null
    }
    
    fun selectEpisode(index: Int): Episode? {
        if (index in playlist.indices) {
            currentIndex = index
            return getCurrentEpisode()
        }
        return null
    }
    
    fun getPlaylist(): List<Episode> = playlist
    
    fun clear() {
        playlist = emptyList()
        currentIndex = 0
    }
}
