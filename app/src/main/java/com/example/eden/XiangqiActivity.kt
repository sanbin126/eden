package com.example.eden

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eden.character.MikuPersona

/**
 * 象棋小游戏：人机对弈（三玖执黑）。
 *
 * - 棋盘 9×10，红先手；玩家固定执红先手，三玖执黑。
 * - 交互：点己方棋子选中 -> 高亮合法落点 -> 点落点走子；再点一次可取消。
 * - 走子规则、将军/绝杀判定、AI 见 [XiangqiAi]；绘制见 [XiangqiBoardView]。
 * - 三档 AI 难度：简单 / 中等 / 困难（搜索深度 1/2/3）。
 * - 三玖会在开局、思考、将军、胜负、闲置时说话（台词池见 [MikuPersona]）。
 *
 * AI 搜索较重，放在后台线程计算，结果经主线程 [Handler] 回投；
 * 并用 [gameId] 局号作废「重开后仍在途」的旧任务。
 */
class XiangqiActivity : AppCompatActivity() {

    private companion object {
        const val EMPTY = XiangqiBoardView.EMPTY
        const val RED = XiangqiBoardView.RED
        const val BLACK = XiangqiBoardView.BLACK

        /** 玩家固定执红（先手），AI 执黑 */
        const val PLAYER_SIDE = RED
        const val AI_SIDE = BLACK
    }

    // ==================== 视图 ====================
    private lateinit var boardView: XiangqiBoardView
    private lateinit var tvStatus: TextView
    private lateinit var tvMikuBubble: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnEasy: TextView
    private lateinit var btnMedium: TextView
    private lateinit var btnHard: TextView

    // ==================== 游戏状态 ====================
    private var board = XiangqiAi.initialBoard()
    private var aiLevel = XiangqiAi.Level.MEDIUM

    /** 当前该谁走子：RED / BLACK */
    private var current = RED
    private var gameOver = false
    private var aiThinking = false

    /** 当前选中的棋子下标，-1 表示未选中 */
    private var selected = -1

    /** 选中棋子的合法落点（供高亮与点击判定） */
    private var targets = IntArray(0)

    /** 最后一手的起点 / 终点，供棋盘标注 */
    private var lastFrom = -1
    private var lastTo = -1

    /** 局号：重开 / 换模式时自增，用于作废在途的 AI 任务 */
    private var gameId = 0

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!gameOver && !aiThinking) showMikuLine(MikuPersona.XIANGQI_IDLE_LINES.random())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xiangqi)

        initViews()
        setupListeners()
        startGame()
    }

    private fun initViews() {
        boardView = findViewById(R.id.board_view)
        tvStatus = findViewById(R.id.tv_status)
        tvMikuBubble = findViewById(R.id.tv_miku_bubble)
        btnRestart = findViewById(R.id.btn_restart)
        btnEasy = findViewById(R.id.btn_easy)
        btnMedium = findViewById(R.id.btn_medium)
        btnHard = findViewById(R.id.btn_hard)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        boardView.onCellTapped = { idx -> onCellTapped(idx) }
    }

    private fun setupListeners() {
        btnEasy.setOnClickListener { if (aiLevel != XiangqiAi.Level.EASY) { aiLevel = XiangqiAi.Level.EASY; startGame() } }
        btnMedium.setOnClickListener { if (aiLevel != XiangqiAi.Level.MEDIUM) { aiLevel = XiangqiAi.Level.MEDIUM; startGame() } }
        btnHard.setOnClickListener { if (aiLevel != XiangqiAi.Level.HARD) { aiLevel = XiangqiAi.Level.HARD; startGame() } }
        btnRestart.setOnClickListener { startGame() }
    }

    private fun startGame() {
        gameId++
        board = XiangqiAi.initialBoard()
        current = RED
        gameOver = false
        aiThinking = false
        selected = -1
        targets = IntArray(0)
        lastFrom = -1
        lastTo = -1

        boardView.setBoard(board)
        boardView.setSelection(-1, IntArray(0))
        boardView.setLastMove(-1, -1)
        boardView.setCheck(-1)
        boardView.inputEnabled = true
        btnRestart.text = getString(R.string.common_status_emoji)

        updateDifficultyButtons()
        updateStatus()
        showMikuLine(MikuPersona.XIANGQI_START_LINES.random())
        resetIdleTimer()
    }

    private fun updateDifficultyButtons() {
        btnEasy.isSelected = aiLevel == XiangqiAi.Level.EASY
        btnMedium.isSelected = aiLevel == XiangqiAi.Level.MEDIUM
        btnHard.isSelected = aiLevel == XiangqiAi.Level.HARD
    }

    // ==================== 走子交互 ====================
    private fun onCellTapped(idx: Int) {
        if (gameOver || aiThinking) return
        // 玩家固定执红；AI 回合 / 思考中不接受点击
        if (current != PLAYER_SIDE) return
        resetIdleTimer()

        val piece = board[idx]

        if (selected >= 0) {
            // 再点一次选中格 -> 取消选中
            if (idx == selected) {
                clearSelection()
                return
            }
            // 点到己方其它棋子 -> 改选
            if (piece != EMPTY && XiangqiAi.sideOf(piece) == current) {
                selectPiece(idx)
                return
            }
            // 点到合法落点 -> 走子
            if (targets.contains(idx)) {
                commitMove(selected, idx)
                return
            }
            // 其它情况忽略
            return
        }

        // 未选中时只能点己方棋子
        if (piece != EMPTY && XiangqiAi.sideOf(piece) == current) {
            selectPiece(idx)
        }
    }

    private fun selectPiece(idx: Int) {
        selected = idx
        targets = XiangqiAi.legalMovesFrom(board, idx)
        boardView.setSelection(idx, targets)
    }

    private fun clearSelection() {
        selected = -1
        targets = IntArray(0)
        boardView.setSelection(-1, IntArray(0))
    }

    /** 落子 + 视图更新 + 回合切换 + 后续判定，玩家与 AI 共用。 */
    private fun commitMove(from: Int, to: Int) {
        board[to] = board[from]
        board[from] = EMPTY
        lastFrom = from
        lastTo = to
        selected = -1
        targets = IntArray(0)

        boardView.setSelection(-1, IntArray(0))
        boardView.setLastMove(from, to)
        boardView.setCheck(-1)

        current = -current
        afterMove()
    }

    /** 走子后：判定绝杀 / 困毙 / 将军，并在轮到 AI 时发起思考。 */
    private fun afterMove() {
        val inCheck = XiangqiAi.isInCheck(board, current)
        val hasMove = XiangqiAi.hasAnyLegalMove(board, current)

        if (!hasMove) {
            // 绝杀（被将且无解）或困毙（未被将但无棋可走），均判走子方获胜
            gameOver = true
            boardView.inputEnabled = false
            boardView.setCheck(XiangqiAi.findKing(board, current))
            finishGame(-current)
            return
        }

        if (inCheck) {
            boardView.setCheck(XiangqiAi.findKing(board, current))
            showMikuLine(MikuPersona.XIANGQI_CHECK_LINES.random())
        } else {
            boardView.setCheck(-1)
        }

        if (current == AI_SIDE) {
            aiTurn()
        } else {
            updateStatus()
            resetIdleTimer()
        }
    }

    private fun finishGame(winner: Int) {
        if (winner == PLAYER_SIDE) {
            tvStatus.text = "你赢了！"
            btnRestart.text = "😎"
            showMikuLine(MikuPersona.XIANGQI_WIN_LINES.random())
        } else {
            tvStatus.text = "三玖赢了"
            btnRestart.text = "😵"
            showMikuLine(MikuPersona.XIANGQI_LOSE_LINES.random())
        }
        settleGame(win = winner == PLAYER_SIDE)
        Toast.makeText(this, tvStatus.text, Toast.LENGTH_SHORT).show()
    }

    /** AI 回合：后台线程搜索，主线程回投；用快照 + 局号避免重开竞态。 */
    private fun aiTurn() {
        aiThinking = true
        boardView.inputEnabled = false
        updateStatus()
        showMikuLine(MikuPersona.XIANGQI_AI_THINK_LINES.random())

        val myGame = gameId
        val snapshot = board.copyOf()
        Thread {
            val move = XiangqiAi.bestMove(snapshot, AI_SIDE, aiLevel)
            handler.post {
                if (myGame != gameId) return@post
                aiThinking = false
                if (gameOver) return@post
                boardView.inputEnabled = true
                if (move >= 0) {
                    commitMove(XiangqiAi.fromOf(move), XiangqiAi.toOf(move))
                } else {
                    updateStatus()
                }
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        if (gameOver) return
        tvStatus.text = if (aiThinking) "三玖思考中…" else getString(R.string.xiangqi_status_red)
    }

    // ==================== 三玖台词气泡 ====================
    /** 当前气泡的自动隐藏任务，新台词顶替旧台词时先取消，避免"新句被旧句的定时器提前隐藏"。 */
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
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 30000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
