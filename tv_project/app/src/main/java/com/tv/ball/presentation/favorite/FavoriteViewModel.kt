package com.tv.ball.presentation.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.data.local.FavoriteDao
import com.tv.ball.data.model.FavoriteEntity
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val addedAt: Long,
    /** 用户自定义分类名；null 表示未分类。 */
    val category: String? = null
)

/**
 * 「我的收藏」+ 分类。
 *
 * 分类约定（全页统一，Fragment / Adapter 都按这个约定理解）：
 * - 数据库 `favorites.category`：自由文本，**null（或空串）表示未分类**；没有独立分类表，
 *   分类列表是 `SELECT DISTINCT category` 从收藏数据反推出来的，所以不存在「空分类」。
 * - [selectedCategory]：**null = 全部**；[UNCATEGORIZED]（空串哨兵）= 只看未分类；
 *   其他字符串 = 只看该分类。也就是 null 与 "" 语义不同，别混用。
 */
class FavoriteViewModel(
    private val favoriteDao: FavoriteDao = Dependencies.database.favoriteDao()
) : ViewModel() {

    private val _favoriteList = MutableStateFlow<List<FavoriteItem>>(emptyList())

    /** 按当前分类筛选后的收藏列表（全部 / 某分类 / 未分类）。 */
    val favoriteList: StateFlow<List<FavoriteItem>> = _favoriteList

    private val _categories = MutableStateFlow<List<String>>(emptyList())

    /** 分类条上的分类名（不含「全部」「未分类」，不含「＋ 新建」）。 */
    val categories: StateFlow<List<String>> = _categories

    private val _selectedCategory = MutableStateFlow<String?>(null)

    /** null = 全部；空串 = 未分类；其它 = 具体分类名。 */
    val selectedCategory: StateFlow<String?> = _selectedCategory

    private val _hasUncategorized = MutableStateFlow(false)

    /** 是否存在未分类收藏（决定「未分类」chip 显不显示）。 */
    val hasUncategorized: StateFlow<Boolean> = _hasUncategorized

    private val _hasFavorites = MutableStateFlow(false)

    /** 是否有任何收藏（不受当前筛选影响），用于「清空」按钮可用性。 */
    val hasFavorites: StateFlow<Boolean> = _hasFavorites

    /**
     * 刚新建、但数据还没落库的分类名。只存在内存里，用来让新分类的 chip 立刻出现、
     * 避免「选了新分类却被判成不存在的分类」的竞态；一旦库里有了同名分类就清掉。
     */
    private val _pendingCategory = MutableStateFlow<String?>(null)

    private var loadJob: Job? = null

    init {
        // 只在 ViewModel 创建时订阅一次：Fragment 重建时重复调 loadFavorites() 不会累积 collector
        loadFavorites()
    }

    /**
     * 启动数据订阅：一条 combine 同时驱动分类列表、未分类标记与筛选后的列表。
     * 重复调用无副作用（订阅已经活着就直接返回），保持原有对外语义。
     */
    fun loadFavorites() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            combine(
                favoriteDao.getAllFavorites(),
                favoriteDao.getCategories(),
                _selectedCategory,
                _pendingCategory
            ) { all, dbCategories, selected, pending ->
                Snapshot(all, dbCategories, selected, pending)
            }.collect { snapshot ->
                applySnapshot(snapshot)
            }
        }
    }

    /** 切换筛选：null = 全部，[UNCATEGORIZED] = 未分类。 */
    fun selectCategory(category: String?) {
        // 未分类用空串哨兵；分类名本身不会为空，所以不会撞车
        _selectedCategory.value = if (category == null) null else category.trim()
    }

    /**
     * 把某条收藏移动到 [category]；传 null（或空白名）表示移回「未分类」。
     * 分类名会做 trim / 压缩空白 / 长度校验，非法名称按「未分类」处理。
     */
    fun assignCategory(videoId: Long, category: String?) {
        val normalized = normalizeCategoryName(category)
        if (normalized != null) _pendingCategory.value = normalized
        viewModelScope.launch {
            favoriteDao.updateCategory(videoId, normalized)
        }
    }

    /**
     * 新建分类的名称处理：规范化 + 校验，**不落库**。
     * 分类没有独立表，空分类无处可存；真正「新建」由调用方把某条收藏
     * [assignCategory] 进去完成（通常是长按卡片 → 新建分类）。
     *
     * @return 规范化后的分类名；空白返回 null，超长返回 null（由调用方按提示区分）
     */
    fun createCategory(name: String): String? = normalizeCategoryName(name)

    fun deleteFavorite(videoId: Long) {
        viewModelScope.launch {
            favoriteDao.deleteFavoriteByVideoId(videoId)
        }
    }

    /**
     * 撤销删除：按原样写回一条收藏。
     *
     * 字段全部用原值（id / addedAt / category / 解析缓存都不重置），所以撤销后
     * 这条收藏的位置、分类、播放进度信息跟删除前一致，不会跳到列表最前。
     * id 传原值 → REPLACE 会顶掉同 id 的行（理论上已被删掉，等于原样恢复）；
     * id 为 0（自动生成没回填的极端情况）时由 Room 自增分配。
     */
    fun restoreFavorite(item: FavoriteItem) {
        viewModelScope.launch {
            favoriteDao.insertFavorite(
                FavoriteEntity(
                    id = item.id,
                    videoId = item.videoId,
                    title = item.title,
                    thumbnailUrl = item.thumbnailUrl,
                    detailUrl = item.detailUrl,
                    episodesJson = item.episodesJson,
                    parsedVideoUrl = item.parsedVideoUrl,
                    videoHeaders = item.videoHeaders,
                    addedAt = item.addedAt,
                    category = item.category
                )
            )
        }
    }

    fun clearAllFavorites() {
        viewModelScope.launch {
            favoriteDao.deleteAllFavorites()
        }
    }

    // ===== 内部 =====

    private fun applySnapshot(snapshot: Snapshot) {
        val dbCategories = snapshot.dbCategories
        val categories = buildList {
            addAll(dbCategories)
            val pending = snapshot.pending
            if (pending != null && pending !in dbCategories) add(pending)
            sort()
        }
        _categories.value = categories

        val hasUncategorized = snapshot.all.any { it.category.isNullOrBlank() }
        _hasUncategorized.value = hasUncategorized
        _hasFavorites.value = snapshot.all.isNotEmpty()

        val filtered: List<FavoriteEntity> = when (val selected = snapshot.selected) {
            null -> snapshot.all
            UNCATEGORIZED -> snapshot.all.filter { it.category.isNullOrBlank() }
            else -> snapshot.all.filter { it.category == selected }
        }
        _favoriteList.value = filtered.map { it.toFavoriteItem() }

        // 新建的分类已经落库，内存里的占位可以撤掉（值不变时 StateFlow 不会重复发射）
        val pending = snapshot.pending
        if (pending != null && pending in dbCategories) {
            _pendingCategory.value = null
        }

        // 选中的分类被删空（数据里已经不存在了）时回落到「全部」，避免卡在一个不存在的筛选里
        val selected = snapshot.selected
        if (selected != null) {
            val stillValid = when (selected) {
                UNCATEGORIZED -> hasUncategorized
                else -> selected in categories
            }
            if (!stillValid) _selectedCategory.value = null
        }
    }

    private fun normalizeCategoryName(raw: String?): String? {
        val normalized = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (normalized.isEmpty()) return null
        if (normalized.length > MAX_CATEGORY_NAME_LENGTH) return null
        return normalized
    }

    private fun FavoriteEntity.toFavoriteItem(): FavoriteItem = FavoriteItem(
        id = id,
        videoId = videoId,
        title = title,
        thumbnailUrl = thumbnailUrl,
        detailUrl = detailUrl,
        episodesJson = episodesJson,
        parsedVideoUrl = parsedVideoUrl,
        videoHeaders = videoHeaders,
        addedAt = addedAt,
        category = category?.takeIf { it.isNotBlank() }
    )

    private data class Snapshot(
        val all: List<FavoriteEntity>,
        val dbCategories: List<String>,
        val selected: String?,
        val pending: String?
    )

    companion object {
        /** 「未分类」筛选用的哨兵值（空串）。注意 null 表示「全部」，两者不同。 */
        const val UNCATEGORIZED = ""

        /** 分类名长度上限，跟输入框提示保持一致。 */
        const val MAX_CATEGORY_NAME_LENGTH = 16
    }
}