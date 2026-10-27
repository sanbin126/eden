package com.example.eden.feature.game


import com.example.eden.R
import com.example.eden.core.ui.BaseActivity
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.example.eden.domain.character.MikuPersona

/**
 * 五子棋小游戏：人机对弈（三玖执白）。
 *
 * - 棋盘 15×15，黑先手；玩家固定执黑先手。
 * - 三档 AI 难度：简单 / 中等 / 困难，见 [GomokuAi]。
 * - 三玖会在开局、思考、胜负、闲置时说话（台词池见 [MikuPersona]）。
 */
class GomokuActivity : BaseActivity() {

    private companion object {
        const val EMPTY = GomokuBoardView.EMPTY
        const val BLACK = GomokuBoardView.BLACK
        const val WHITE = GomokuBoardView.WHITE
        const val SIZE = GomokuBoardView.SIZE

        /** 四个方向：横、竖、右下、右上 */
        val DR = intArrayOf(0, 1, 1, 1)
        val DC = intArrayOf(1, 0, 1, -1)
    }

    // ==================== 视图 ====================
    private lateinit var boardView: GomokuBoardView
    private lateinit var tvStatus: TextView
    private lateinit var tvMikuBubble: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnEasy: TextView
    private lateinit var btnMedium: TextView
    private lateinit var btnHard: TextView

    // ==================== 游戏状态 ====================
    private var board = IntArray(SIZE * SIZE)
    private var aiLevel = GomokuAi.Level.MEDIUM
    private var current = BLACK
    private var gameOver = false
    private var aiThinking = false
    private var winLine = IntArray(0)

    /** 局号：重开/换模式时自增，用于作废在途的 AI 延时任务 */
    private var gameId = 0

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!gameOver) showMikuLine(MikuPersona.GOMOKU_IDLE_LINES.random())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gomoku)

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
        btnEasy.setOnClickListener { if (aiLevel != GomokuAi.Level.EASY) { aiLevel = GomokuAi.Level.EASY; startGame() } }
        btnMedium.setOnClickListener { if (aiLevel != GomokuAi.Level.MEDIUM) { aiLevel = GomokuAi.Level.MEDIUM; startGame() } }
        btnHard.setOnClickListener { if (aiLevel != GomokuAi.Level.HARD) { aiLevel = GomokuAi.Level.HARD; startGame() } }
        btnRestart.setOnClickListener { startGame() }
    }

    private fun startGame() {
        gameId++
        board = IntArray(SIZE * SIZE)
        current = BLACK
        gameOver = false
        aiThinking = false
        winLine = IntArray(0)

        boardView.setBoard(board)
        boardView.setLastMove(-1)
        boardView.setWinLine(IntArray(0))
        boardView.inputEnabled = true
        btnRestart.text = getString(R.string.common_status_emoji)

        updateDifficultyButtons()
        updateStatus()
        showMikuLine(MikuPersona.GOMOKU_START_LINES.random())
        resetIdleTimer()
    }

    private fun updateDifficultyButtons() {
        btnEasy.isSelected = aiLevel == GomokuAi.Level.EASY
        btnMedium.isSelected = aiLevel == GomokuAi.Level.MEDIUM
        btnHard.isSelected = aiLevel == GomokuAi.Level.HARD
    }

    // ==================== 落子流程 ====================
    private fun onCellTapped(idx: Int) {
        if (gameOver || aiThinking) return
        // 玩家固定执黑；思考中/白方回合不接受点击
        if (current != BLACK) return
        if (board[idx] != EMPTY) return
        resetIdleTimer()
        place(idx, current)
    }

    private fun place(idx: Int, player: Int) {
        board[idx] = player
        boardView.setLastMove(idx)
        boardView.invalidate()

        val line = findWinLine(idx, player)
        if (line != null) {
            winLine = line
            boardView.setWinLine(line)
            gameOver = true
            boardView.inputEnabled = false
            finishGame(player)
            return
        }

        if (board.all { it != EMPTY }) {
            gameOver = true
            boardView.inputEnabled = false
            tvStatus.text = "平局"
            showMikuLine(MikuPersona.GOMOKU_DRAW_LINES.random())
            settleGame(win = false)
            Toast.makeText(this, "平局", Toast.LENGTH_SHORT).show()
            return
        }

        current = if (player == BLACK) WHITE else BLACK
        updateStatus()

        if (current == WHITE) {
            aiTurn()
        }
    }

    private fun finishGame(winner: Int) {
        if (winner == BLACK) {
            tvStatus.text = "你赢了！"
            btnRestart.text = "😎"
            showMikuLine(MikuPersona.GOMOKU_WIN_LINES.random())
        } else {
            tvStatus.text = "三玖赢了"
            btnRestart.text = "😵"
            showMikuLine(MikuPersona.GOMOKU_LOSE_LINES.random())
        }
        settleGame(win = winner == BLACK)
        Toast.makeText(this, tvStatus.text, Toast.LENGTH_SHORT).show()
    }

    /** AI 回合：短延时后落子，延时里用局号防止「重开后 AI 落在旧棋盘」 */
    private fun aiTurn() {
        aiThinking = true
        boardView.inputEnabled = false
        updateStatus()
        showMikuLine(MikuPersona.GOMOKU_AI_THINK_LINES.random())

        val myGame = gameId
        handler.postDelayed({
            if (myGame != gameId || gameOver) return@postDelayed
            val move = GomokuAi.bestMove(board, WHITE, aiLevel)
            aiThinking = false
            boardView.inputEnabled = true
            if (move >= 0 && !gameOver) {
                place(move, WHITE)
            } else {
                updateStatus()
            }
        }, 400)
    }

    /** 从落子点向四个方向找连续的 >=5 连珠，返回整条连线的下标，未连成返回 null */
    private fun findWinLine(idx: Int, player: Int): IntArray? {
        val r = idx / SIZE
        val c = idx % SIZE
        for (d in 0 until 4) {
            val dr = DR[d]
            val dc = DC[d]
            val cells = ArrayList<Int>()
            cells.add(idx)

            var rr = r + dr
            var cc = c + dc
            while (rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == player) {
                cells.add(rr * SIZE + cc)
                rr += dr
                cc += dc
            }
            rr = r - dr
            cc = c - dc
            while (rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == player) {
                cells.add(0, rr * SIZE + cc)
                rr -= dr
                cc -= dc
            }
            if (cells.size >= 5) return cells.toIntArray()
        }
        return null
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        if (gameOver) return
        tvStatus.text = if (aiThinking) "三玖思考中…" else getString(R.string.gomoku_status_black)
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

/**
 * 五子棋 AI：基于棋型评分的启发式搜索，够用且快，适合手机端即时落子。
 *
 * 思路：
 * 1. 我方下一步能连五就直接赢；对手下一步能连五就必堵。
 * 2. 其余候选点按「我方落这里能形成的棋型分 + 对手落这里能形成的棋型分 × 防守权重」排序，
 *    一次求和即可把活三 / 冲四 / 双三这类威胁自然地算进去（各方向分数累加）。
 * 3. 三档难度：简单带随机且防守弱、中等贪心取最优、困难在贪心基础上做一步预判，
 *    避免「只顾自己进攻、不去堵对方四」的低级失误。
 *
 * 说明：这里只做「连续子 + 两端封堵」的棋型识别（不含跳三/跳四这类断点棋型），
 * 强度足以陪玩，但不追求职业级棋力。
 */
object GomokuAi {

    private const val SIZE = GomokuBoardView.SIZE
    private const val EMPTY = GomokuBoardView.EMPTY
    private const val BLACK = GomokuBoardView.BLACK
    private const val WHITE = GomokuBoardView.WHITE

    private val DR = intArrayOf(0, 1, 1, 1)
    private val DC = intArrayOf(1, 0, 1, -1)

    enum class Level { EASY, MEDIUM, HARD }

    /** 返回 AI 的最佳落子下标；棋盘已满返回 -1。 */
    fun bestMove(board: IntArray, ai: Int, level: Level): Int {
        val human = if (ai == BLACK) WHITE else BLACK
        val cands = candidates(board)
        if (cands.isEmpty()) return -1

        // 1. 自己一步连五 -> 直接取胜
        for (c in cands) if (makesFive(board, c, ai)) return c
        // 2. 对手一步连五 -> 必须堵
        for (c in cands) if (makesFive(board, c, human)) return c

        val defenseWeight = when (level) {
            Level.EASY -> 0.55
            Level.MEDIUM -> 1.0
            Level.HARD -> 1.05
        }

        val scored = cands
            .map { c -> c to (evaluatePoint(board, c, ai) + evaluatePoint(board, c, human) * defenseWeight) }
            .sortedByDescending { it.second }

        return when (level) {
            // 简单：在前几个还不错的点里随机，偶尔会漏堵，玩家更好赢
            Level.EASY -> scored.take(minOf(6, scored.size)).random().first
            Level.MEDIUM -> scored.first().first
            Level.HARD -> pickHard(board, ai, human, scored)
        }
    }

    /** 困难难度：对贪心排前的候选各试落一步，减去对手最佳回应的威胁分，取净收益最高者 */
    private fun pickHard(
        board: IntArray,
        ai: Int,
        human: Int,
        scored: List<Pair<Int, Double>>
    ): Int {
        val top = scored.take(10)
        var bestMove = top.first().first
        var bestNet = Double.NEGATIVE_INFINITY
        for ((m, myGain) in top) {
            board[m] = ai
            val oppReply = bestPointScore(board, human)
            board[m] = EMPTY
            val net = myGain - oppReply * 0.9
            if (net > bestNet) {
                bestNet = net
                bestMove = m
            }
        }
        return bestMove
    }

    /** 指定玩家在所有候选点里能取得的最高单点棋型分（用于困难难度的一步预判） */
    private fun bestPointScore(board: IntArray, player: Int): Double {
        var best = 0.0
        for (c in candidates(board)) {
            val s = evaluatePoint(board, c, player)
            if (s > best) best = s
        }
        return best
    }

    /** 候选点：所有棋子的 2 格邻域内的空点；空棋盘时取天元。 */
    private fun candidates(board: IntArray): IntArray {
        var hasStone = false
        for (v in board) if (v != EMPTY) { hasStone = true; break }
        if (!hasStone) return intArrayOf((SIZE / 2) * SIZE + SIZE / 2)

        val marked = BooleanArray(SIZE * SIZE)
        val list = ArrayList<Int>()
        for (i in 0 until SIZE * SIZE) {
            if (board[i] == EMPTY) continue
            val r = i / SIZE
            val c = i % SIZE
            for (dr in -2..2) {
                for (dc in -2..2) {
                    val nr = r + dr
                    val nc = c + dc
                    if (nr !in 0 until SIZE || nc !in 0 until SIZE) continue
                    val j = nr * SIZE + nc
                    if (board[j] == EMPTY && !marked[j]) {
                        marked[j] = true
                        list.add(j)
                    }
                }
            }
        }
        return list.toIntArray()
    }

    /** 在空点 [idx] 落 [player] 子后，能否形成 >=5 连珠 */
    private fun makesFive(board: IntArray, idx: Int, player: Int): Boolean {
        val r = idx / SIZE
        val c = idx % SIZE
        for (d in 0 until 4) {
            var count = 1
            var rr = r + DR[d]
            var cc = c + DC[d]
            while (rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == player) {
                count++
                rr += DR[d]
                cc += DC[d]
            }
            rr = r - DR[d]
            cc = c - DC[d]
            while (rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == player) {
                count++
                rr -= DR[d]
                cc -= DC[d]
            }
            if (count >= 5) return true
        }
        return false
    }

    /** 在空点 [idx] 落 [player] 子后的棋型总分（四个方向累加，天然反映双三/双四威胁）。 */
    private fun evaluatePoint(board: IntArray, idx: Int, player: Int): Double {
        val r = idx / SIZE
        val c = idx % SIZE
        var total = 0.0
        for (d in 0 until 4) {
            var count = 1

            var rr = r + DR[d]
            var cc = c + DC[d]
            while (rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == player) {
                count++
                rr += DR[d]
                cc += DC[d]
            }
            val openForward = rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == EMPTY

            rr = r - DR[d]
            cc = c - DC[d]
            while (rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == player) {
                count++
                rr -= DR[d]
                cc -= DC[d]
            }
            val openBackward = rr in 0 until SIZE && cc in 0 until SIZE && board[rr * SIZE + cc] == EMPTY

            val openEnds = (if (openForward) 1 else 0) + (if (openBackward) 1 else 0)
            total += lineScore(count, openEnds)
        }
        return total
    }

    /** 棋型分值表：连子数 + 两端开放数 -> 分数 */
    private fun lineScore(count: Int, openEnds: Int): Double = when {
        count >= 5 -> 10_000_000.0
        count == 4 -> if (openEnds == 2) 500_000.0 else if (openEnds == 1) 50_000.0 else 0.0
        count == 3 -> if (openEnds == 2) 20_000.0 else if (openEnds == 1) 2_000.0 else 0.0
        count == 2 -> if (openEnds == 2) 1_000.0 else if (openEnds == 1) 200.0 else 0.0
        count == 1 -> if (openEnds == 2) 100.0 else if (openEnds == 1) 20.0 else 0.0
        else -> 0.0
    }
}
