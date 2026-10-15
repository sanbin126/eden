package com.example.eden

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.eden.auth.AuthClient
import com.example.eden.auth.ChangePasswordRequest
import com.example.eden.auth.authErrorMessage
import kotlinx.coroutines.launch

/**
 * 修改密码界面。调用需登录态的 auth/password/change 接口。
 */
class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var etOldPassword: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSubmit: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etOldPassword = findViewById(R.id.et_old_password)
        etNewPassword = findViewById(R.id.et_new_password)
        etConfirmPassword = findViewById(R.id.et_confirm_password)
        btnSubmit = findViewById(R.id.btn_submit)
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        btnSubmit.setOnClickListener { submit() }
    }

    private fun submit() {
        val oldPassword = etOldPassword.text.toString()
        val newPassword = etNewPassword.text.toString()
        val confirm = etConfirmPassword.text.toString()

        if (oldPassword.isEmpty()) {
            showToast(getString(R.string.change_password_current_hint))
            return
        }
        if (newPassword.length < 6) {
            showToast("新密码至少 6 位")
            return
        }
        if (newPassword != confirm) {
            showToast("两次输入的新密码不一致")
            return
        }
        if (newPassword == oldPassword) {
            showToast("新密码不能与当前密码相同")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = AuthClient.api.changePassword(
                    AuthManager.bearerToken(),
                    ChangePasswordRequest(oldPassword, newPassword)
                )
                setLoading(false)
                if (resp.code == 0) {
                    showToast("密码修改成功")
                    finish()
                } else {
                    showToast(resp.message ?: "修改失败(${resp.code})")
                }
            } catch (e: Exception) {
                setLoading(false)
                showToast(authErrorMessage(e))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnSubmit.isEnabled = !loading
        btnSubmit.text = if (loading) "提交中…" else getString(R.string.change_password_submit)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
