package com.example.eden.feature.profile


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 头像圆形裁剪页。
 *
 * 交互：圆形取景框固定居中，图片可单指拖动、双指缩放（>= 1 倍，且始终铺满取景圆）。
 * 结果：按取景圆外接正方形裁剪出等比例图片，JPEG 写入 cacheDir，
 * 通过 [EXTRA_RESULT_PATH] 把绝对路径回传给调用方。
 */
class CropActivity : BaseActivity() {

    companion object {
        /** 入参：待裁剪图片的 Uri 字符串。 */
        const val EXTRA_SOURCE_URI = "source_uri"

        /** 出参：裁剪结果图片的绝对路径。 */
        const val EXTRA_RESULT_PATH = "result_path"

        /** 解码时最长边上限，避免大图 OOM。 */
        private const val MAX_IMAGE_SIDE = 2048

        /** 用户可放大的最大倍数。 */
        private const val MAX_SCALE = 6f
    }

    private lateinit var imageView: ImageView
    private lateinit var overlayView: CropOverlayView

    private var bitmap: Bitmap? = null

    /** 取景圆的外接正方形（相对根布局坐标）。 */
    private val cropRect = RectF()

    /** 初始「铺满取景框」的基准矩阵，不含用户的缩放与平移。 */
    private val baseMatrix = Matrix()

    /** 最终作用到 ImageView 上的显示矩阵。 */
    private val displayMatrix = Matrix()

    private var userScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var isDragging = false
    private var lastX = 0f
    private var lastY = 0f

    private var scaleDetector: ScaleGestureDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val root = findViewById<FrameLayout>(R.id.crop_root)
        imageView = findViewById(R.id.crop_image)
        overlayView = findViewById(R.id.crop_overlay)

        val uri = sourceUri() ?: run {
            finish()
            return
        }

        setupGesture()

        // 手势挂在根布局上：图片 / 遮罩不消费事件，底部按钮会自行消费，互不干扰
        root.setOnTouchListener { _, event -> handleTouch(event) }

        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_confirm).setOnClickListener { cropAndReturn() }

        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeBitmap(uri) }
            if (decoded == null) {
                toast(getString(R.string.crop_failed))
                finish()
                return@launch
            }
            bitmap = decoded
            imageView.setImageBitmap(decoded)
            imageView.scaleType = ImageView.ScaleType.MATRIX
            // 等遮罩测量完成后再算取景框
            overlayView.post { layoutCrop() }
        }
    }

    /** API 33+ 用新签名读 Parcelable，旧版本回退到已废弃写法。 */
    @Suppress("DEPRECATION")
    private fun sourceUri(): Uri? {
        val raw = intent.getStringExtra(EXTRA_SOURCE_URI) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    // ==================== 取景框与矩阵 ====================

    private fun layoutCrop() {
        val w = overlayView.width
        val h = overlayView.height
        if (w <= 0 || h <= 0) return

        val minSide = minOf(w, h)
        val side = (minSide - dp(48)).coerceAtLeast(dp(140)).coerceAtMost(minSide)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        cropRect.set(left, top, left + side, top + side)
        overlayView.cropRect = RectF(cropRect)

        resetTransform()
    }

    /** 把图片缩放到刚好铺满取景框并居中，作为基准矩阵。 */
    private fun resetTransform() {
        val bmp = bitmap ?: return
        val cover = maxOf(
            cropRect.width() / bmp.width.toFloat(),
            cropRect.height() / bmp.height.toFloat()
        )
        baseMatrix.reset()
        baseMatrix.setScale(cover, cover)
        baseMatrix.postTranslate(
            cropRect.centerX() - bmp.width * cover / 2f,
            cropRect.centerY() - bmp.height * cover / 2f
        )
        userScale = 1f
        offsetX = 0f
        offsetY = 0f
        applyMatrix()
    }

    /**
     * 由基准矩阵 + 用户缩放/平移重建显示矩阵，并把平移量钳制在
     * 「图片始终覆盖取景框」的范围内，避免拖出黑边。
     */
    private fun applyMatrix() {
        val bmp = bitmap ?: return

        val m = Matrix()
        m.set(baseMatrix)
        m.postScale(userScale, userScale, cropRect.centerX(), cropRect.centerY())
        m.postTranslate(offsetX, offsetY)

        val mapped = RectF()
        m.mapRect(mapped, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()))

        var adjustX = 0f
        var adjustY = 0f
        if (mapped.left > cropRect.left) adjustX = cropRect.left - mapped.left
        if (mapped.right < cropRect.right) adjustX = cropRect.right - mapped.right
        if (mapped.top > cropRect.top) adjustY = cropRect.top - mapped.top
        if (mapped.bottom < cropRect.bottom) adjustY = cropRect.bottom - mapped.bottom

        offsetX += adjustX
        offsetY += adjustY
        m.postTranslate(adjustX, adjustY)

        displayMatrix.set(m)
        imageView.imageMatrix = displayMatrix
    }

    // ==================== 手势 ====================

    private fun setupGesture() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val target = (userScale * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                    val ratio = target / userScale
                    if (ratio == 1f) return true

                    // 以捏合焦点为锚点缩放：偏移量按比例缩放后再叠加焦点变化
                    val ux = detector.focusX - cropRect.centerX()
                    val uy = detector.focusY - cropRect.centerY()
                    offsetX = ux * (1f - ratio) + offsetX * ratio
                    offsetY = uy * (1f - ratio) + offsetY * ratio
                    userScale = target
                    applyMatrix()
                    return true
                }
            }
        )
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    applyMatrix()
                    lastX = event.x
                    lastY = event.y
                }
            }

            // 第二根手指按下：本次手势交给缩放，暂停拖动
            MotionEvent.ACTION_POINTER_DOWN -> isDragging = false

            // 抬起一根手指：以剩下的那根为起点恢复拖动
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                isDragging = true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }

    // ==================== 裁剪输出 ====================

    private fun cropAndReturn() {
        val bmp = bitmap ?: return

        val inverse = Matrix()
        if (!displayMatrix.invert(inverse)) return

        // 取景框反算回原图坐标（矩阵无旋转，映射结果仍是矩形）
        val srcF = RectF()
        inverse.mapRect(srcF, cropRect)
        srcF.left = srcF.left.coerceIn(0f, bmp.width.toFloat())
        srcF.top = srcF.top.coerceIn(0f, bmp.height.toFloat())
        srcF.right = srcF.right.coerceIn(0f, bmp.width.toFloat())
        srcF.bottom = srcF.bottom.coerceIn(0f, bmp.height.toFloat())
        if (srcF.width() < 1f || srcF.height() < 1f) return

        val src = Rect()
        srcF.round(src)

        val size = cropRect.width().toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(
            bmp,
            src,
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        val outFile = File(cacheDir, "avatar_crop_${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            FileOutputStream(outFile).use { output.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.isSuccess
        output.recycle()

        if (!ok || outFile.length() <= 0) {
            toast(getString(R.string.crop_failed))
            return
        }

        setResult(RESULT_OK, intent.putExtra(EXTRA_RESULT_PATH, outFile.absolutePath))
        finish()
    }

    // ==================== 解码 ====================

    private fun decodeBitmap(uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeWithImageDecoder(uri)
        else decodeWithSampling(uri)

    /** API 28+：ImageDecoder 会自动应用 EXIF 方向，并按需采样。 */
    private fun decodeWithImageDecoder(uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 必须为软件位图，否则无法绘制到软件 Canvas
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_IMAGE_SIDE) {
                decoder.setTargetSampleSize(
                    Math.ceil(longest / MAX_IMAGE_SIDE.toDouble()).toInt()
                )
            }
        }
    }.getOrNull()

    /** API 28 以下：两段式采样解码（不处理 EXIF 旋转）。 */
    private fun decodeWithSampling(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
