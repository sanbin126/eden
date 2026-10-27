package com.example.eden.feature.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 五子棋棋盘自定义 View：只负责绘制与触摸坐标换算。
 *
 * 游戏规则、回合切换与 AI 都在 [GomokuActivity]，这里保持「纯视图」。
 * 棋盘用 [IntArray] 表示，长度 15*15，0 空 / 1 黑 / 2 白，index = row * SIZE + col。
 */
class GomokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 棋盘路数：15×15 个交叉点 */
        const val SIZE = 15
        const val EMPTY = 0
        const val BLACK = 1
        const val WHITE = 2
    }

    /** 落子回调，参数为棋盘下标 index = row * SIZE + col */
    var onCellTapped: ((Int) -> Unit)? = null

    /** 是否接受点击：AI 思考中 / 游戏结束时置 false */
    var inputEnabled: Boolean = true

    private var board: IntArray = IntArray(SIZE * SIZE)
    private var lastMove: Int = -1
    private var winLine: IntArray = IntArray(0)

    /** 相邻两个交叉点的像素间距 */
    private var cell: Float = 0f
    /** 第一个交叉点（左上角）的像素坐标 */
    private var origin: Float = 0f

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3E0C7")
        style = Paint.Style.FILL
    }
    private val boardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9BE9A")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8D6E63")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6D4C41")
        style = Paint.Style.FILL
    }
    private val blackStonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2B")
        style = Paint.Style.FILL
    }
    private val whiteStonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAFAFA")
        style = Paint.Style.FILL
    }
    private val stoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val winLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val rect = RectF()

    fun setBoard(board: IntArray) {
        this.board = board
        invalidate()
    }

    fun setLastMove(idx: Int) {
        lastMove = idx
        invalidate()
    }

    fun setWinLine(line: IntArray) {
        winLine = line
        invalidate()
    }

    /**
     * 强制正方形：高度用 wrap_content 时按可用宽度取正方形，
     * 高度被约束为确定值（EXACTLY）时取宽高较小者，保证棋盘不被拉扁。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            min(w, h)
        } else {
            w
        }
        if (size > 0) setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 左右各留半格边距，边缘棋子不会被裁切
        cell = w.toFloat() / (SIZE + 1)
        origin = cell
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return

        // 棋盘底色
        val radius = cell * 0.6f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, boardPaint)
        rect.inset(boardBorderPaint.strokeWidth / 2f, boardBorderPaint.strokeWidth / 2f)
        canvas.drawRoundRect(rect, radius, radius, boardBorderPaint)

        // 网格线
        val end = origin + (SIZE - 1) * cell
        for (i in 0 until SIZE) {
            val p = origin + i * cell
            canvas.drawLine(origin, p, end, p, linePaint)
            canvas.drawLine(p, origin, p, end, linePaint)
        }

        // 星位（天元 + 四角星）
        for (s in STAR_POINTS) {
            val cx = origin + (s % SIZE) * cell
            val cy = origin + (s / SIZE) * cell
            canvas.drawCircle(cx, cy, cell * 0.08f, starPaint)
        }

        // 棋子
        val stoneRadius = cell * 0.42f
        for (i in 0 until SIZE * SIZE) {
            val v = board[i]
            if (v == EMPTY) continue
            val cx = origin + (i % SIZE) * cell
            val cy = origin + (i / SIZE) * cell
            if (v == BLACK) {
                canvas.drawCircle(cx, cy, stoneRadius, blackStonePaint)
            } else {
                canvas.drawCircle(cx, cy, stoneRadius, whiteStonePaint)
                canvas.drawCircle(cx, cy, stoneRadius, stoneStrokePaint)
            }
        }

        // 最后一手标记
        if (lastMove in 0 until SIZE * SIZE && board[lastMove] != EMPTY) {
            val cx = origin + (lastMove % SIZE) * cell
            val cy = origin + (lastMove / SIZE) * cell
            canvas.drawCircle(cx, cy, cell * 0.14f, lastMovePaint)
        }

        // 胜利连线
        if (winLine.size >= 2) {
            val a = winLine.first()
            val b = winLine.last()
            canvas.drawLine(
                origin + (a % SIZE) * cell, origin + (a / SIZE) * cell,
                origin + (b % SIZE) * cell, origin + (b / SIZE) * cell,
                winLinePaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || cell <= 0f) return false
        if (event.action != MotionEvent.ACTION_DOWN) return true

        val col = ((event.x - origin) / cell).roundToInt()
        val row = ((event.y - origin) / cell).roundToInt()
        if (row !in 0 until SIZE || col !in 0 until SIZE) return true

        // 离最近交叉点太远则忽略，避免误触
        val cx = origin + col * cell
        val cy = origin + row * cell
        if (abs(event.x - cx) > cell * 0.45f || abs(event.y - cy) > cell * 0.45f) return true

        val idx = row * SIZE + col
        if (board[idx] == EMPTY) onCellTapped?.invoke(idx)
        return true
    }

    private val STAR_POINTS = intArrayOf(
        3 * SIZE + 3, 3 * SIZE + 11, 7 * SIZE + 7, 11 * SIZE + 3, 11 * SIZE + 11
    )
}
