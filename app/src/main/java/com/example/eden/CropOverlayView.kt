package com.example.eden

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 裁剪页的圆形取景遮罩：圆形区域外一层半透明黑幕，圆形边缘画一圈白色描边。
 *
 * 不消费触摸事件（[onTouchEvent] 返回 false），手势会穿透到下层图片/父容器，
 * 由 [CropActivity] 统一处理拖动与缩放。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 取景圆所在的正方形区域（相对本 View 坐标），由 Activity 计算后写入。 */
    var cropRect: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = Color.WHITE
    }

    private val outerPath = Path()
    private val holePath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = cropRect ?: return

        val radius = rect.width() / 2f
        val cx = rect.centerX()
        val cy = rect.centerY()

        // 全屏矩形挖去中间的圆，得到「只留四周」的遮罩路径
        outerPath.reset()
        outerPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        holePath.reset()
        holePath.addCircle(cx, cy, radius, Path.Direction.CW)
        outerPath.op(holePath, Path.Op.DIFFERENCE)

        canvas.drawPath(outerPath, scrimPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
