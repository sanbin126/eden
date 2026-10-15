package com.example.eden

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eden.character.MikuPersona
import kotlin.random.Random

/**
 * 扫雷小游戏：三玖陪你一起玩。
 *
 * 三个难度（手机友好尺寸）：
 * - 简单：9×9，10 雷
 * - 中等：12×12，24 雷
 * - 困难：16×16，50 雷
 *
 * 点击翻开，长按插旗。
 * 首次点击保证不是雷。
 * 翻开超过 10 格三玖夸一句；
 * 超过 30 秒无操作三玖提醒。
 */
class MinesweeperActivity : AppCompatActivity() {

    // ==================== 难度配置 ====================
    enum class Difficulty(val rows: Int, val cols: Int, val mines: Int, val label: String) {
        EASY(9, 9, 10, "简单"),
        MEDIUM(12, 12, 24, "中等"),
        HARD(16, 16, 50, "困难");
    }

    // ==================== 格子状态 ====================
    enum class CellState { COVERED, REVEALED, FLAGGED, EXPLODED }

    data class Cell(var hasMine: Boolean = false, var state: CellState = CellState.COVERED, var neighborMines: Int = 0)

    // ==================== 视图 ====================
    private lateinit var gridLayout: GridLayout
    private lateinit var tvMineCount: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnEasy: TextView
    private lateinit var btnMedium: TextView
    private lateinit var btnHard: TextView
    private lateinit var tvMikuBubble: TextView

    // ==================== 游戏状态 ====================
    private var difficulty = Difficulty.EASY
    private lateinit var board: Array<Array<Cell>>
    private var gameOver = false
    private var firstClick = true
    private var revealedCount = 0
    private var flaggedCount = 0
    private var praisedOnce = false

    // 计时
    private var timerHandler = Handler(Looper.getMainLooper())
    private var timerStart = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!gameOver) {
                val elapsed = ((SystemClock.elapsedRealtime() - timerStart) / 1000).toInt()
                tvTimer.text = "⏱ $elapsed"
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    // 30 秒无操作提示
    private var idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!gameOver) {
            showMikuLine(MikuPersona.MINESWEEPER_IDLE_LINES.random())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_minesweeper)

        initViews()
        setupListeners()
        startGame(Difficulty.EASY)
    }

    private fun initViews() {
        gridLayout = findViewById(R.id.grid_mines)
        tvMineCount = findViewById(R.id.tv_mine_count)
        tvTimer = findViewById(R.id.tv_timer)
        btnRestart = findViewById(R.id.btn_restart)
        btnEasy = findViewById(R.id.btn_easy)
        btnMedium = findViewById(R.id.btn_medium)
        btnHard = findViewById(R.id.btn_hard)
        tvMikuBubble = findViewById(R.id.tv_miku_bubble)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        btnEasy.setOnClickListener { startGame(Difficulty.EASY) }
        btnMedium.setOnClickListener { startGame(Difficulty.MEDIUM) }
        btnHard.setOnClickListener { startGame(Difficulty.HARD) }
        btnRestart.setOnClickListener { startGame(difficulty) }
    }

    private fun startGame(diff: Difficulty) {
        this.difficulty = diff
        gameOver = false
        firstClick = true
        revealedCount = 0
        flaggedCount = 0
        praisedOnce = false

        // 重置计时
        timerHandler.removeCallbacks(timerRunnable)
        tvTimer.text = getString(R.string.minesweeper_timer)
        timerStart = SystemClock.elapsedRealtime()
        timerHandler.post(timerRunnable)

        // 更新难度按钮选中态
        updateDifficultyButtons(diff)

        // 初始化棋盘数据
        board = Array(diff.rows) { Array(diff.cols) { Cell() } }

        // 三玖说一句话
        showMikuLine(MikuPersona.MINESWEEPER_START_LINES.random())

        // 重置闲置计时
        resetIdleTimer()

        // 渲染棋盘
        renderBoard()
        updateMineCount()
    }

    private fun updateDifficultyButtons(selected: Difficulty) {
        btnEasy.isSelected = (selected == Difficulty.EASY)
        btnMedium.isSelected = (selected == Difficulty.MEDIUM)
        btnHard.isSelected = (selected == Difficulty.HARD)
    }

    private fun placeMines(safeRow: Int, safeCol: Int) {
        val positions = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until difficulty.rows) {
            for (c in 0 until difficulty.cols) {
                if (r != safeRow || c != safeCol) {
                    positions.add(r to c)
                }
            }
        }
        positions.shuffle(Random)
        for (i in 0 until difficulty.mines) {
            val (r, c) = positions[i]
            board[r][c].hasMine = true
        }
        // 计算每个格子的相邻雷数
        for (r in 0 until difficulty.rows) {
            for (c in 0 until difficulty.cols) {
                if (!board[r][c].hasMine) {
                    board[r][c].neighborMines = countNeighborMines(r, c)
                }
            }
        }
    }

    private fun countNeighborMines(row: Int, col: Int): Int {
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = row + dr
                val nc = col + dc
                if (nr in 0 until difficulty.rows && nc in 0 until difficulty.cols && board[nr][nc].hasMine) {
                    count++
                }
            }
        }
        return count
    }

    @SuppressLint("SetTextI18n")
    private fun renderBoard() {
        gridLayout.removeAllViews()
        gridLayout.rowCount = difficulty.rows
        gridLayout.columnCount = difficulty.cols

        val cellSize = dpToPx(when (difficulty) {
            Difficulty.EASY -> 36
            Difficulty.MEDIUM -> 30
            Difficulty.HARD -> 26
        })

        for (r in 0 until difficulty.rows) {
            for (c in 0 until difficulty.cols) {
                val cell = board[r][c]
                val textView = TextView(this).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = cellSize
                        height = cellSize
                        rowSpec = GridLayout.spec(r)
                        columnSpec = GridLayout.spec(c)
                        setMargins(1, 1, 1, 1)
                    }
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, when (difficulty) {
                        Difficulty.EASY -> 16f
                        Difficulty.MEDIUM -> 14f
                        Difficulty.HARD -> 12f
                    })
                    background = ContextCompat.getDrawable(this@MinesweeperActivity, R.drawable.bg_mine_cell)
                    tag = r to c
                }
                updateCellView(textView, cell)

                textView.setOnClickListener {
                    if (gameOver) return@setOnClickListener
                    resetIdleTimer()
                    onCellClick(r, c)
                }
                textView.setOnLongClickListener {
                    if (gameOver) return@setOnLongClickListener true
                    resetIdleTimer()
                    onCellLongClick(r, c)
                    true
                }

                gridLayout.addView(textView)
            }
        }
    }

    private fun updateCellView(view: TextView, cell: Cell) {
        when (cell.state) {
            CellState.COVERED -> {
                view.text = ""
                view.setTextColor(Color.BLACK)
                view.background = ContextCompat.getDrawable(this, R.drawable.bg_mine_cell)
            }
            CellState.FLAGGED -> {
                view.text = "🚩"
                view.setTextColor(Color.BLACK)
                view.background = ContextCompat.getDrawable(this, R.drawable.bg_mine_cell)
            }
            CellState.REVEALED -> {
                view.background = ContextCompat.getDrawable(this, R.drawable.bg_mine_cell_revealed)
                if (cell.hasMine) {
                    view.text = getString(R.string.game_picker_mine_icon)
                    view.setTextColor(Color.BLACK)
                } else if (cell.neighborMines > 0) {
                    view.text = cell.neighborMines.toString()
                    view.setTextColor(numberColor(cell.neighborMines))
                } else {
                    view.text = ""
                }
            }
            CellState.EXPLODED -> {
                view.text = "💥"
                view.setTextColor(Color.RED)
                view.background = ContextCompat.getDrawable(this, R.drawable.bg_mine_cell_revealed)
            }
        }
    }

    private fun numberColor(n: Int): Int = when (n) {
        1 -> Color.parseColor("#2196F3")
        2 -> Color.parseColor("#4CAF50")
        3 -> Color.parseColor("#F44336")
        4 -> Color.parseColor("#9C27B0")
        5 -> Color.parseColor("#FF9800")
        6 -> Color.parseColor("#00BCD4")
        7 -> Color.parseColor("#000000")
        8 -> Color.parseColor("#607D8B")
        else -> Color.BLACK
    }

    private fun onCellClick(row: Int, col: Int) {
        val cell = board[row][col]
        if (cell.state == CellState.FLAGGED || cell.state == CellState.REVEALED) return

        if (firstClick) {
            firstClick = false
            placeMines(row, col)
        }

        if (cell.hasMine) {
            cell.state = CellState.EXPLODED
            gameOver = true
            timerHandler.removeCallbacks(timerRunnable)
            revealAllMines()
            refreshBoardViews()
            showMikuLine(MikuPersona.MINESWEEPER_LOSE_LINES.random())
            btnRestart.text = "😵"
            settleGame(win = false)
            return
        }

        // 连锁翻开
        revealCell(row, col)
        refreshBoardViews()
        updateMineCount()

        // 彩蛋：翻开超过 10 格夸一句（只触发一次）
        if (!praisedOnce && revealedCount > 10) {
            praisedOnce = true
            showMikuLine(MikuPersona.MINESWEEPER_PRAISE_LINES.random())
        }

        // 检查胜利
        if (checkWin()) {
            gameOver = true
            timerHandler.removeCallbacks(timerRunnable)
            showMikuLine(MikuPersona.MINESWEEPER_WIN_LINES.random())
            btnRestart.text = "😎"
            settleGame(win = true)
            Toast.makeText(this, "恭喜通关！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCellLongClick(row: Int, col: Int) {
        val cell = board[row][col]
        if (cell.state == CellState.REVEALED || cell.state == CellState.EXPLODED) return

        cell.state = if (cell.state == CellState.FLAGGED) CellState.COVERED else CellState.FLAGGED
        flaggedCount = board.flatten().count { it.state == CellState.FLAGGED }
        refreshCellView(row, col)
        updateMineCount()
    }

    private fun revealCell(row: Int, col: Int) {
        if (row !in 0 until difficulty.rows || col !in 0 until difficulty.cols) return
        val cell = board[row][col]
        if (cell.state != CellState.COVERED) return
        if (cell.hasMine) return

        cell.state = CellState.REVEALED
        revealedCount++

        if (cell.neighborMines == 0) {
            for (dr in -1..1) {
                for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    revealCell(row + dr, col + dc)
                }
            }
        }
    }

    private fun revealAllMines() {
        for (r in 0 until difficulty.rows) {
            for (c in 0 until difficulty.cols) {
                val cell = board[r][c]
                if (cell.hasMine && cell.state != CellState.EXPLODED) {
                    cell.state = CellState.REVEALED
                }
            }
        }
    }

    private fun checkWin(): Boolean {
        val totalCells = difficulty.rows * difficulty.cols
        return revealedCount == totalCells - difficulty.mines
    }

    private fun refreshBoardViews() {
        for (i in 0 until gridLayout.childCount) {
            val view = gridLayout.getChildAt(i) as TextView
            val (r, c) = view.tag as Pair<Int, Int>
            updateCellView(view, board[r][c])
        }
    }

    private fun refreshCellView(row: Int, col: Int) {
        for (i in 0 until gridLayout.childCount) {
            val view = gridLayout.getChildAt(i) as TextView
            val (r, c) = view.tag as Pair<Int, Int>
            if (r == row && c == col) {
                updateCellView(view, board[r][c])
                return
            }
        }
    }

    private fun updateMineCount() {
        val remaining = difficulty.mines - flaggedCount
        tvMineCount.text = "💣 $remaining"
    }

    // ==================== 三玖台词气泡 ====================
    /** 当前气泡的自动隐藏任务，新台词顶替旧台词时先取消。 */
    private var bubbleHide: Runnable? = null

    private fun showMikuLine(text: String) {
        bubbleHide?.let { tvMikuBubble.removeCallbacks(it) }
        tvMikuBubble.text = text
        tvMikuBubble.visibility = View.VISIBLE
        tvMikuBubble.alpha = 0f
        tvMikuBubble.animate().alpha(1f).setDuration(200).start()
        val hide = Runnable {
            tvMikuBubble.animate().alpha(0f).setDuration(300).withEndAction {
                tvMikuBubble.visibility = View.INVISIBLE
            }.start()
        }
        bubbleHide = hide
        tvMikuBubble.postDelayed(hide, 3500)
    }

    /**
     * 一局结束的收尾：结算奖励（円胜+10/负+5 日上限 50；好感每天首次 +3），
     * 并在结果台词之后再补一句出口台词，把用户从小游戏引回聊天 / 投喂。
     */
    private fun settleGame(win: Boolean) {
        GameRewards.settle(this, win)
        tvMikuBubble.postDelayed({
            if (!isFinishing && !isDestroyed) showMikuLine(MikuPersona.GAME_EXIT_LINES.random())
        }, 3600)
    }

    // ==================== 30 秒无操作提示 ====================
    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, 30000)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        idleHandler.removeCallbacks(idleRunnable)
        super.onDestroy()
    }
}
