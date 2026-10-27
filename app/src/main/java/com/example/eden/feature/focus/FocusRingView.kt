package com.example.eden.feature.focus


import com.example.eden.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import com.example.eden.ui.theme.edenColor

/**
 * 专注页中央的圆环进度控件。
 *
 * 用粉色（#FF66CC）在浅粉底环（#FFE3F0）上画出已完成比例，中心显示剩余时间与模式名，
 * 与 App 其他页面同款配色，不额外引入图片资源。
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = getContext().edenColor(R.attr.edenColorChipBg, R.color.eden_chip_bg)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = getContext().edenColor(R.attr.edenColorPrimary, R.color.eden_primary)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = getContext().edenColor(R.attr.edenColorTextPrimary, R.color.eden_text_primary)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = getContext().edenColor(R.attr.edenColorTextTertiary, R.color.eden_text_tertiary)
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()
    private var progress = 0f
    private var timeText = "25:00"
    private var labelText = "专注"

    /** 设置剩余时间文本，如 "24:59" */
    fun setTimeText(text: String) {
        if (timeText != text) {
            timeText = text
            invalidate()
        }
    }

    /** 设置中心下方的模式名，如 "专注" / "小憩" */
    fun setLabel(text: String) {
        if (labelText != text) {
            labelText = text
            invalidate()
        }
    }

    /** 设置已完成比例 0f~1f */
    fun setProgress(value: Float) {
        val p = value.coerceIn(0f, 1f)
        if (progress != p) {
            progress = p
            invalidate()
        }
    }

    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val stroke = dp(12f)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - stroke / 2f - dp(2f)
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, trackPaint)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        if (progress > 0f) {
            canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
        }

        timePaint.textSize = radius * 0.42f
        val fm = timePaint.fontMetrics
        val timeBaseline = cy + (-(fm.ascent + fm.descent) / 2f) - radius * 0.10f
        canvas.drawText(timeText, cx, timeBaseline, timePaint)

        labelPaint.textSize = radius * 0.17f
        canvas.drawText(labelText, cx, cy + radius * 0.34f, labelPaint)
    }
}
