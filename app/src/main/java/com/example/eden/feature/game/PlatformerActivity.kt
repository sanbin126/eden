package com.example.eden.feature.game

import com.example.eden.core.ui.BaseActivity
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 横版跳跃小游戏：WebView 全屏加载 assets/platformer 里的 Canvas 网页。
 *
 * 页面本身是独立可双击运行的网页，接入 App 只做三件事：
 * - 锁定横屏 + 隐藏系统栏，让 16:9 画布铺满；
 * - 打开 JS 后注入 `EdenGame` 桥，通关时由网页回调 [GameBridge.onCleared]；
 * - 通关按统一口径结算奖励（复用 [GameRewards]，每日上限不变）。
 *
 * 中途返回不会结算，和扫雷 / 五子棋 / 象棋一致。
 */
class PlatformerActivity : BaseActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式横屏：不占系统栏，避免画布被状态栏 / 导航栏挤压
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#080b12"))
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isHapticFeedbackEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
            }

            // 页面内跳转保持在 WebView 里，避免弹出外部浏览器
            webViewClient = WebViewClient()
            addJavascriptInterface(GameBridge(), "EdenGame")
            loadUrl("file:///android_asset/platformer/index.html")
        }
        setContentView(webView)
    }

    /** 网页通关时回调：结算一次，并把本次入账的「円」提示给用户。 */
    private inner class GameBridge {
        @JavascriptInterface
        fun onCleared(coins: Int, total: Int) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val yen = GameRewards.settle(this@PlatformerActivity, win = true)
                val message = if (coins >= total) {
                    "全收集通关！获得 $yen 円"
                } else {
                    "通关！金币 $coins / $total，获得 $yen 円"
                }
                Toast.makeText(this@PlatformerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("EdenGame")
        webView.destroy()
        super.onDestroy()
    }
}
