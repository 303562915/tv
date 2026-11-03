package com.tv.ball.data.model

data class MediaResource(
    val url: String,
    val type: MediaType,
    val size: String? = null,
    val title: String? = null,
    val mimeType: String? = null
) {
    enum class MediaType {
        VIDEO,
        AUDIO,
        M3U8,
        UNKNOWN
    }

    companion object {
        fun fromUrl(url: String, mimeType: String? = null): MediaResource? {
            val type = when {
                url.contains(".m3u8", ignoreCase = true) -> MediaType.M3U8
                url.contains(".mp4", ignoreCase = true) ||
                url.contains(".mkv", ignoreCase = true) ||
                url.contains(".avi", ignoreCase = true) ||
                url.contains(".flv", ignoreCase = true) ||
                mimeType?.startsWith("video/") == true -> MediaType.VIDEO
                url.contains(".mp3", ignoreCase = true) ||
                url.contains(".aac", ignoreCase = true) ||
                mimeType?.startsWith("audio/") == true -> MediaType.AUDIO
                else -> return null
            }

            return MediaResource(
                url = url,
                type = type,
                mimeType = mimeType
            )
        }
    }
}
