package com.tv.ball.presentation.localvideo

import com.tv.ball.di.Dependencies

/**
 * 本地视频播放进度 ↔ 历史记录。
 *
 * 播放器（presentation/player）对本地文件只写 [com.tv.ball.domain.repository.LocalVideoProgressManager]，
 * 不写 history 表；本地视频这边读同一份进度，把进度镜像进历史，走的是现成的写入路径：
 *
 * `HistoryRepository.addOrUpdateHistory` → `LocalDataSource.insertHistory` → `HistoryDao.insertHistory`。
 *
 * 本地视频没有 videoId，key 用「文件路径的稳定哈希」：
 * - `videoSource = "local:<路径稳定哈希>"`：history 表唯一键是 videoTitle + currentEpisode + videoSource，
 *   哈希固定，同一路径每次落到同一行（更新而不是新增）；
 * - `currentEpisode = "本地视频"`：历史列表直接显示这一行时是人话，不暴露哈希；
 * - `videoUrl = "file://<路径>"`：从历史点回本地文件仍然能播；
 * - `episodesJson` 写成单集列表：历史页判断「有剧集」就会直达播放器，不会绕去详情页。
 */
internal object LocalVideoPlaybackHistory {

    /** 历史表里本地视频的来源前缀，后面拼路径的稳定哈希。 */
    const val SOURCE_PREFIX = "local:"

    /** 历史列表里显示的集数文案（历史表里是「本地视频」这一个固定值）。 */
    private const val EPISODE_LABEL = "本地视频"

    /** 同一路径每次算出来都一样，跨进程、跨重启不变（FNV-1a 64，不用 String.hashCode 的 32 位）。 */
    fun stableHash(path: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 位 offset basis
        for (ch in path) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toUnsignedString(hash)
    }

    /** 历史表里的 videoSource：local:<稳定哈希>。 */
    fun sourceKey(path: String): String = SOURCE_PREFIX + stableHash(path)

    /** 播放器认 file:// 前缀，历史里存的也统一成这个形态。 */
    fun fileUrl(path: String): String =
        if (path.startsWith("file://")) path else "file://$path"

    /** 上次的播放位置（毫秒）：读播放器写的进度管理器，没有记录就是 0。 */
    fun lastPosition(path: String): Long = try {
        if (path.isBlank()) 0L else Dependencies.localVideoProgressManager.getProgress(path)
    } catch (e: Throwable) {
        android.util.Log.e("LocalVideoPlaybackHistory", "读取本地视频进度失败: ${e.message}", e)
        0L
    }

    /** 上次的总时长（毫秒）：同样来自进度管理器。 */
    fun lastDuration(path: String): Long = try {
        if (path.isBlank()) 0L else Dependencies.localVideoProgressManager.getDuration(path)
    } catch (e: Throwable) {
        android.util.Log.e("LocalVideoPlaybackHistory", "读取本地视频时长失败: ${e.message}", e)
        0L
    }

    /**
     * 把一条本地视频的进度镜像进历史。
     *
     * @param name 文件名（历史列表的标题）
     * @param path 真实文件路径（同时决定 videoSource 的哈希 key）
     * @param position 已观看位置（毫秒）；<= 0 说明还没真正播放过，不写行，免得历史里堆空记录
     * @param duration 总时长（毫秒）
     */
    suspend fun record(name: String, path: String, position: Long, duration: Long) {
        if (path.isBlank() || position <= 0L) return
        val title = name.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
        try {
            Dependencies.historyRepository.addOrUpdateHistory(
                videoTitle = title,
                videoUrl = fileUrl(path),
                parsedVideoUrl = null,
                videoHeaders = null,
                thumbnailUrl = "",
                watchedPosition = position,
                duration = duration.coerceAtLeast(0L),
                detailUrl = null,
                episodesJson = singleEpisodeJson(path),
                currentEpisode = EPISODE_LABEL,
                videoSource = sourceKey(path)
            )
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoPlaybackHistory", "写入本地视频历史失败: ${e.message}", e)
        }
    }

    /** 等价于「有剧集」分支的最小 episodesJson：[{"name":"本地视频","url":"file://<路径>"}]。 */
    private fun singleEpisodeJson(path: String): String =
        "[{\"name\":${quote(EPISODE_LABEL)},\"url\":${quote(fileUrl(path))}}]"

    /** 最小 JSON 字符串转义：路径里出现反斜杠 / 引号时不会把 JSON 弄坏。 */
    private fun quote(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
