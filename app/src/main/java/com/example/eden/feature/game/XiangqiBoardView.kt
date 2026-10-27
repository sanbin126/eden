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
 * 象棋棋盘自定义 View：只负责绘制与触摸坐标换算。
 *
 * 走子规则、胜负判定与 AI 都在 [XiangqiAi] / [XiangqiActivity]，这里保持「纯视图」。
 *
 * 棋盘用 [IntArray] 表示，长度 [COLS]*[ROWS] = 9*10 = 90，
 * index = row * COLS + col；row 0 在上方（黑方底线），row 9 在下方（红方底线）。
 * 棋子用带符号整数表示：正数为红方、负数为黑方，绝对值为棋子类型（见 [KING] 等常量）。
 */
class XiangqiBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 列数（竖线 9 条） */
        const val COLS = 9
        /** 行数（横线 10 条） */
        const val ROWS = 10

        const val EMPTY = 0

        /** 棋子类型（取绝对值后判断） */
        const val KING = 1      // 帅 / 将
        const val ADVISOR = 2   // 仕 / 士
        const val ELEPHANT = 3  // 相 / 象
        const val HORSE = 4     // 马
        const val ROOK = 5      // 车
        const val CANNON = 6    // 炮
        const val PAWN = 7      // 兵 / 卒

        /** 阵营：红方为正（先手），黑方为负 */
        const val RED = 1
        const val BLACK = -1

        fun typeOf(piece: Int): Int = abs(piece)
        fun sideOf(piece: Int): Int = if (piece > 0) RED else BLACK
    }

    /** 落子回调，参数为棋盘下标 index = row * COLS + col */
    var onCellTapped: ((Int) -> Unit)? = null

    /** 是否接受点击：AI 思考中 / 游戏结束时置 false */
    var inputEnabled: Boolean = true

    private var board: IntArray = IntArray(COLS * ROWS)
    private var selected: Int = -1
    private var targets: IntArray = IntArray(0)
    private var lastFrom: Int = -1
    private var lastTo: Int = -1
    private var checkIdx: Int = -1

    /** 相邻两个交叉点的像素间距 */
    private var cell: Float = 0f
    /** 左上角交叉点的像素坐标 */
    private var originX: Float = 0f
    private var originY: Float = 0f

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
        strokeWidth = 1.6f
    }
    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A1887F")
        textAlign = Paint.Align.CENTER
        textSize = 16f
    }
    private val redStonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF3E0")
        style = Paint.Style.FILL
    }
    private val stoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BCAAA4")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FF4081")
        style = Paint.Style.FILL
    }
    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A65")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val rect = RectF()

    fun setBoard(board: IntArray) {
        this.board = board
        invalidate()
    }

    fun setSelection(idx: Int, targets: IntArray) {
        selected = idx
        this.targets = targets
        invalidate()
    }

    fun setLastMove(from: Int, to: Int) {
        lastFrom = from
        lastTo = to
        invalidate()
    }

    fun setCheck(idx: Int) {
        checkIdx = idx
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 四周各留半格以上边距，边缘棋子不被裁切
        cell = min(w / (COLS + 1f), h / (ROWS + 1f))
        originX = (w - (COLS - 1) * cell) / 2f
        originY = (h - (ROWS - 1) * cell) / 2f
    }

    private fun cx(col: Int) = originX + col * cell
    private fun cy(row: Int) = originY + row * cell

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return

        // 木质底板
        val margin = cell * 0.6f
        val radius = cell * 0.4f
        rect.set(
            originX - margin, originY - margin,
            originX + (COLS - 1) * cell + margin,
            originY + (ROWS - 1) * cell + margin
        )
        canvas.drawRoundRect(rect, radius, radius, boardPaint)
        rect.inset(boardBorderPaint.strokeWidth / 2f, boardBorderPaint.strokeWidth / 2f)
        canvas.drawRoundRect(rect, radius, radius, boardBorderPaint)

        // 横线
        for (r in 0 until ROWS) {
            canvas.drawLine(cx(0), cy(r), cx(COLS - 1), cy(r), linePaint)
        }
        // 竖线：最左最右贯通，其余在楚河汉界处断开
        val riverTop = cy(4)
        val riverBottom = cy(5)
        for (c in 0 until COLS) {
            if (c == 0 || c == COLS - 1) {
                canvas.drawLine(cx(c), cy(0), cx(c), cy(ROWS - 1), linePaint)
            } else {
                canvas.drawLine(cx(c), cy(0), cx(c), riverTop, linePaint)
                canvas.drawLine(cx(c), riverBottom, cx(c), cy(ROWS - 1), linePaint)
            }
        }
        // 九宫斜线（上、下各一个）
        canvas.drawLine(cx(3), cy(0), cx(5), cy(2), linePaint)
        canvas.drawLine(cx(5), cy(0), cx(3), cy(2), linePaint)
        canvas.drawLine(cx(3), cy(7), cx(5), cy(9), linePaint)
        canvas.drawLine(cx(5), cy(7), cx(3), cy(9), linePaint)

        // 楚河汉界
        riverTextPaint.textSize = cell * 0.5f
        val riverCenterY = (riverTop + riverBottom) / 2f - (riverTextPaint.descent() + riverTextPaint.ascent()) / 2f
        canvas.drawText("楚 河", cx(2), riverCenterY, riverTextPaint)
        canvas.drawText("汉 界", cx(6), riverCenterY, riverTextPaint)

        // 可落点提示
        val dotRadius = cell * 0.12f
        for (t in targets) {
            canvas.drawCircle(cx(t % COLS), cy(t / COLS), dotRadius, targetPaint)
        }

        // 棋子
        val stoneRadius = cell * 0.44f
        redTextPaint.textSize = cell * 0.58f
        blackTextPaint.textSize = cell * 0.58f
        for (i in 0 until COLS * ROWS) {
            val v = board[i]
            if (v == EMPTY) continue
            val px = cx(i % COLS)
            val py = cy(i / COLS)
            val red = v > 0
            canvas.drawCircle(px, py, stoneRadius, redStonePaint)
            stoneStrokePaint.color = if (red) Color.parseColor("#E57373") else Color.parseColor("#9E9E9E")
            canvas.drawCircle(px, py, stoneRadius, stoneStrokePaint)

            val textPaint = if (red) redTextPaint else blackTextPaint
            val textY = py - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(pieceChar(v), px, textY, textPaint)
        }

        // 最后一手：起点描边 + 终点描边
        if (lastFrom in 0 until COLS * ROWS) {
            canvas.drawCircle(cx(lastFrom % COLS), cy(lastFrom / COLS), stoneRadius, lastMovePaint)
        }
        if (lastTo in 0 until COLS * ROWS) {
            canvas.drawCircle(cx(lastTo % COLS), cy(lastTo / COLS), stoneRadius, lastMovePaint)
        }

        // 选中标记
        if (selected in 0 until COLS * ROWS) {
            canvas.drawCircle(cx(selected % COLS), cy(selected / COLS), stoneRadius + 2f, selectedPaint)
        }

        // 被将军的帅 / 将描红
        if (checkIdx in 0 until COLS * ROWS) {
            canvas.drawCircle(cx(checkIdx % COLS), cy(checkIdx / COLS), stoneRadius + 3f, checkPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || cell <= 0f) return false
        if (event.action != MotionEvent.ACTION_DOWN) return true

        val col = ((event.x - originX) / cell).roundToInt()
        val row = ((event.y - originY) / cell).roundToInt()
        if (row !in 0 until ROWS || col !in 0 until COLS) return true

        // 离最近交叉点太远则忽略，避免误触
        if (abs(event.x - cx(col)) > cell * 0.5f || abs(event.y - cy(row)) > cell * 0.5f) return true

        onCellTapped?.invoke(row * COLS + col)
        return true
    }

    /** 棋子展示用汉字：红方 / 黑方用字不同 */
    private fun pieceChar(piece: Int): String {
        val red = piece > 0
        return when (typeOf(piece)) {
            KING -> if (red) "帅" else "将"
            ADVISOR -> if (red) "仕" else "士"
            ELEPHANT -> if (red) "相" else "象"
            HORSE -> "马"
            ROOK -> "车"
            CANNON -> "炮"
            PAWN -> if (red) "兵" else "卒"
            else -> ""
        }
    }
}
