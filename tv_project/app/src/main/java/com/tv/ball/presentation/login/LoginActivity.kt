package com.tv.ball.presentation.login

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.ball.databinding.ActivityLoginBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.presentation.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isLoginMode = true
    private var countDownTimer: CountDownTimer? = null
    private val memberApiService by lazy { Dependencies.memberApiService }
    private val userPreferences by lazy { Dependencies.userPreferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        updateUIForMode()
    }

    private fun setupClickListeners() {
        // Tab切换
        binding.tvLoginTab.setOnClickListener {
            switchToLoginMode()
        }

        binding.tvRegisterTab.setOnClickListener {
            switchToRegisterMode()
        }

        // 获取验证码
        binding.btnGetCode.setOnClickListener {
            getVerificationCode()
        }

        // 提交按钮
        binding.btnSubmit.setOnClickListener {
            if (isLoginMode) {
                login()
            } else {
                register()
            }
        }

        // 切换模式链接
        binding.tvSwitchMode.setOnClickListener {
            if (isLoginMode) {
                switchToRegisterMode()
            } else {
                switchToLoginMode()
            }
        }

        // 忘记密码
        binding.tvForgotPassword.setOnClickListener {
            com.tv.ball.util.CustomToast.show(this, "忘记密码 - 开发中")
        }

        // 服务协议
        binding.tvServiceAgreement.setOnClickListener {
            com.tv.ball.util.CustomToast.show(this, "服务协议 - 开发中")
        }

        // 隐私政策
        binding.tvPrivacyPolicy.setOnClickListener {
            com.tv.ball.util.CustomToast.show(this, "隐私政策 - 开发中")
        }

        // 第三方登录
        binding.ivWechatLogin.setOnClickListener {
            com.tv.ball.util.CustomToast.show(this, "微信登录 - 开发中")
        }

        binding.ivAlipayLogin.setOnClickListener {
            com.tv.ball.util.CustomToast.show(this, "支付宝登录 - 开发中")
        }
    }

    private fun switchToLoginMode() {
        isLoginMode = true
        updateUIForMode()
    }

    private fun switchToRegisterMode() {
        isLoginMode = false
        updateUIForMode()
    }

    private fun updateUIForMode() {
        if (isLoginMode) {
            // 登录模式
            binding.tvLoginTab.setTextColor(0xFFFFFFFF.toInt())
            binding.tvLoginTab.setBackgroundResource(com.tv.ball.R.drawable.tab_selected_bg)
            binding.tvRegisterTab.setTextColor(0xFF666666.toInt())
            binding.tvRegisterTab.setBackgroundResource(android.R.color.transparent)

            binding.layoutPassword.visibility = View.GONE
            binding.layoutConfirmPassword.visibility = View.GONE
            binding.layoutLoginOptions.visibility = View.VISIBLE

            binding.btnSubmit.text = "登录"
            binding.tvSwitchMode.text = "去注册"
        } else {
            // 注册模式
            binding.tvLoginTab.setTextColor(0xFF666666.toInt())
            binding.tvLoginTab.setBackgroundResource(android.R.color.transparent)
            binding.tvRegisterTab.setTextColor(0xFFFFFFFF.toInt())
            binding.tvRegisterTab.setBackgroundResource(com.tv.ball.R.drawable.tab_selected_bg)

            binding.layoutPassword.visibility = View.VISIBLE
            binding.layoutConfirmPassword.visibility = View.VISIBLE
            binding.layoutLoginOptions.visibility = View.GONE

            binding.btnSubmit.text = "注册"
            binding.tvSwitchMode.text = "去登录"
        }
    }

    private fun getVerificationCode() {
        val email = binding.etEmail.text.toString().trim()

        if (email.isEmpty()) {
            com.tv.ball.util.CustomToast.show(this, "请输入邮箱")
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            com.tv.ball.util.CustomToast.show(this, "请输入正确的邮箱格式")
            return
        }

        // 禁用按钮，防止重复点击
        binding.btnGetCode.isEnabled = false

        lifecycleScope.launch {
            try {
                val type = if (isLoginMode) "login" else "register"
                val result = memberApiService.sendEmailCode(email, type)

                if (result.code == 0) {
                    com.tv.ball.util.CustomToast.show(this@LoginActivity, result.message ?: "验证码已发送")
                    startCountDown()
                } else {
                    com.tv.ball.util.CustomToast.show(this@LoginActivity, result.message ?: "发送失败")
                    binding.btnGetCode.isEnabled = true
                }
            } catch (e: Exception) {
                com.tv.ball.util.CustomToast.show(this@LoginActivity, "发送失败: ${e.message}")
                binding.btnGetCode.isEnabled = true
            }
        }
    }

    private fun startCountDown() {
        countDownTimer?.cancel()
        val countdown = userPreferences.getCodeCountdown()
        countDownTimer = object : CountDownTimer((countdown * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.btnGetCode.text = "${seconds}秒"
            }

            override fun onFinish() {
                binding.btnGetCode.text = "获取验证码"
                binding.btnGetCode.isEnabled = true
            }
        }.start()
    }

    private fun login() {
        val email = binding.etEmail.text.toString().trim()
        val code = binding.etCode.text.toString().trim()

        if (email.isEmpty()) {
            com.tv.ball.util.CustomToast.show(this, "请输入邮箱")
            return
        }

        if (code.isEmpty()) {
            com.tv.ball.util.CustomToast.show(this, "请输入验证码")
            return
        }

        if (!binding.cbAgree.isChecked) {
            com.tv.ball.util.CustomToast.show(this, "请先同意服务协议和隐私政策")
            return
        }

        lifecycleScope.launch {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )

                val result = memberApiService.login(
                    email = email,
                    code = code,
                    deviceId = deviceId,
                    deviceType = "android",
                    appVersion = "1.0.0"
                )

                if (result.code == 0) {
                    // 保存登录信息
                    result.data?.let { memberData ->
                        userPreferences.saveLoginInfo(
                            token = memberData.token,
                            memberId = memberData.memberId,
                            username = memberData.username,
                            email = memberData.email,
                            nickname = memberData.nickname,
                            avatar = memberData.avatar,
                            level = memberData.level,
                            vipExpireTime = memberData.vipExpireTime,
                            points = memberData.points,
                            balance = memberData.balance,
                            inviteCode = memberData.inviteCode,
                            autoLogin = binding.cbAutoLogin.isChecked
                        )
                    }

                    com.tv.ball.util.CustomToast.show(this@LoginActivity, "登录成功")
                    navigateToMain()
                } else {
                    com.tv.ball.util.CustomToast.show(this@LoginActivity, result.message ?: "登录失败")
                }
            } catch (e: Exception) {
                com.tv.ball.util.CustomToast.show(this@LoginActivity, "登录失败: ${e.message}")
            }
        }
    }

    private fun register() {
        val email = binding.etEmail.text.toString().trim()
        val code = binding.etCode.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (email.isEmpty()) {
            com.tv.ball.util.CustomToast.show(this, "请输入邮箱")
            return
        }

        if (code.isEmpty()) {
            com.tv.ball.util.CustomToast.show(this, "请输入验证码")
            return
        }

        if (password.isEmpty()) {
            com.tv.ball.util.CustomToast.show(this, "请输入密码")
            return
        }

        if (password.length < 6) {
            com.tv.ball.util.CustomToast.show(this, "密码长度不能少于6位")
            return
        }

        if (password != confirmPassword) {
            com.tv.ball.util.CustomToast.show(this, "两次密码输入不一致")
            return
        }

        if (!binding.cbAgree.isChecked) {
            com.tv.ball.util.CustomToast.show(this, "请先同意服务协议和隐私政策")
            return
        }

        lifecycleScope.launch {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )

                val result = memberApiService.register(
                    email = email,
                    code = code,
                    password = password,
                    deviceId = deviceId,
                    deviceType = "android",
                    appVersion = "1.0.0"
                )

                if (result.code == 0) {
                    // 保存注册信息（注册成功后自动登录）
                    result.data?.let { memberData ->
                        userPreferences.saveLoginInfo(
                            token = memberData.token,
                            memberId = memberData.memberId,
                            username = memberData.username,
                            email = memberData.email,
                            nickname = memberData.nickname,
                            avatar = memberData.avatar,
                            level = memberData.level,
                            vipExpireTime = memberData.vipExpireTime,
                            points = memberData.points,
                            balance = memberData.balance,
                            inviteCode = memberData.inviteCode,
                            autoLogin = true  // 注册后默认自动登录
                        )
                    }

                    com.tv.ball.util.CustomToast.show(this@LoginActivity, "注册成功")
                    navigateToMain()
                } else {
                    com.tv.ball.util.CustomToast.show(this@LoginActivity, result.message ?: "注册失败")
                }
            } catch (e: Exception) {
                com.tv.ball.util.CustomToast.show(this@LoginActivity, "注册失败: ${e.message}")
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
