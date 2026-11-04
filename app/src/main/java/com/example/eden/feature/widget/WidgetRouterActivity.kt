package com.example.eden.feature.widget

import android.content.Intent
import android.os.Bundle
import com.example.eden.core.session.SessionManager
import com.example.eden.core.ui.BaseActivity
import com.example.eden.feature.auth.LoginActivity
import com.example.eden.feature.chat.ChatActivity
import com.example.eden.feature.privacy.PrivacyConsentActivity

/**
 * 桌面小组件的点击中转页。
 *
 * 小组件无法自行判断「隐私是否同意 / 是否登录」，直接跳聊天页会在未登录时出错，
 * 因此统一从这里按状态分流，分流后立即关闭自己，避免留下空白页。
 */
class WidgetRouterActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val next = when {
            !PrivacyConsentActivity.hasAgreed(this) -> PrivacyConsentActivity::class.java
            SessionManager.getUserId(this) <= 0L -> LoginActivity::class.java
            else -> ChatActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
