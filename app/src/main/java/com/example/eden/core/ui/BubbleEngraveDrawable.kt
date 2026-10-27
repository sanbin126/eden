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
 * 聊天气泡右下角的玫瑰刻花层，左右气泡共用。
 *
 * 与主按钮的 [ChatEngraveDrawable] 同一套做法：
 * 1. 裁到气泡圆角内，花纹不会溢出圆角；
 * 2. 在离屏层里画整幅刻花线稿；
 * 3. 径向渐变按 DST_IN 擦除，只保留右下主花并向外柔化；
 * 4. 整层以「正片叠底」合成到气泡底色上。
 *
 * 区别在于气泡宽高随文字变化，所以刻花尺寸按气泡高度自适应并做上限，
 * 保证单行长消息、多行长消息都不会把花纹拉变形。
 */
class BubbleEngraveDrawable : Drawable() {

    private var engraving: Bitmap? = null
    private var density = 1f

    /** 刻花锚定角：默认右下，用户气泡改为左上。 */
    private var anchorTopLeft = false
    private var artAlpha = ART_ALPHA
    private var artScale = ART_SCALE
    private var maxSizeDp = MAX_SIZE_DP

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = ART_ALPHA
        isDither = true
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val clipPath = Path()
    private val layerBounds = RectF()
    private val artBounds = RectF()

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density

        val a = r.obtainAttributes(attrs, R.styleable.BubbleEngraveDrawable)
        anchorTopLeft = a.getInt(R.styleable.BubbleEngraveDrawable_edenEngraveCorner, 0) == 1
        artAlpha = a.getInt(R.styleable.BubbleEngraveDrawable_edenEngraveAlpha, ART_ALPHA)
        artScale = a.getFloat(R.styleable.BubbleEngraveDrawable_edenEngraveScale, ART_SCALE)
        maxSizeDp = a.getFloat(R.styleable.BubbleEngraveDrawable_edenEngraveMaxSize, MAX_SIZE_DP)
        a.recycle()

        artPaint.alpha = artAlpha
        engraving = loadArt(r)
    }

    override fun draw(canvas: Canvas) {
        val bitmap = engraving ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        // 刻花边长：跟随气泡高度，限制最大尺寸，同时不超过气泡宽度
        val width = b.width().toFloat()
        val height = b.height().toFloat()
        val size = minOf(artScale * height, maxSizeDp * density, MAX_WIDTH_RATIO * width)
        if (size <= 0f) return

        if (anchorTopLeft) {
            // 左上角：反推刻花方块位置，让主花落在左上角内侧，方块其余部分出血由气泡裁掉
            val artLeft = b.left + (CORNER_INSET_RATIO - MASK_CX) * size
            val artTop = b.top + (CORNER_INSET_RATIO - MASK_CY) * size
            artBounds.set(artLeft, artTop, artLeft + size, artTop + size)
        } else {
            // 右下角：花纹锚在右下角，向下/向右少量出血，由气泡边界裁掉
            val artRight = b.right - ART_RIGHT_RATIO * size
            val artBottom = b.bottom + ART_BOTTOM_RATIO * size
            artBounds.set(artRight - size, artBottom - size, artRight, artBottom)
        }

        // 1. 裁到气泡圆角内
        clipPath.reset()
        clipPath.addRoundRect(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            CORNER_DP * density, CORNER_DP * density, Path.Direction.CW
        )
        val clipSave = canvas.save()
        canvas.clipPath(clipPath)

        // 2. 离屏层：层内做刻花 + 径向擦除，整层再以正片叠底合成
        layerBounds.set(artBounds)
        val layerPaint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Paint().apply { blendMode = BlendMode.MULTIPLY }
        } else {
            null
        }
        val layerSave = canvas.saveLayer(layerBounds, layerPaint)

        canvas.drawBitmap(bitmap, null, artBounds, artPaint)

        // 3. 径向遮罩：中心落在右下主花上，向外羽化到完全透明
        maskPaint.shader = RadialGradient(
            artBounds.left + size * MASK_CX,
            artBounds.top + size * MASK_CY,
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
        artPaint.alpha = alpha * artAlpha / 255
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        artPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** 刻花整体不透明度：比主聊天按钮更淡，约 18%。 */
        const val ART_ALPHA = 46

        /** 主花在画面中的位置（整幅线稿天然落点）。 */
        const val MASK_CX = 0.73f
        const val MASK_CY = 0.77f

        /** 遮罩半径（相对刻花边长）。 */
        const val MASK_RADIUS = 0.62f

        /** 刻花边长相对气泡高度的倍数。 */
        const val ART_SCALE = 2.4f

        /** 刻花边长上限，避免多行气泡上的花纹过大。 */
        const val MAX_SIZE_DP = 160f

        /** 刻花边长不超过气泡宽度的比例，避免超短气泡里花纹被裁得太厉害。 */
        const val MAX_WIDTH_RATIO = 0.85f

        const val ART_RIGHT_RATIO = 0.06f
        const val ART_BOTTOM_RATIO = 0.04f

        /** 左上角锚定时，主花中心距离气泡左/上边缘的距离（相对刻花边长）。 */
        const val CORNER_INSET_RATIO = 0.16f

        /** 气泡圆角半径，与 bg_bubble_left / bg_bubble_right 保持一致。 */
        const val CORNER_DP = 20f

        @Volatile
        private var cachedArt: Bitmap? = null

        fun loadArt(r: Resources): Bitmap? =
            cachedArt ?: BitmapFactory.decodeResource(r, R.drawable.chat_engrave_rose)?.also { cachedArt = it }
    }
}
