package com.example.eden

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HeartParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val particles = mutableListOf<Particle>()
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colors: IntArray =
        getContext().resources.obtainTypedArray(R.array.eden_greeting_colors).let { ta ->
            val arr = IntArray(ta.length()) { ta.getColor(it, 0) }
            ta.recycle()
            arr
        }

    data class Particle(
        var x: Float,
        var y: Float,
        var size: Float,
        var alpha: Float,
        var rotation: Float,
        var speedY: Float,
        var speedX: Float,
        var rotationSpeed: Float,
        var color: Int
    )

    fun emit(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float) {
        // 面部区域（避开）
        val faceLeft = centerX - radiusX * 0.2f
        val faceRight = centerX + radiusX * 0.2f
        val faceTop = centerY - radiusY * 0.1f
        val faceBottom = centerY + radiusY * 0.4f

        particles.clear()
        val count = Random.nextInt(12, 16)
        repeat(count) {
            var px: Float
            var py: Float
            do {
                val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
                val dist = Random.nextFloat() * radiusX * 0.7f + radiusX * 0.15f
                px = centerX + cos(angle.toDouble()).toFloat() * dist
                py = centerY + sin(angle.toDouble()).toFloat() * dist * 0.6f
            } while (px in faceLeft..faceRight && py in faceTop..faceBottom)

            particles.add(
                Particle(
                    x = px,
                    y = py,
                    size = Random.nextFloat() * 10f + 5f,
                    alpha = 0.3f + Random.nextFloat() * 0.5f,
                    rotation = Random.nextFloat() * 360f,
                    speedY = Random.nextFloat() * 1.8f + 0.4f,
                    speedX = (Random.nextFloat() - 0.5f) * 0.8f,
                    rotationSpeed = (Random.nextFloat() - 0.5f) * 5f,
                    color = colors.random()
                )
            )
        }

        animator?.cancel()
        var elapsed = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500L
            addUpdateListener {
                val progress = it.animatedFraction
                elapsed += it.animatedFraction
                particles.forEach { p ->
                    p.y -= p.speedY
                    p.x += p.speedX
                    p.rotation += p.rotationSpeed
                    val flicker = 0.4f + 0.6f * sin(progress * Math.PI.toFloat() * 5f + p.x)
                    p.alpha = flicker.coerceIn(0.15f, 0.9f)
                }
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    animate().alpha(0f).setDuration(400).withEndAction {
                        particles.clear()
                        alpha = 1f
                        visibility = GONE
                    }
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in particles) {
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)
            paint.color = p.color
            paint.alpha = (p.alpha * 255).toInt()
            drawHeart(canvas, 0f, 0f, p.size, paint)
            canvas.restore()
        }
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size / 2f
        val path = Path()
        path.moveTo(cx, cy + s * 0.6f)
        path.cubicTo(cx - s, cy - s * 0.3f, cx - s, cy + s * 0.4f, cx, cy + s)
        path.cubicTo(cx + s, cy + s * 0.4f, cx + s, cy - s * 0.3f, cx, cy + s * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
    }
}