package com.tv.ball.data.local

import androidx.room.*
import com.tv.ball.data.model.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    /**
     * 按分类过滤：只返回 category 严格等于 [category] 的记录。
     * 「未分类」请用 [getUncategorizedFavorites]（库里未分类既可能是 NULL 也可能是空串）。
     */
    @Query("SELECT * FROM favorites WHERE category = :category ORDER BY addedAt DESC")
    fun getFavoritesByCategory(category: String): Flow<List<FavoriteEntity>>

    /** 仅「未分类」：category 为 NULL 或空串。 */
    @Query("SELECT * FROM favorites WHERE category IS NULL OR category = '' ORDER BY addedAt DESC")
    fun getUncategorizedFavorites(): Flow<List<FavoriteEntity>>

    /**
     * @Query("SELECT * FROM favorites WHERE (:category IS NULL OR category = :category) ORDER BY addedAt DESC")
     * 等价的单条写法：category 传 null 时退化成「全部」。这里拆成上面三条更明确的分支，
     * 避免 NULL 既表示「全部」又表示「未分类」的歧义。
     */

    /** 已存在的分类名（去空、去重、按名称排序），分类列表由收藏数据反推，不单独建表。 */
    @Query("SELECT DISTINCT category FROM favorites WHERE category IS NOT NULL AND category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    /** 设置某条收藏的分类；[category] 传 null 表示移回「未分类」。 */
    @Query("UPDATE favorites SET category = :category WHERE videoId = :videoId")
    suspend fun updateCategory(videoId: Long, category: String?)

    @Query("SELECT * FROM favorites WHERE videoId = :videoId LIMIT 1")
    suspend fun getFavoriteByVideoId(videoId: Long): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE videoId = :videoId)")
    suspend fun isFavorite(videoId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE videoId = :videoId")
    suspend fun deleteFavoriteByVideoId(videoId: Long)

    @Query("DELETE FROM favorites")
    suspend fun deleteAllFavorites()
}