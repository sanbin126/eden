package com.example.eden.feature.chat


import com.example.eden.R
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * 主聊天按钮外圈的动态柔光。
 *
 * 由若干圈「旋转的扫掠渐变」叠出柔和光晕（不用 BlurMaskFilter，硬件加速下也流畅），
 * 同时整体做缓慢的呼吸透明度。颜色与开关都由当前主题的属性决定：
 * · edenChatHaloEnabled —— 粉色主题为 false（不显示），蓝色绣球主题为 true；
 * · edenChatHaloColor1/2/3 —— 渐变的三个色标。
 */
class ChatHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 每一圈：宽度 / 半径，透明度系数（由外到内逐渐变亮，叠出柔光）。 */
    private val ringWidths = floatArrayOf(0.34f, 0.24f, 0.15f, 0.08f)
    private val ringAlphas = floatArrayOf(0.10f, 0.16f, 0.26f, 0.42f)

    /** 光圈中心线所在半径（相对整个 View 的半径）。 */
    private val ringRadiusFactor = 0.80f

    private val baseColors = IntArray(3)
    private val shaders = ArrayList<SweepGradient>(ringWidths.size)
    private val matrices = ArrayList<Matrix>(ringWidths.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var haloEnabled = false
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private var rotationAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null
    private var angle = 0f

    init {
        readTheme()
        if (!isInEditMode && !haloEnabled) visibility = GONE
    }

    private fun readTheme() {
        val theme = context.theme
        theme.obtainStyledAttributes(intArrayOf(R.attr.edenChatHaloEnabled)).let {
            haloEnabled = it.getBoolean(0, false)
            it.recycle()
        }
        theme.obtainStyledAttributes(
            intArrayOf(
                R.attr.edenChatHaloColor1,
                R.attr.edenChatHaloColor2,
                R.attr.edenChatHaloColor3
            )
        ).let {
            baseColors[0] = it.getColor(0, Color.TRANSPARENT)
            baseColors[1] = it.getColor(1, Color.TRANSPARENT)
            baseColors[2] = it.getColor(2, Color.TRANSPARENT)
            it.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f
        buildShaders()
    }

    private fun buildShaders() {
        if (radius <= 0f) return
        shaders.clear()
        matrices.clear()
        for (i in ringWidths.indices) {
            val alpha = (ringAlphas[i] * 255).toInt()
            val colors = intArrayOf(
                withAlpha(baseColors[0], alpha),
                withAlpha(baseColors[1], alpha),
                withAlpha(baseColors[2], alpha),
                withAlpha(baseColors[0], alpha)
            )
            shaders.add(SweepGradient(centerX, centerY, colors, null))
            matrices.add(Matrix())
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        if (!haloEnabled || radius <= 0f || shaders.isEmpty()) return
        for (i in shaders.indices) {
            matrices[i].setRotate(angle, centerX, centerY)
            shaders[i].setLocalMatrix(matrices[i])
            paint.shader = shaders[i]
            paint.strokeWidth = radius * ringWidths[i]
            canvas.drawCircle(centerX, centerY, radius * ringRadiusFactor, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (haloEnabled) startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        if (rotationAnimator != null) return
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 12000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { alpha = 0.7f + 0.3f * (it.animatedValue as Float) }
            start()
        }
    }

    private fun stopAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        breathAnimator?.cancel()
        breathAnimator = null
        alpha = 1f
    }
}
