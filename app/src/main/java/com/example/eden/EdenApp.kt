package com.example.eden


import com.example.eden.core.session.AuthClient
import com.example.eden.core.session.SessionManager
import com.example.eden.core.storage.EdenStorage
import com.example.eden.feature.auth.LoginActivity
import com.example.eden.feature.home.MainActivity
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        SessionManager.init(this)
        // 升级到「按账号分区」存储后首次启动：把旧版遗留的未分区数据改名到当前账号分区，
        // 必须在任何数据库 / 业务 prefs 被访问之前执行，否则会读到空分区而误以为数据丢失。
        EdenStorage.migrateLegacyToUserScope(this)
        setupImmersiveWindows()
    }

    /**
     * 只有主界面（背景图全屏铺满）收起系统状态栏，让顶栏成为页面最上方；
     * 其他界面保持系统状态栏可见，内容由各页布局自行避让，避免顶部出现空白带。
     */
    private fun setupImmersiveWindows() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) hideSystemStatusBar(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                // 主界面从弹窗 / 系统栏临时呼出后重新收起
                if (activity is MainActivity) hideSystemStatusBar(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun hideSystemStatusBar(activity: Activity) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
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
                SessionManager.logoutAndResetSession(app) {
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
