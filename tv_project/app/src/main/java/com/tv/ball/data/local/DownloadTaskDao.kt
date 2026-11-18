package com.tv.ball.data.local

import androidx.room.*
import com.tv.ball.data.model.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createTime DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>
    
    @Query("SELECT * FROM download_tasks ORDER BY createTime DESC")
    suspend fun getAllTasksOnce(): List<DownloadTaskEntity>
    
    @Query("SELECT * FROM download_tasks WHERE id = :taskId")
    suspend fun getTask(taskId: Long): DownloadTaskEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)
    
    @Update
    suspend fun updateTask(task: DownloadTaskEntity)
    
    @Delete
    suspend fun deleteTask(task: DownloadTaskEntity)
    
    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)
}
