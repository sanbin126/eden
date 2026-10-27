package com.example.eden.core.ui


import com.example.eden.R
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser

/**
 * 剧情对话框底：不透明主题底色 + 低透明度白玫瑰纹理 + 描边。
 *
 * 全部在同一层 Drawable 内绘制，不额外增加子 View，
 * 因此对话框的测量尺寸与原来完全一致（仍由文字内容 + 内边距决定）。
 *
 * 底色保持不透明，既能遮住立绘下半身，也保证文字对比度；
 * 玫瑰纹理只按宽度等比缩放、底边对齐，框向上变高时已显示的画面不变，只多露出图片上方。
 */
class StoryDialogBackdropDrawable : Drawable() {

    private var rose: Bitmap? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = ROSE_ALPHA
        isDither = true
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val rect = RectF()
    private val artDst = RectF()
    private val clipPath = Path()

    private var cornerRadius = 0f
    private var strokeWidth = 0f

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        super.inflate(r, parser, attrs, theme)
        rose = BitmapFactory.decodeResource(r, R.drawable.story_dialog_rose_art)
        fillPaint.color = resolveColor(r, theme, R.attr.edenColorSurfaceTint, R.color.eden_surface_tint)
        strokePaint.color = resolveColor(r, theme, R.attr.edenColorBorder, R.color.eden_border)
        cornerRadius = r.getDimensionPixelSize(R.dimen.radius_large).toFloat()
        strokeWidth = r.displayMetrics.density
        strokePaint.strokeWidth = strokeWidth
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val inset = strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)

        clipPath.reset()
        clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)

        val clipSave = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, fillPaint)
        rose?.let { bitmap ->
            // 只按宽度等比缩放：底边与框底对齐（对话框向下固定、向上变高），
            // 因此已显示过的画面内容不变，变高的部分只是多露出图片上方
            val scale = rect.width() / bitmap.width
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            val top = rect.bottom - dh
            artDst.set(rect.left, top, rect.left + dw, top + dh)
            canvas.drawBitmap(bitmap, null, artDst, artPaint)
        }
        canvas.restoreToCount(clipSave)

        canvas.drawPath(clipPath, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        artPaint.alpha = alpha * ROSE_ALPHA / 255
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        artPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** 优先取当前主题里的 ?attr，未定义时回退到默认皮肤色。 */
    private fun resolveColor(r: Resources, theme: Resources.Theme?, attr: Int, fallback: Int): Int {
        if (theme != null) {
            val value = TypedValue()
            if (theme.resolveAttribute(attr, value, true)) {
                when {
                    value.resourceId != 0 -> return r.getColor(value.resourceId, null)
                    value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
                        return value.data
                }
            }
        }
        return r.getColor(fallback, null)
    }

    private companion object {
        /** 玫瑰纹理不透明度：约 0.26，仅作纸面质感，不干扰阅读。 */
        const val ROSE_ALPHA = 66
    }
}
