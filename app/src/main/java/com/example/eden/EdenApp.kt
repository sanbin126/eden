package com.example.eden

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局 Application：负责在 Token 失效（HTTP 401）时统一登出并跳回登录页。
 *
 * 为什么放在 Application：401 可能发生在任意页面的任意请求上，
 * 由网络层（见 `AuthClient` 的拦截器）反向通知这里，可以避免每个 Activity 各写一遍登出逻辑。
 */
class EdenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 提前把持久化的 token 载入内存，供网络层拼 Authorization 头
        AuthManager.init(this)
        // 升级到「按账号分区」存储后首次启动：把旧版遗留的未分区数据改名到当前账号分区，
        // 必须在任何数据库 / 业务 prefs 被访问之前执行，否则会读到空分区而误以为数据丢失。
        EdenStorage.migrateLegacyToUserScope(this)
    }

    companion object {
        private lateinit var instance: EdenApp

        /** 防重入：401 往往并发触发多次，只处理第一次 */
        private val loggingOut = AtomicBoolean(false)

        private const val RELOGIN_GUARD_MS = 3000L

        /**
         * 收到 401 时调用：清空本地登录态，并跳转到登录页（清空返回栈）。
         * 可在任意线程调用，内部会切回主线程执行。
         */
        fun onUnauthorized() {
            if (!loggingOut.compareAndSet(false, true)) return
            val app = instance
            Handler(Looper.getMainLooper()).post {
                // 先清除登录态与同步内存态，再跳登录页；本地数据按账号分区保存，不清空
                SyncManager.logoutAndResetSession(app) {
                    val intent = Intent(app, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    app.startActivity(intent)
                    // 稍后解除防重入，避免用户重新登录后第一次请求又被旧标记挡住
                    Handler(Looper.getMainLooper())
                        .postDelayed({ loggingOut.set(false) }, RELOGIN_GUARD_MS)
                }
            }
        }
    }
}
