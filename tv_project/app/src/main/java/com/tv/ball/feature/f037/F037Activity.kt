package com.tv.ball.feature.f037

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF037Binding
import com.tv.ball.util.CustomToast

/**
 * 「播放列表封面与描述」页。
 *
 * 三段：
 * 1. **封面**：从系统文档选择器挑一张本地图片，落盘保存 `content://` URI，并尝试拿长期读权限；
 * 2. **名称与描述**：名称单行、描述多行，点「保存」一起写进 [F037Engine.PlaylistMetaStore]；
 * 3. **列表卡片预览**：按列表页那张卡片的排法（封面 + 名称 + 描述 + 条目数）实时渲染，
 *    上面改一个字，下面立刻跟着变，不用保存也能先看效果。
 *
 * 三条交互规则：
 * - **选图即落盘**：选封面是一个明确的动作，选完就把 URI 存下来，用户不用再点一次保存。
 *   `takePersistableUriPermission` 失败的场合（对方提供方没给 persistable 授权）照样保存 URI，
 *   只在状态行说清楚「重启后可能加载不出来」，不因为权限没拿全就把用户的选择扔掉；
 * - **文字由「保存」落盘**：名称和描述可以反复改，只有点保存才写盘，避免每敲一个字都写一次
 *   SharedPreferences；预览区不受影响，它读的是当前草稿而不是已存盘的值；
 * - **空值有兜底**：名字清空回落到默认名，描述为空显示占位文案，没封面显示默认底色，
 *   预览卡永远不会变成一片空白。
 *
 * 封面加载走 Glide（项目已有依赖）：`Glide.with(this).load(uri).into(imageView)`，
 * 同一个 URI 同时喂给大图和预览卡的小图，Glide 自己按尺寸取缩略图，不会把原图解码两遍。
 */
class F037Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF037Binding

    private lateinit var store: F037Engine.PlaylistMetaStore

    /** 当前正在编辑的元数据（草稿）：输入框、封面、预览读的都是它。 */
    private var draft: F037Engine.PlaylistMeta = F037Engine.PlaylistMeta()

    /**
     * 系统文档选择器：`OpenDocument` 才能被长期授权，`GetContent` 拿到的权限只活到进程结束。
     * 只允许图片，用户取消时回调收到 null，直接忽略。
     */
    private val pickCover =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) adoptCover(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF037Binding.inflate(layoutInflater)
        setContentView(binding.root)

        store = F037Engine.store(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.f037PickCover.setOnClickListener { pickCover.launch(arrayOf("image/*")) }
        binding.f037ClearCover.setOnClickListener { clearCover() }
        binding.f037SaveButton.setOnClickListener { saveText() }

        // 先读盘并回填输入框、再挂监听：回填本身不该被当成一次「草稿变了」
        draft = store.load()
        bindForm(draft)
        attachTextWatchers()
        renderCover()
        renderPreview()

        setStatus(
            if (store.exists()) getString(R.string.f037_status_loaded)
            else getString(R.string.f037_status_idle),
            R.color.theme_text_secondary
        )
    }

    // ===== 封面 =====

    /**
     * 采纳用户选中的图片。
     *
     * `takePersistableUriPermission` 抛 `SecurityException` 是预期内的情况：
     * 有些文档提供方只给一次性授权（比如部分网盘、部分文件管理器），
     * 这时本次会话照样能显示封面，只是重启后 URI 会读不出来。
     * 所以这里 catch 住、降级成一句警告，而不是让页面直接崩掉或者把选择丢掉。
     */
    private fun adoptCover(uri: Uri) {
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        draft = draft.copy(coverUri = uri.toString())
        // 封面是离散选择，选完就落盘；文字草稿顺带存一份当前值，不会丢
        persist(draft)

        if (persisted.isSuccess) {
            setStatus(getString(R.string.f037_cover_picked), R.color.theme_success)
        } else {
            setStatus(getString(R.string.f037_cover_permission_failed), R.color.theme_warning)
        }
        renderCover()
        renderPreview()
    }

    /** 移除封面：只清 URI，不删用户的图片文件（那是系统相册里的东西）。 */
    private fun clearCover() {
        if (!draft.hasCover) {
            setStatus(getString(R.string.f037_cover_none), R.color.theme_text_secondary)
            return
        }
        draft = draft.copy(coverUri = null)
        persist(draft)
        setStatus(getString(R.string.f037_cover_cleared), R.color.theme_text_secondary)
        renderCover()
        renderPreview()
    }

    /** 大图与外层状态行：有封面就加载图片，没有就清空图片、留默认底色。 */
    private fun renderCover() {
        loadCover(draft.coverUri, binding.f037CoverImage)
        binding.f037CoverStatus.text = if (draft.hasCover) {
            getString(R.string.f037_cover_selected, coverLabel(draft.coverUri))
        } else {
            getString(R.string.f037_cover_none)
        }
        binding.f037ClearCover.isEnabled = draft.hasCover
    }

    /**
     * 把封面塞进 ImageView。
     *
     * 空 URI 时主动 `setImageDrawable(null)`：Glide 的 `into` 会保留上一张图，
     * 不主动清掉的话「移除封面」之后旧图还挂在上面，看着像没生效。
     */
    private fun loadCover(uri: String?, target: ImageView) {
        if (uri.isNullOrBlank()) {
            target.setImageDrawable(null)
            return
        }
        Glide.with(this@F037Activity).load(uri).into(target)
    }

    /** 状态行里显示封面用哪个名字：优先取 URI 末段（通常就是文件名）。 */
    private fun coverLabel(uri: String?): String {
        if (uri.isNullOrBlank()) return ""
        val tail = Uri.parse(uri).lastPathSegment
        return if (tail.isNullOrBlank()) uri else tail
    }

    // ===== 名称与描述 =====

    /** 读盘的结果回填到输入框；先挂监听再回填会误触发草稿同步，所以这里只赋值。 */
    private fun bindForm(meta: F037Engine.PlaylistMeta) {
        binding.f037NameInput.setText(meta.name)
        binding.f037DescInput.setText(meta.description)
    }

    /**
     * 名称 / 描述任一变化就重算草稿并刷新预览。
     *
     * `afterTextChanged` 里同步草稿而不是在保存时才读输入框：
     * 预览要「实时」，就必须有一个一直是最新的草稿对象。
     */
    private fun attachTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                draft = draft.copy(
                    name = binding.f037NameInput.text.toString(),
                    description = binding.f037DescInput.text.toString()
                )
                renderPreview()
            }
        }
        binding.f037NameInput.addTextChangedListener(watcher)
        binding.f037DescInput.addTextChangedListener(watcher)
    }

    /** 「保存」：把草稿洗一遍写盘，成功与失败都给一句明确的回执。 */
    private fun saveText() {
        val outcome = persist(draft)
        if (outcome.isSuccess) {
            // 存下去的是洗过的值（去首尾空白、空名字回落默认名），预览跟着切到这一份
            renderPreview()
            setStatus(getString(R.string.f037_status_saved), R.color.theme_success)
            CustomToast.show(this, getString(R.string.f037_status_saved))
        } else {
            val reason = outcome.exceptionOrNull()?.message ?: "未知错误"
            setStatus(getString(R.string.f037_status_save_failed, reason), R.color.theme_error)
        }
    }

    /**
     * 写盘。
     *
     * 返回 [Result] 而不是自己 catch：调用方需要区分成功与失败来决定文案。
     * 成功时把洗过一遍的值写回草稿，用户看到的就是真正存下去的那一份
     * （比如名字里的首尾空格已经去掉了，预览跟着变成存下来的样子）。
     */
    private fun persist(meta: F037Engine.PlaylistMeta): Result<F037Engine.PlaylistMeta> = runCatching {
        val cleaned = meta.normalized()
        store.save(cleaned)
        draft = cleaned
        cleaned
    }

    // ===== 预览 =====

    /** 列表卡片预览：封面、名称、描述、条目数，与列表页那张卡片同构。 */
    private fun renderPreview() {
        loadCover(draft.coverUri, binding.f037PreviewCover)

        binding.f037PreviewName.text = draft.displayName

        binding.f037PreviewDesc.text = if (F037Engine.hasDescription(draft)) {
            draft.displayDescription
        } else {
            getString(R.string.f037_preview_no_desc)
        }

        binding.f037PreviewCount.text =
            getString(R.string.f037_preview_count, F037Engine.entryCount())
    }

    /** 状态行：文案 + 语义色（成功 success / 权限不足 warning / 失败 error / 普通 secondary）。 */
    private fun setStatus(text: String, colorRes: Int) {
        binding.f037StatusText.text = text
        binding.f037StatusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }
}