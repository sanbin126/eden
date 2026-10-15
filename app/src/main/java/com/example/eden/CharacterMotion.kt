package com.example.eden

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PorterDuff
import android.widget.ImageView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 角色立绘「整体变换」动画控制器（档位 1：零美术成本，纯代码）。
 *
 * 只有一张立绘的情况下，靠旋转 / 缩放 / 位移 / 叠色模拟出几种神态，让纸片人「活」起来：
 * - [happyBounce] 摸头时开心弹跳
 * - [tiltHead]    触耳机时疑惑歪头
 * - [shyShake]    触胸口时害羞发抖 + 脸上泛红
 * - [leanIn]      其它区域时轻轻前倾
 * - [setSway]     音乐播放中跟着节奏轻轻摇摆
 * - 常驻 [start]  呼吸（缩放的周期性微起伏）
 *
 * 为什么要有这个类：呼吸和瞬时动作会抢同一个 View 属性（例如呼吸动 scale、开心也动 scale），
 * 各自独立播放必然互相抖动。这里把所有变换收口，遵循一条规则——
 * **播瞬时动作前先暂停呼吸/摇摆并把属性归位，动作结束后再恢复**。
 *
 * 旋转支点固定在立绘「底部偏上」（[PIVOT_RATIO_Y]）：小幅旋转看上去是头肩整体倾斜
 * （歪头 / 身体轻晃），而不是画面绕中心翻转。
 */
class CharacterMotion(private val view: ImageView) {

    companion object {
        /** 旋转支点竖直比例：越靠近底部，越像「上半身倾斜」而非「原地打转」 */
        private const val PIVOT_RATIO_Y = 0.9f

        /** 呼吸周期与幅度（沿用原先 MainActivity 的手感） */
        private const val BREATH_PERIOD_MS = 3000L
        private const val BREATH_SCALE = 1.02f

        /** 歪头角度 */
        private const val TILT_DEGREE = 4.5f

        /** 摇晃最大角度与周期 */
        private const val SWAY_DEGREE = 1.8f
        private const val SWAY_PERIOD_MS = 1400L

        /** 害羞叠色的粉调（只取 RGB，alpha 由动画控制） */
        private const val BLUSH_R = 0xFF
        private const val BLUSH_G = 0x6F
        private const val BLUSH_B = 0xA5
    }

    private var breathAnim: AnimatorSet? = null
    private var swayAnim: ObjectAnimator? = null
    private var transient: AnimatorSet? = null

    /** 是否正处于瞬时动作中（期间不恢复呼吸/摇摆，避免抢属性） */
    private var interactive = false

    /** 期望的摇摆状态：music 状态由外部同步，瞬时动作结束后按它决定是否恢复摇摆 */
    private var wantSway = false

    /**
     * 瞬时动作统一结束回调。
     * 只在「当前动作自己播完」时收尾；被新动作打断时 [transient] 已被置空，
     * 这里的 identity 判断会直接跳过，避免误触发恢复。
     */
    private val endListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            if (animation === transient) finishInteractive()
        }
    }

    // ============== 对外动作 ==============

    /** 常驻呼吸，进页面时调用一次 */
    fun start() = startBreath()

    /** 页面销毁时释放所有动画，避免泄漏 */
    fun release() {
        breathAnim?.cancel(); breathAnim = null
        swayAnim?.cancel(); swayAnim = null
        transient?.cancel(); transient = null
        view.setColorFilter(null)
    }

    /** 摸头：开心地原地弹跳两下 + 轻微放大 */
    fun happyBounce() {
        beginInteractive()
        val up = dp(14f)
        play(
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "translationY", 0f, -up, 0f, -up * 0.45f, 0f)
                        .apply { duration = 640 },
                    ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.04f, 1f)
                        .apply { duration = 640 },
                    ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.04f, 1f)
                        .apply { duration = 640 }
                )
            }
        )
    }

    /** 触耳机：疑惑地歪头停一下再回正 */
    fun tiltHead() {
        beginInteractive()
        play(
            AnimatorSet().apply {
                playSequentially(
                    ObjectAnimator.ofFloat(view, "rotation", 0f, TILT_DEGREE).apply {
                        duration = 260
                        interpolator = OvershootInterpolator(2.2f)
                    },
                    ObjectAnimator.ofFloat(view, "rotation", TILT_DEGREE, 0f).apply {
                        duration = 240
                        interpolator = DecelerateInterpolator()
                        startDelay = 700
                    }
                )
            }
        )
    }

    /** 触胸口：害羞地小幅左右发抖，同时脸颊泛红 */
    fun shyShake() {
        beginInteractive()
        val d = dp(6f)
        val shake = ObjectAnimator.ofFloat(
            view, "translationX",
            0f, -d, d, -d * 0.8f, d * 0.8f, -d * 0.4f, d * 0.4f, 0f
        ).apply {
            duration = 460
            interpolator = LinearInterpolator()
        }
        // 叠色用 ValueAnimator 手写：ImageView 的 colorFilter 不是可动画属性
        val blush = ValueAnimator.ofFloat(0f, 0.32f, 0f).apply {
            duration = 900
            startDelay = 80
        }
        blush.addUpdateListener { anim ->
            val alpha = anim.animatedValue as Float
            view.setColorFilter(blushColor(alpha), PorterDuff.Mode.SRC_ATOP)
        }
        play(
            AnimatorSet().apply { playTogether(shake, blush) }
        )
    }

    /** 其它区域：轻轻前倾一下，像是在认真听你说话（不再放大立绘） */
    fun leanIn() {
        beginInteractive()
        val peak = -dp(10f)
        val forward = ObjectAnimator.ofFloat(view, "translationY", 0f, peak).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
        }
        val back = ObjectAnimator.ofFloat(view, "translationY", peak, 0f).apply {
            duration = 260
            interpolator = AccelerateInterpolator()
            startDelay = 550
        }
        play(
            AnimatorSet().apply { playSequentially(forward, back) }
        )
    }

    /**
     * 同步音乐播放状态：播放中轻轻摇摆，停止/暂停后回正。
     * 处于瞬时动作中只记录期望值，等动作播完由 [finishInteractive] 统一恢复。
     */
    fun setSway(playing: Boolean) {
        wantSway = playing
        if (interactive) return
        if (playing) startSway() else stopSway()
    }

    // ============== 内部实现 ==============

    private fun play(set: AnimatorSet) {
        ensurePivot()
        transient = set
        set.addListener(endListener)
        set.start()
    }

    /** 进入瞬时动作：暂停常驻动画并归位，打断上一个未播完的动作 */
    private fun beginInteractive() {
        interactive = true
        breathAnim?.cancel(); breathAnim = null
        swayAnim?.cancel(); swayAnim = null
        transient?.let {
            transient = null
            it.cancel()
        }
        resetTransform()
    }

    /** 瞬时动作收尾：归位后恢复常驻动画（呼吸，必要时叠加摇摆） */
    private fun finishInteractive() {
        if (!interactive) return
        interactive = false
        transient = null
        resetTransform()
        startBreath()
        if (wantSway) startSway()
    }

    private fun resetTransform() {
        view.rotation = 0f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.setColorFilter(null)
    }

    private fun startBreath() {
        if (interactive || breathAnim != null) return
        // repeatCount / repeatMode 只属于 ValueAnimator（ObjectAnimator），
        // AnimatorSet 本身没有这两个属性，必须设在子动画上。
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, BREATH_SCALE, 1f).apply {
            duration = BREATH_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, BREATH_SCALE, 1f).apply {
            duration = BREATH_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        breathAnim = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun startSway() {
        if (swayAnim != null) return
        ensurePivot()
        swayAnim = ObjectAnimator.ofFloat(view, "rotation", -SWAY_DEGREE, SWAY_DEGREE).apply {
            duration = SWAY_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopSway() {
        swayAnim?.cancel()
        swayAnim = null
        if (!interactive) view.rotation = 0f
    }

    /** 立绘尺寸就绪后固定旋转支点（未测量时跳过，交互时必然已布局完成） */
    private fun ensurePivot() {
        if (view.width > 0 && view.height > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height * PIVOT_RATIO_Y
        }
    }

    private fun blushColor(alpha: Float): Int =
        Color.argb((alpha * 255f).toInt().coerceIn(0, 255), BLUSH_R, BLUSH_G, BLUSH_B)

    private fun dp(value: Float): Float = value * view.resources.displayMetrics.density
}
