package com.tv.ball.feature.f064

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF064Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 「二维码分享地址」页。
 *
 * 一条直线：输入地址 → 点「生成二维码」→ [F064Engine.encode] 出模块矩阵 → 画成黑白位图 → 贴到 ImageView。
 * 编码全在本地，不发网络请求，所以这个页面不需要任何状态同步；唯一的异步是为了不在主线程上算矩阵
 * （版本 6 要试 8 张 41×41 的掩码并分别罚分，放主线程会掉帧）。
 *
 * 三个状态：
 * 1. 还没生成：二维码位置显示一句占位说明；
 * 2. 生成成功：位图贴上去，下面把地址原文也列出来，长按二维码或地址都能复制到剪贴板；
 * 3. 生成失败：只有一种失败——地址超出版本 6 / 纠错 M 的 106 字节容量，
 *    这时清掉旧图、给出「多少字节 / 上限多少字节」的提示，而不是显示一张画错的码。
 *
 * 静区（quiet zone）由这里负责留：QR 标准要求四周至少 4 个模块的白边，
 * [F064Engine] 交出来的矩阵不含白边，画图时在四周各铺 [QUIET_ZONE] 个模块的白色。
 */
class F064Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF064Binding

    /** 当前这次生成；重复点按钮先取消旧的，避免两张图互相覆盖。 */
    private var job: Job? = null

    /** 当前二维码对应的地址，长按复制时直接用它，避免复制到输入框里改了一半的内容。 */
    private var currentAddress: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF064Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f064GenerateButton.setOnClickListener { generate() }
        binding.f064AddressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                generate()
                true
            } else {
                false
            }
        }
        binding.f064QrImage.setOnLongClickListener { copyAddress(); true }
        binding.f064AddressText.setOnLongClickListener { copyAddress(); true }

        // 投屏页跳过来时把地址带进来，省得用户在电视上敲一长串 URL
        val preset = intent.getStringExtra(EXTRA_URL) ?: intent.getStringExtra(EXTRA_ADDRESS)
        if (!preset.isNullOrBlank()) {
            binding.f064AddressInput.setText(preset)
            binding.f064AddressInput.setSelection(preset.length)
            generate()
        } else {
            renderIdle()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 生成 =====

    /**
     * 读地址 → 后台算矩阵 → 回主线程画图。
     *
     * 空地址不发请求也不清图，只提示一句：用户点按钮时大概率是还没填，
     * 把已经生成的码留在屏幕上比清掉更不打扰。
     */
    private fun generate() {
        val address = binding.f064AddressInput.text?.toString()?.trim().orEmpty()
        if (address.isEmpty()) {
            setStatus(getString(R.string.f064_status_empty), R.color.theme_warning)
            CustomToast.show(this, getString(R.string.f064_toast_empty))
            return
        }

        job?.cancel()
        binding.f064GenerateButton.isEnabled = false
        binding.f064GenerateButton.alpha = 0.5f
        setStatus(getString(R.string.f064_status_working), R.color.theme_text_secondary)

        job = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { F064Engine.encode(address) }
            }
            if (isFinishing || isDestroyed) return@launch
            binding.f064GenerateButton.isEnabled = true
            binding.f064GenerateButton.alpha = 1f
            result
                .onSuccess { qr -> renderQr(address, qr) }
                .onFailure { error -> renderFailure(address, error) }
        }
    }

    // ===== 渲染 =====

    private fun renderIdle() {
        currentAddress = ""
        binding.f064QrImage.visibility = View.GONE
        binding.f064QrImage.setImageDrawable(null)
        binding.f064QrPlaceholder.visibility = View.VISIBLE
        binding.f064QrPlaceholder.text = getString(R.string.f064_qr_placeholder)
        binding.f064AddressText.text = getString(R.string.f064_value_none)
        setStatus(getString(R.string.f064_status_idle), R.color.theme_text_secondary)
    }

    /** 成功：贴图 + 在下方列出地址原文（也支持长按复制）。 */
    private fun renderQr(address: String, qr: F064Engine.Qr) {
        currentAddress = address
        binding.f064QrImage.setImageBitmap(toBitmap(qr))
        binding.f064QrImage.visibility = View.VISIBLE
        binding.f064QrPlaceholder.visibility = View.GONE
        binding.f064AddressText.text = getString(R.string.f064_address_prefix, address)
        setStatus(
            getString(R.string.f064_status_ready, qr.size, qr.version),
            R.color.theme_success
        )
    }

    /**
     * 失败：目前只有「超长」一种。
     *
     * 超长时把旧图清掉——旧图对应的不是现在输入的地址，留着会让人扫到错的码。
     */
    private fun renderFailure(address: String, error: Throwable) {
        currentAddress = ""
        binding.f064QrImage.setImageDrawable(null)
        binding.f064QrImage.visibility = View.GONE
        binding.f064QrPlaceholder.visibility = View.VISIBLE
        binding.f064QrPlaceholder.text = getString(R.string.f064_qr_failed)
        binding.f064AddressText.text = getString(R.string.f064_address_prefix, address)

        val message = if (error is F064Engine.OverflowException) {
            getString(R.string.f064_status_too_long, error.byteCount, error.limit)
        } else {
            getString(R.string.f064_status_failed, error.message ?: getString(R.string.f064_value_none))
        }
        setStatus(message, R.color.theme_error)
        CustomToast.show(this, getString(R.string.f064_toast_too_long))
    }

    // ===== 位图 =====

    /**
     * 模块矩阵 → 黑白位图。
     *
     * - 四周补 [QUIET_ZONE] 个模块的白边（标准要求 4 个，少了扫码器容易切边）；
     * - 每个模块的像素数按屏幕宽度换算，取整数：整数倍缩放不会出现半像素拼缝，
     *   否则某一行会看起来比别的浅，扫码器的二值化就容易判错；
     * - 只用 [Color.BLACK] / [Color.WHITE]：二维码必须黑白高对比才能扫，
     *   不能跟主题色走（深色主题下用 surface 色当底会把对比度压到扫不出来）。
     */
    private fun toBitmap(qr: F064Engine.Qr): Bitmap {
        val total = qr.size + QUIET_ZONE * 2
        val target = max(MIN_QR_PIXELS, (resources.displayMetrics.widthPixels * QR_SCREEN_RATIO).toInt())
        val scale = max(MIN_SCALE, target / total)
        val side = total * scale
        val pixels = IntArray(side * side)
        java.util.Arrays.fill(pixels, Color.WHITE)
        for (row in 0 until qr.size) {
            val top = (row + QUIET_ZONE) * scale
            for (col in 0 until qr.size) {
                if (!qr.isDark(row, col)) continue
                val left = (col + QUIET_ZONE) * scale
                for (y in top until top + scale) {
                    val base = y * side
                    java.util.Arrays.fill(pixels, base + left, base + left + scale, Color.BLACK)
                }
            }
        }
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        return bitmap
    }

    // ===== 复制 / 状态 =====

    /** 长按复制：只复制真正生成出二维码的那条地址，输入框里改了一半的内容不算。 */
    private fun copyAddress() {
        val address = currentAddress
        if (address.isEmpty()) {
            CustomToast.show(this, getString(R.string.f064_toast_copy_empty))
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            CustomToast.show(this, getString(R.string.f064_toast_copy_failed))
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, address))
        CustomToast.show(this, getString(R.string.f064_toast_copied))
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f064StatusLine.text = message
        binding.f064StatusLine.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.f064StatusLine.visibility = View.VISIBLE
    }

    companion object {

        /** 从投屏页跳进来时可以带地址，两个键都认，省得调用方记住用哪个。 */
        const val EXTRA_URL: String = "url"
        const val EXTRA_ADDRESS: String = "address"

        /** 剪贴板条目的标签，按这个能看出是谁复制的。 */
        private const val CLIP_LABEL = "tv:share-url"

        /** 二维码四周的白边，单位是「模块」，标准要求 4。 */
        private const val QUIET_ZONE = 4

        /** 位图目标边长占屏幕宽度的比例；再按模块数取整数倍放大。 */
        private const val QR_SCREEN_RATIO = 0.42f

        /** 目标边长的下限（像素）：模块多的时候也保证码不糊。 */
        private const val MIN_QR_PIXELS = 320

        /** 最小放大倍数：每模块至少 2 像素，否则扫码器分辨不出来。 */
        private const val MIN_SCALE = 2
    }
}
