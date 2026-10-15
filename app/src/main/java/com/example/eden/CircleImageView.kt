package com.example.eden

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 圆形头像控件：把图片按 centerCrop 铺满后裁成圆形，可选画一圈描边。
 *
 * 纯自绘实现，不引入第三方库。XML 支持 `app:circleRingWidth` / `app:circleRingColor`。
 */
class CircleImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val bounds = RectF()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        scaleType = ScaleType.CENTER_CROP
        val ta = context.obtainStyledAttributes(attrs, R.styleable.CircleImageView)
        try {
            ringPaint.strokeWidth = ta.getDimension(R.styleable.CircleImageView_circleRingWidth, 0f)
            ringPaint.color = ta.getColor(R.styleable.CircleImageView_circleRingColor, 0)
        } finally {
            ta.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (drawable == null) return

        val side = minOf(width, height).toFloat()
        if (side <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = side / 2f

        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(save)

        if (ringPaint.strokeWidth > 0f) {
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(bounds, 0f, 360f, false, ringPaint)
        }
    }
}
