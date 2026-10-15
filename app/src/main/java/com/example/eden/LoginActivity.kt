package com.example.eden

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.eden.auth.ApiResponse
import com.example.eden.auth.AuthClient
import com.example.eden.auth.AuthData
import com.example.eden.auth.CodeLoginRequest
import com.example.eden.auth.CodeScene
import com.example.eden.auth.PasswordLoginRequest
import com.example.eden.auth.RegisterRequest
import com.example.eden.auth.ResetPasswordRequest
import com.example.eden.auth.SendCodeRequest
import com.example.eden.auth.authErrorMessage
import kotlinx.coroutines.launch

/**
 * 登录 / 注册界面。
 *
 * 支持四种模式：密码登录、验证码登录、注册、忘记密码（邮箱 + 验证码 + 新密码）。
 * 首次启动未登录时会展示本界面，登录成功后写入会话并进入 [MainActivity]。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val MODE_PASSWORD = 0
        private const val MODE_CODE = 1
        private const val MODE_REGISTER = 2
        private const val MODE_RESET = 3
        private const val COUNTDOWN_SECONDS = 60
    }

    private lateinit var tabContainer: LinearLayout
    private lateinit var tabPassword: TextView
    private lateinit var tabCode: TextView
    private lateinit var etEmail: EditText
    private lateinit var rowCode: View
    private lateinit var etCode: EditText
    private lateinit var btnSendCode: Button
    private lateinit var rowPassword: View
    private lateinit var etPassword: EditText
    private lateinit var rowConfirm: View
    private lateinit var etConfirm: EditText
    private lateinit var btnSubmit: Button
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvSwitchMode: TextView

    private var mode = MODE_PASSWORD
    private var countdown = 0
    private val handler = Handler(Looper.getMainLooper())

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdown <= 0) {
                btnSendCode.isEnabled = true
                btnSendCode.text = getString(R.string.login_get_code)
                return
            }
            btnSendCode.text = "${countdown}s"
            countdown--
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        AuthManager.init(this)
        initViews()
        setupListeners()
        applyMode()
    }

    private fun initViews() {
        tabContainer = findViewById(R.id.tab_container)
        tabPassword = findViewById(R.id.tab_password)
        tabCode = findViewById(R.id.tab_code)
        etEmail = findViewById(R.id.et_email)
        rowCode = findViewById(R.id.row_code)
        etCode = findViewById(R.id.et_code)
        btnSendCode = findViewById(R.id.btn_send_code)
        rowPassword = findViewById(R.id.row_password)
        etPassword = findViewById(R.id.et_password)
        rowConfirm = findViewById(R.id.row_confirm)
        etConfirm = findViewById(R.id.et_confirm)
        btnSubmit = findViewById(R.id.btn_submit)
        tvForgotPassword = findViewById(R.id.tv_forgot_password)
        tvSwitchMode = findViewById(R.id.tv_switch_mode)
    }

    private fun setupListeners() {
        tabPassword.setOnClickListener {
            if (mode != MODE_PASSWORD) {
                mode = MODE_PASSWORD
                applyMode()
            }
        }
        tabCode.setOnClickListener {
            if (mode != MODE_CODE) {
                mode = MODE_CODE
                applyMode()
            }
        }
        tvForgotPassword.setOnClickListener {
            if (mode != MODE_RESET) {
                mode = MODE_RESET
                applyMode()
            }
        }
        tvSwitchMode.setOnClickListener {
            mode = if (mode == MODE_REGISTER || mode == MODE_RESET) MODE_PASSWORD else MODE_REGISTER
            applyMode()
        }
        btnSendCode.setOnClickListener { sendVerificationCode() }
        btnSubmit.setOnClickListener { submit() }
    }

    /** 根据当前模式控制各输入框与按钮文案的显示 */
    private fun applyMode() {
        val isRegister = mode == MODE_REGISTER
        val isCodeLogin = mode == MODE_CODE
        val isReset = mode == MODE_RESET
        val isLogin = !isRegister && !isReset

        tabContainer.visibility = if (isLogin) View.VISIBLE else View.GONE
        rowCode.visibility = if (isRegister || isReset || isCodeLogin) View.VISIBLE else View.GONE
        rowPassword.visibility = if (isRegister || isReset || !isCodeLogin) View.VISIBLE else View.GONE
        rowConfirm.visibility = if (isRegister || isReset) View.VISIBLE else View.GONE
        tvForgotPassword.visibility = if (mode == MODE_PASSWORD) View.VISIBLE else View.GONE

        etPassword.hint = if (isReset) "请输入新密码" else getString(R.string.login_password_hint)
        etConfirm.hint = if (isReset) getString(R.string.change_password_confirm_hint) else getString(R.string.login_confirm_password_hint)

        btnSubmit.text = when {
            isRegister -> "注册"
            isReset -> "重置密码"
            else -> getString(R.string.login_submit)
        }
        tvSwitchMode.text = if (isRegister || isReset) "已有账号？返回登录" else getString(R.string.login_register)

        updateTabStyle(tabPassword, mode == MODE_PASSWORD)
        updateTabStyle(tabCode, mode == MODE_CODE)
    }

    private fun updateTabStyle(tab: TextView, selected: Boolean) {
        if (selected) {
            tab.setBackgroundResource(R.drawable.bg_tab_selected)
            tab.setTextColor(edenColor(R.attr.edenColorAccent, R.color.eden_accent))
            tab.setTypeface(null, Typeface.BOLD)
        } else {
            tab.background = null
            tab.setTextColor(edenColor(R.attr.edenColorTextTertiary, R.color.eden_text_tertiary))
            tab.setTypeface(null, Typeface.NORMAL)
        }
    }

    // ==================== 获取验证码 ====================
    private fun sendVerificationCode() {
        val email = etEmail.text.toString().trim()
        if (!isValidEmail(email)) {
            showToast("请输入正确的邮箱地址")
            return
        }

        val scene = when (mode) {
            MODE_REGISTER -> CodeScene.REGISTER
            MODE_RESET -> CodeScene.RESET
            else -> CodeScene.LOGIN
        }
        btnSendCode.isEnabled = false
        btnSendCode.text = "发送中…"

        lifecycleScope.launch {
            try {
                val resp = AuthClient.api.sendCode(SendCodeRequest(email, scene))
                if (resp.code == 0) {
                    showToast("验证码已发送，请查收邮箱")
                    startCountdown()
                } else {
                    resetSendCodeButton()
                    showToast(resp.message ?: "发送失败(${resp.code})")
                }
            } catch (e: Exception) {
                resetSendCodeButton()
                showToast(authErrorMessage(e))
            }
        }
    }

    private fun startCountdown() {
        countdown = COUNTDOWN_SECONDS
        btnSendCode.isEnabled = false
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
    }

    private fun resetSendCodeButton() {
        btnSendCode.isEnabled = true
        btnSendCode.text = getString(R.string.login_get_code)
    }

    // ==================== 提交登录 / 注册 ====================
    private fun submit() {
        val email = etEmail.text.toString().trim()
        if (!isValidEmail(email)) {
            showToast("请输入正确的邮箱地址")
            return
        }

        when (mode) {
            MODE_PASSWORD -> {
                val password = etPassword.text.toString()
                if (password.isEmpty()) {
                    showToast(getString(R.string.login_password_hint))
                    return
                }
                doPasswordLogin(email, password)
            }
            MODE_CODE -> {
                val code = etCode.text.toString().trim()
                if (code.isEmpty()) {
                    showToast(getString(R.string.login_code_hint))
                    return
                }
                doCodeLogin(email, code)
            }
            MODE_REGISTER -> {
                val code = etCode.text.toString().trim()
                val password = etPassword.text.toString()
                val confirm = etConfirm.text.toString()
                if (code.isEmpty()) {
                    showToast(getString(R.string.login_code_hint))
                    return
                }
                if (password.length < 6) {
                    showToast("密码至少 6 位")
                    return
                }
                if (password != confirm) {
                    showToast("两次输入的密码不一致")
                    return
                }
                doRegister(email, code, password)
            }
            MODE_RESET -> {
                val code = etCode.text.toString().trim()
                val password = etPassword.text.toString()
                val confirm = etConfirm.text.toString()
                if (code.isEmpty()) {
                    showToast(getString(R.string.login_code_hint))
                    return
                }
                if (password.length < 6) {
                    showToast("密码至少 6 位")
                    return
                }
                if (password != confirm) {
                    showToast("两次输入的密码不一致")
                    return
                }
                doResetPassword(email, code, password)
            }
        }
    }

    private fun doPasswordLogin(email: String, password: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = AuthClient.api.login(PasswordLoginRequest(email, password))
                handleAuthResponse(resp)
            } catch (e: Exception) {
                setLoading(false)
                showToast(authErrorMessage(e))
            }
        }
    }

    private fun doCodeLogin(email: String, code: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = AuthClient.api.loginByCode(CodeLoginRequest(email, code))
                handleAuthResponse(resp)
            } catch (e: Exception) {
                setLoading(false)
                showToast(authErrorMessage(e))
            }
        }
    }

    private fun doRegister(email: String, code: String, password: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = AuthClient.api.register(RegisterRequest(email, code, password))
                handleAuthResponse(resp)
            } catch (e: Exception) {
                setLoading(false)
                showToast(authErrorMessage(e))
            }
        }
    }

    /** 忘记密码：用邮箱验证码重置密码，成功后切回登录模式 */
    private fun doResetPassword(email: String, code: String, password: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = AuthClient.api.resetPassword(
                    ResetPasswordRequest(email, code, password)
                )
                setLoading(false)
                if (resp.code == 0) {
                    showToast("密码已重置，请使用新密码登录")
                    etPassword.setText("")
                    etConfirm.setText("")
                    mode = MODE_PASSWORD
                    applyMode()
                } else {
                    showToast(resp.message ?: "重置失败(${resp.code})")
                }
            } catch (e: Exception) {
                setLoading(false)
                showToast(authErrorMessage(e))
            }
        }
    }

    /** 统一处理登录/注册返回：成功后保存会话并进入主界面 */
    private fun handleAuthResponse(resp: ApiResponse<AuthData>) {
        if (resp.code != 0) {
            setLoading(false)
            showToast(resp.message ?: "操作失败(${resp.code})")
            return
        }
        val data = resp.data
        val token = data?.token
        if (token.isNullOrEmpty()) {
            setLoading(false)
            showToast("登录失败：服务端未返回登录凭证")
            return
        }
        AuthManager.saveSession(
            this,
            token = token,
            email = data.user?.email ?: etEmail.text.toString().trim(),
            nickname = data.user?.nickname,
            userId = data.user?.id
        )
        val userId = data.user?.id ?: 0L
        // 进入主界面前，先按当前账号整理本地数据（切换账号时清空上一账号的残留数据）。
        // 必须在跳转前完成，否则主界面 onResume 会先读到旧账号的数据。
        lifecycleScope.launch {
            SyncManager.prepareForUser(this@LoginActivity, userId)
            showToast("登录成功")
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun setLoading(loading: Boolean) {
        btnSubmit.isEnabled = !loading
        btnSubmit.text = when {
            loading -> "请稍候…"
            mode == MODE_REGISTER -> "注册"
            mode == MODE_RESET -> "重置密码"
            else -> getString(R.string.login_submit)
        }
    }

    private fun isValidEmail(email: String): Boolean =
        Patterns.EMAIL_ADDRESS.matcher(email).matches()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }
}
