package com.example.eden.feature.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import com.example.eden.BuildConfig
import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import com.example.eden.feature.home.MainActivity

/**
 * 首次启动的隐私同意页（LAUNCHER 入口）。
 *
 * 合规要求：用户点「同意」之前不得申请任何系统权限、不得发起任何网络请求。
 * 因此登录态同步、定位、通知等初始化都留在 [MainActivity] 及其之后，本页只做本地读改写。
 */
class PrivacyConsentActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已同意过：直接进入原有流程，不重复展示
        if (hasAgreed(this)) {
            goNext()
            return
        }

        setContentView(R.layout.activity_privacy_consent)

        findViewById<TextView>(R.id.btn_privacy_agree).setOnClickListener {
            prefs(this).edit().putBoolean(KEY_AGREED, true).apply()
            goNext()
        }
        findViewById<TextView>(R.id.btn_privacy_disagree).setOnClickListener {
            Toast.makeText(this, R.string.privacy_disagree_toast, Toast.LENGTH_SHORT).show()
            // 不同意即退出，不留后台入口
            finishAffinity()
        }
        findViewById<TextView>(R.id.tv_privacy_policy_link).setOnClickListener { openPolicy() }
    }

    private fun goNext() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
        } catch (e: ActivityNotFoundException) {
            // 设备没有可打开网页的浏览器
            Toast.makeText(this, R.string.privacy_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREFS_NAME = "privacy_prefs"
        private const val KEY_AGREED = "privacy_agreed"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** 供启动页判断是否已同意隐私政策 */
        fun hasAgreed(context: Context): Boolean = prefs(context).getBoolean(KEY_AGREED, false)
    }
}
