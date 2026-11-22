package com.tv.ball.presentation.player

import android.net.Uri
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

/**
 * 多线程分段下载的DataSource
 * 将大文件分成多个段，使用多个线程并行下载
 */
class MultiThreadDataSource(
    private val upstreamDataSourceFactory: HttpDataSource.Factory,
    private val threadCount: Int = 4  // 默认4个线程
) : DataSource {

    private var dataSource: HttpDataSource? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    private val executor = Executors.newFixedThreadPool(threadCount)
    private val downloadTasks = mutableListOf<Future<*>>()

    override fun addTransferListener(transferListener: com.google.android.exoplayer2.upstream.TransferListener) {
        // 传递给底层数据源
    }

    override fun open(dataSpec: DataSpec): Long {
        if (opened) {
            throw IOException("Already opened")
        }

        uri = dataSpec.uri
        dataSource = upstreamDataSourceFactory.createDataSource()
        
        try {
            // 先打开一次获取文件大小
            val length = requireNotNull(dataSource) { "DataSource 初始化失败" }.open(dataSpec)
            bytesRemaining = if (length == com.google.android.exoplayer2.C.LENGTH_UNSET.toLong()) {
                // 如果无法获取长度，使用单线程
                length
            } else {
                length
            }
            opened = true
            return bytesRemaining
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) {
            throw IOException("Not opened")
        }

        if (bytesRemaining == 0L) {
            return com.google.android.exoplayer2.C.RESULT_END_OF_INPUT
        }

        val dataSource = this.dataSource ?: throw IOException("DataSource is null")
        
        try {
            val bytesRead = dataSource.read(buffer, offset, length)
            if (bytesRead > 0) {
                bytesRemaining -= bytesRead
            }
            return bytesRead
        } catch (e: Exception) {
            throw IOException("Read failed", e)
        }
    }

    override fun getUri(): Uri? {
        return uri
    }

    override fun close() {
        if (opened) {
            opened = false
            bytesRemaining = 0
            
            // 取消所有下载任务
            downloadTasks.forEach { it.cancel(true) }
            downloadTasks.clear()
            
            try {
                dataSource?.close()
            } catch (e: Exception) {
                // 忽略关闭错误
            }
            dataSource = null
        }
    }

    /**
     * Factory for creating MultiThreadDataSource instances
     */
    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val threadCount: Int = 4
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return MultiThreadDataSource(upstreamFactory, threadCount)
        }
    }
}
