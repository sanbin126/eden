package com.example.eden.core.ui


import com.example.eden.R
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser

/**
 * 主聊天按钮内部的玫瑰刻花层（仅蓝色绣球主题使用）。
 *
 * 绘制顺序：
 * 1. 先把画布裁到按钮圆形内，方形角落不会露出花纹；
 * 2. 在离屏层里画整幅刻花线稿；
 * 3. 用径向渐变按 DST_IN 擦除，只保留右下角主花并向外柔化过渡；
 * 4. 整层以「正片叠底」合到下面的磨砂玻璃上（API 29+ 精确生效，
 *    低版本硬件加速不支持 MULTIPLY，会退化为普通叠加，视觉差异很小）。
 *
 * 图层通过 layer-list 追加在玻璃背景的最上层，因此不会改变按钮尺寸与原有样式。
 */
class ChatEngraveDrawable : Drawable() {

    private var engraving: Bitmap? = null

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = ART_ALPHA
        isDither = true
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val circlePath = Path()
    private val layerBounds = RectF()
    private val artBounds = RectF()

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        super.inflate(r, parser, attrs, theme)
        engraving = BitmapFactory.decodeResource(r, R.drawable.chat_engrave_rose)
    }

    override fun draw(canvas: Canvas) {
        val bitmap = engraving ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val size = minOf(b.width(), b.height()).toFloat()
        val centerX = b.exactCenterX()
        val centerY = b.exactCenterY()

        // 1. 裁到圆形按钮内
        circlePath.reset()
        circlePath.addCircle(centerX, centerY, size / 2f, Path.Direction.CW)
        val clipSave = canvas.save()
        canvas.clipPath(circlePath)

        // 2. 离屏层：层内做刻花 + 径向擦除，整层再以正片叠底合成
        layerBounds.set(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f)
        val layerPaint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Paint().apply { blendMode = BlendMode.MULTIPLY }
        } else {
            null
        }
        val layerSave = canvas.saveLayer(layerBounds, layerPaint)

        artBounds.set(layerBounds)
        canvas.drawBitmap(bitmap, null, artBounds, artPaint)

        // 3. 径向遮罩：中心落在右下主花上，向外羽化到完全透明
        maskPaint.shader = RadialGradient(
            artBounds.left + artBounds.width() * MASK_CX,
            artBounds.top + artBounds.height() * MASK_CY,
            size * MASK_RADIUS,
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xB3000000.toInt(), 0x38000000, 0x00000000),
            floatArrayOf(0f, 0.41f, 0.65f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(artBounds, maskPaint)
        maskPaint.shader = null

        canvas.restoreToCount(layerSave)
        canvas.restoreToCount(clipSave)
    }

    override fun setAlpha(alpha: Int) {
        artPaint.alpha = alpha * ART_ALPHA / 255
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        artPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 刻花整体不透明度：再叠加按钮自身的 0.8 alpha 后约等于预览里的 0.55。 */
        const val ART_ALPHA = 175

        /** 主花在画面中的位置（整幅线稿天然落点）。 */
        const val MASK_CX = 0.73f
        const val MASK_CY = 0.77f

        /** 遮罩半径（相对按钮边长）：约覆盖按钮 1/4 面积。 */
        const val MASK_RADIUS = 0.62f
    }
}
