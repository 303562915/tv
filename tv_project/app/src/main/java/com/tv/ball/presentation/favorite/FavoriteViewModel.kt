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
 * 重命名分类的结果。分类没有独立表，「分类存在」就等于「至少有一条收藏用了这个名字」，
 * 所以几种失败原因必须分开表达，UI 才能给出准确提示（只回 Boolean 就只能说一句「失败」）。
 */
sealed interface CategoryRenameResult {
    /** 成功。[count] 是受影响的收藏条数；[merged] 为 true 表示并进了已存在的同名分类。 */
    data class Renamed(val category: String, val count: Int, val merged: Boolean) : CategoryRenameResult

    /** 新名字跟旧的完全一样，不用写库。 */
    object Unchanged : CategoryRenameResult

    /** 规范化后为空串。 */
    object EmptyName : CategoryRenameResult

    /** 超过名称长度上限。 */
    object TooLong : CategoryRenameResult

    /** 撞上「全部」「未分类」这类筛选名。 */
    object ReservedName : CategoryRenameResult

    /** 旧分类名下已经没有任何收藏了。 */
    object NotFound : CategoryRenameResult
}

/** 删除分类的结果：只清分类名，收藏本体不动。 */
sealed interface CategoryDeleteResult {
    /** [count] 是退回「未分类」的收藏条数（0 条等于这个分类本来就空了）。 */
    data class Cleared(val category: String, val count: Int) : CategoryDeleteResult

    /** 「未分类」是筛选哨兵，不是分类，不能删。 */
    object ReservedName : CategoryDeleteResult

    /** 这个名字下已经没有收藏了。 */
    object NotFound : CategoryDeleteResult
}

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

    private val _categoryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * 各分类的收藏条数：key 是分类名，[UNCATEGORIZED]（空串）这个 key 表示未分类。
     *
     * 必须用未筛选的全量数据数出来 —— [favoriteList] 已经按当前筛选裁剪过，
     * 拿它统计只会得到「当前分类 N 条、其余 0 条」。数据来自同一条 getAllFavorites，
     * 所以不需要为每个分类单独查库。
     */
    val categoryCounts: StateFlow<Map<String, Int>> = _categoryCounts

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

    /**
     * 重命名分类：把 [from] 下的收藏整体改名成 [to]。
     *
     * 撞名（[to] 已经存在于分类条上）走**合并**，不拒绝：分类名只是收藏行上的自由文本，
     * 没有独立表，「改成已有的名字」在数据上就等于把两组并成一组，一条收藏都不会丢，
     * 用户想要的结果（两组片子放在一起）一次就能达成。反过来拒绝只会逼人先去把重名的
     * 分类改名或清空，多一步操作却没多出任何信息。
     *
     * 是 suspend：调用方（Fragment）要拿返回值决定 Toast 文案。
     */
    suspend fun renameCategory(from: String, to: String): CategoryRenameResult {
        val source = from.trim()
        // 空串是「未分类」的哨兵，本来就不是分类，没有可改的名字
        if (source.isEmpty() || source in RESERVED_CATEGORY_NAMES) {
            return CategoryRenameResult.ReservedName
        }

        val typed = to.trim()
        if (typed.isEmpty()) return CategoryRenameResult.EmptyName
        if (typed.length > MAX_CATEGORY_NAME_LENGTH) return CategoryRenameResult.TooLong
        val target = normalizeCategoryName(typed) ?: return CategoryRenameResult.EmptyName
        if (target in RESERVED_CATEGORY_NAMES) return CategoryRenameResult.ReservedName
        if (target == source) return CategoryRenameResult.Unchanged

        val merged = target in _categories.value
        val affected = favoriteDao.renameCategory(source, target)
        if (affected == 0) return CategoryRenameResult.NotFound

        // 正停在这个分类上就跟着挪到新名字，否则 applySnapshot 会把旧名字判成失效并弹回「全部」
        if (_selectedCategory.value == source) _selectedCategory.value = target
        if (_pendingCategory.value == source) _pendingCategory.value = target
        loadFavorites()
        return CategoryRenameResult.Renamed(target, affected, merged)
    }

    /**
     * 删除分类：把 [category] 下的收藏退回「未分类」，**收藏本身一条都不删**。
     * 分类是从收藏数据反推出来的，名字清空后这个分类自然就从分类条上消失了。
     *
     * 是 suspend：调用方要拿到条数才能说清「几条退回未分类」。
     */
    suspend fun deleteCategory(category: String): CategoryDeleteResult {
        val name = category.trim()
        if (name.isEmpty() || name in RESERVED_CATEGORY_NAMES) {
            return CategoryDeleteResult.ReservedName
        }

        val affected = favoriteDao.clearCategory(name)
        // 正停在这个分类上就回「全部」，别把用户留在一个已经不存在（但看起来还选中）的筛选里
        if (_selectedCategory.value == name) _selectedCategory.value = null
        if (_pendingCategory.value == name) _pendingCategory.value = null
        loadFavorites()
        if (affected == 0) return CategoryDeleteResult.NotFound
        return CategoryDeleteResult.Cleared(name, affected)
    }

    /**
     * 删一条收藏：用列表项的主键 id 走 DAO 的 @Delete，只删这一行。
     * 不用 deleteFavoriteByVideoId —— 那是按业务 id 批量删，遇到同 videoId 的多条会一起删掉。
     */
    fun deleteFavorite(item: FavoriteItem) {
        viewModelScope.launch {
            favoriteDao.deleteFavorite(item.toEntity())
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
            favoriteDao.insertFavorite(item.toEntity())
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

        // 顺手在全量上数一遍各分类条数（一次遍历，不额外查库），供分类条 chip 显示
        val counts = HashMap<String, Int>(categories.size + 1)
        for (entity in snapshot.all) {
            val key = entity.category?.takeIf { it.isNotBlank() } ?: UNCATEGORIZED
            counts[key] = (counts[key] ?: 0) + 1
        }
        _categoryCounts.value = counts

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

    /**
     * 列表项 → 实体：删除（@Delete 按主键 id）与撤销（REPLACE 原样写回）共用这一份映射，
     * 字段一一对应，别在两处各写一遍。
     */
    private fun FavoriteItem.toEntity(): FavoriteEntity = FavoriteEntity(
        id = id,
        videoId = videoId,
        title = title,
        thumbnailUrl = thumbnailUrl,
        detailUrl = detailUrl,
        episodesJson = episodesJson,
        parsedVideoUrl = parsedVideoUrl,
        videoHeaders = videoHeaders,
        addedAt = addedAt,
        category = category
    )

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

        /**
         * 分类条上的两个筛选名：它们不是真实分类，只是「全部」「未分类」两种视图。
         * 允许分类改成这两个名字，分类条上会出现两个同名 chip、点谁都说不清筛的是哪个，
         * 所以重命名 / 删除两个入口都要先挡掉。
         */
        private val RESERVED_CATEGORY_NAMES = setOf("全部", "未分类")
    }
}