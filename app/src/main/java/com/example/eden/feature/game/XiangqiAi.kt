package com.example.eden.feature.game

import kotlin.math.abs

/**
 * 象棋规则与 AI。
 *
 * 棋盘表示见 [XiangqiBoardView]：长度 90 的 [IntArray]，index = row * 9 + col，
 * 正数为红方、负数为黑方，绝对值为棋子类型。
 *
 * AI 采用「子力价值 + 位置分」的评估函数配合 alpha-beta 剪枝的负极大值搜索，
 * 三档难度对应搜索深度 1 / 2 / 3 层。
 */
object XiangqiAi {

    private const val COLS = XiangqiBoardView.COLS
    private const val ROWS = XiangqiBoardView.ROWS
    private const val N = COLS * ROWS

    private const val KING = XiangqiBoardView.KING
    private const val ADVISOR = XiangqiBoardView.ADVISOR
    private const val ELEPHANT = XiangqiBoardView.ELEPHANT
    private const val HORSE = XiangqiBoardView.HORSE
    private const val ROOK = XiangqiBoardView.ROOK
    private const val CANNON = XiangqiBoardView.CANNON
    private const val PAWN = XiangqiBoardView.PAWN
    private const val EMPTY = XiangqiBoardView.EMPTY

    private const val RED = XiangqiBoardView.RED
    private const val BLACK = XiangqiBoardView.BLACK

    private const val MATE = 1_000_000

    /** 四个直线方向：上、下、左、右 */
    private val DR = intArrayOf(-1, 1, 0, 0)
    private val DC = intArrayOf(0, 0, -1, 1)

    /** 马的八个「日」字落点 */
    private val HORSE_MOVES = arrayOf(
        intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(2, -1), intArrayOf(2, 1),
        intArrayOf(-1, -2), intArrayOf(1, -2), intArrayOf(-1, 2), intArrayOf(1, 2)
    )

    enum class Level { EASY, MEDIUM, HARD }

    // ==================== 棋盘初始化 / 编码 ====================

    /** 标准开局：红方在下（row 7-9），黑方在上（row 0-2），红先手。 */
    fun initialBoard(): IntArray {
        val b = IntArray(N)
        val back = intArrayOf(ROOK, HORSE, ELEPHANT, ADVISOR, KING, ADVISOR, ELEPHANT, HORSE, ROOK)
        // 黑方
        for (c in 0 until COLS) b[0 * COLS + c] = -back[c]
        b[2 * COLS + 1] = -CANNON
        b[2 * COLS + 7] = -CANNON
        for (c in intArrayOf(0, 2, 4, 6, 8)) b[3 * COLS + c] = -PAWN
        // 红方
        for (c in 0 until COLS) b[9 * COLS + c] = back[c]
        b[7 * COLS + 1] = CANNON
        b[7 * COLS + 7] = CANNON
        for (c in intArrayOf(0, 2, 4, 6, 8)) b[6 * COLS + c] = PAWN
        return b
    }

    fun encode(from: Int, to: Int): Int = (from shl 7) or to
    fun fromOf(move: Int): Int = move shr 7
    fun toOf(move: Int): Int = move and 0x7F

    fun typeOf(piece: Int): Int = abs(piece)
    fun sideOf(piece: Int): Int = if (piece > 0) RED else BLACK

    // ==================== 对外查询 ====================

    /** 指定位置棋子的合法落点（用于 UI 高亮）。 */
    fun legalMovesFrom(board: IntArray, from: Int): IntArray {
        if (from !in 0 until N) return IntArray(0)
        val piece = board[from]
        if (piece == EMPTY) return IntArray(0)
        val side = sideOf(piece)
        val pseudo = pseudoMovesFrom(board, from)
        val result = ArrayList<Int>(pseudo.size)
        for (m in pseudo) {
            val to = toOf(m)
            val captured = board[to]
            board[to] = piece
            board[from] = EMPTY
            val ok = !isInCheck(board, side)
            board[from] = piece
            board[to] = captured
            if (ok) result.add(to)
        }
        return result.toIntArray()
    }

    /** 该方是否存在合法走法（用于绝杀 / 困毙判定）。 */
    fun hasAnyLegalMove(board: IntArray, side: Int): Boolean {
        val pseudo = pseudoMoves(board, side)
        for (m in pseudo) {
            val from = fromOf(m)
            val to = toOf(m)
            val moving = board[from]
            val captured = board[to]
            board[to] = moving
            board[from] = EMPTY
            val ok = !isInCheck(board, side)
            board[from] = moving
            board[to] = captured
            if (ok) return true
        }
        return false
    }

    fun isInCheck(board: IntArray, side: Int): Boolean {
        val k = findKing(board, side)
        return k >= 0 && isAttacked(board, k, -side)
    }

    fun findKing(board: IntArray, side: Int): Int {
        for (i in 0 until N) {
            val p = board[i]
            if (p != EMPTY && sideOf(p) == side && typeOf(p) == KING) return i
        }
        return -1
    }

    // ==================== 走法生成 ====================

    private fun inBoard(r: Int, c: Int) = r in 0 until ROWS && c in 0 until COLS

    private fun inPalace(r: Int, c: Int, side: Int): Boolean {
        if (c !in 3..5) return false
        return if (side == RED) r in 7..9 else r in 0..2
    }

    /** 生成某一方所有「伪合法」走法（未过滤自将），编码为 [encode]。 */
    private fun pseudoMoves(board: IntArray, side: Int): IntArray {
        val list = ArrayList<Int>(64)
        for (i in 0 until N) {
            val p = board[i]
            if (p == EMPTY || sideOf(p) != side) continue
            pseudoMovesFrom(board, i, list)
        }
        return list.toIntArray()
    }

    /** 生成某位置棋子的伪合法走法（返回编码走法）。 */
    private fun pseudoMovesFrom(board: IntArray, from: Int): IntArray {
        val list = ArrayList<Int>(16)
        pseudoMovesFrom(board, from, list)
        return list.toIntArray()
    }

    private fun pseudoMovesFrom(board: IntArray, from: Int, list: MutableList<Int>) {
        val p = board[from]
        if (p == EMPTY) return
        val side = sideOf(p)
        val r = from / COLS
        val c = from % COLS
        when (typeOf(p)) {
            ROOK -> genRook(board, from, r, c, side, list)
            CANNON -> genCannon(board, from, r, c, side, list)
            HORSE -> genHorse(board, from, r, c, side, list)
            ELEPHANT -> genElephant(board, from, r, c, side, list)
            ADVISOR -> genAdvisor(board, from, r, c, side, list)
            KING -> genKing(board, from, r, c, side, list)
            PAWN -> genPawn(board, from, r, c, side, list)
        }
    }

    private fun addMove(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        if (!inBoard(r, c)) return
        val target = board[r * COLS + c]
        if (target != EMPTY && sideOf(target) == side) return
        list.add(encode(from, r * COLS + c))
    }

    private fun genRook(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        for (d in 0 until 4) {
            var rr = r + DR[d]
            var cc = c + DC[d]
            while (inBoard(rr, cc)) {
                val t = board[rr * COLS + cc]
                if (t == EMPTY) {
                    list.add(encode(from, rr * COLS + cc))
                } else {
                    if (sideOf(t) != side) list.add(encode(from, rr * COLS + cc))
                    break
                }
                rr += DR[d]
                cc += DC[d]
            }
        }
    }

    private fun genCannon(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        for (d in 0 until 4) {
            var rr = r + DR[d]
            var cc = c + DC[d]
            // 未翻山：只能走空位
            while (inBoard(rr, cc) && board[rr * COLS + cc] == EMPTY) {
                list.add(encode(from, rr * COLS + cc))
                rr += DR[d]
                cc += DC[d]
            }
            // 翻过炮架后，第一个遇到的子若是敌子则可吃
            if (inBoard(rr, cc)) {
                rr += DR[d]
                cc += DC[d]
                while (inBoard(rr, cc)) {
                    val t = board[rr * COLS + cc]
                    if (t != EMPTY) {
                        if (sideOf(t) != side) list.add(encode(from, rr * COLS + cc))
                        break
                    }
                    rr += DR[d]
                    cc += DC[d]
                }
            }
        }
    }

    private fun genHorse(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        for (d in HORSE_MOVES) {
            val nr = r + d[0]
            val nc = c + d[1]
            if (!inBoard(nr, nc)) continue
            // 蹩马腿：沿「日」的长边方向相邻点不能有子
            val legR = if (abs(d[0]) == 2) r + d[0] / 2 else r
            val legC = if (abs(d[1]) == 2) c + d[1] / 2 else c
            if (board[legR * COLS + legC] != EMPTY) continue
            addMove(board, from, nr, nc, side, list)
        }
    }

    private fun genElephant(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        for (dr in intArrayOf(-2, 2)) {
            for (dc in intArrayOf(-2, 2)) {
                val nr = r + dr
                val nc = c + dc
                if (!inBoard(nr, nc)) continue
                // 象不过河
                if (side == RED && nr < 5) continue
                if (side == BLACK && nr > 4) continue
                // 塞象眼
                if (board[(r + dr / 2) * COLS + (c + dc / 2)] != EMPTY) continue
                addMove(board, from, nr, nc, side, list)
            }
        }
    }

    private fun genAdvisor(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        for (dr in intArrayOf(-1, 1)) {
            for (dc in intArrayOf(-1, 1)) {
                val nr = r + dr
                val nc = c + dc
                if (!inPalace(nr, nc, side)) continue
                addMove(board, from, nr, nc, side, list)
            }
        }
    }

    private fun genKing(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        for (d in 0 until 4) {
            val nr = r + DR[d]
            val nc = c + DC[d]
            if (!inPalace(nr, nc, side)) continue
            addMove(board, from, nr, nc, side, list)
        }
    }

    private fun genPawn(board: IntArray, from: Int, r: Int, c: Int, side: Int, list: MutableList<Int>) {
        if (side == RED) {
            // 红兵向上（row 减小）；过河后（row <= 4）可左右
            addMove(board, from, r - 1, c, side, list)
            if (r <= 4) {
                addMove(board, from, r, c - 1, side, list)
                addMove(board, from, r, c + 1, side, list)
            }
        } else {
            addMove(board, from, r + 1, c, side, list)
            if (r >= 5) {
                addMove(board, from, r, c - 1, side, list)
                addMove(board, from, r, c + 1, side, list)
            }
        }
    }

    // ==================== 被攻击判定 ====================

    /** 判断坐标 idx 是否被 bySide 方的任意棋子攻击（含将帅照面）。 */
    private fun isAttacked(board: IntArray, idx: Int, bySide: Int): Boolean {
        val r = idx / COLS
        val c = idx % COLS

        // 车 / 炮 / 将帅照面
        for (d in 0 until 4) {
            var rr = r + DR[d]
            var cc = c + DC[d]
            var screen = 0
            while (inBoard(rr, cc)) {
                val p = board[rr * COLS + cc]
                if (p != EMPTY) {
                    if (screen == 0) {
                        if (sideOf(p) == bySide) {
                            val t = typeOf(p)
                            if (t == ROOK) return true
                            // 将帅照面：同列、中间无子
                            if (t == KING && DC[d] == 0) return true
                        }
                        screen = 1
                    } else {
                        if (sideOf(p) == bySide && typeOf(p) == CANNON) return true
                        break
                    }
                }
                rr += DR[d]
                cc += DC[d]
            }
        }

        // 马
        for (d in HORSE_MOVES) {
            val nr = r + d[0]
            val nc = c + d[1]
            if (!inBoard(nr, nc)) continue
            val p = board[nr * COLS + nc]
            if (p == EMPTY || sideOf(p) != bySide || typeOf(p) != HORSE) continue
            val dr = r - nr
            val dc = c - nc
            val legR = if (abs(dr) == 2) nr + dr / 2 else nr
            val legC = if (abs(dc) == 2) nc + dc / 2 else nc
            if (board[legR * COLS + legC] == EMPTY) return true
        }

        // 兵 / 卒
        if (bySide == RED) {
            if (inBoard(r + 1, c) && board[(r + 1) * COLS + c] == PAWN) return true
            if (r <= 4) {
                for (dc in intArrayOf(-1, 1)) {
                    if (inBoard(r, c + dc) && board[r * COLS + (c + dc)] == PAWN) return true
                }
            }
        } else {
            if (inBoard(r - 1, c) && board[(r - 1) * COLS + c] == -PAWN) return true
            if (r >= 5) {
                for (dc in intArrayOf(-1, 1)) {
                    if (inBoard(r, c + dc) && board[r * COLS + (c + dc)] == -PAWN) return true
                }
            }
        }
        return false
    }

    // ==================== AI 搜索 ====================

    /**
     * 返回 AI 的最佳走法（编码值），无合法走法时返回 -1。
     * [level] 决定搜索深度：简单 1 层（并带随机）、中等 2 层、困难 3 层。
     */
    fun bestMove(board: IntArray, ai: Int, level: Level): Int {
        val b = board.copyOf()
        val rootMoves = legalMoves(b, ai)
        if (rootMoves.isEmpty()) return -1

        val depth = when (level) {
            Level.EASY -> 1
            Level.MEDIUM -> 2
            Level.HARD -> 3
        }

        orderMoves(b, rootMoves)
        val scored = ArrayList<Pair<Int, Int>>(rootMoves.size)
        for (m in rootMoves) {
            val captured = makeMove(b, m)
            val score = -negamax(b, -ai, depth - 1, -Int.MAX_VALUE, Int.MAX_VALUE)
            unmakeMove(b, m, captured)
            scored.add(m to score)
        }

        return when (level) {
            // 简单：在评分最高的几个走法里随机，偶尔会看漏，方便玩家取胜
            Level.EASY -> {
                scored.sortByDescending { it.second }
                val pool = scored.take(minOf(3, scored.size))
                pool.random().first
            }
            else -> scored.maxByOrNull { it.second }!!.first
        }
    }

    private fun negamax(board: IntArray, side: Int, depth: Int, alphaIn: Int, betaIn: Int): Int {
        val moves = legalMoves(board, side)
        if (moves.isEmpty()) return -MATE
        if (depth == 0) return evaluate(board, side)

        orderMoves(board, moves)
        var alpha = alphaIn
        for (m in moves) {
            val captured = makeMove(board, m)
            val score = -negamax(board, -side, depth - 1, -betaIn, -alpha)
            unmakeMove(board, m, captured)
            if (score > alpha) alpha = score
            if (alpha >= betaIn) break
        }
        return alpha
    }

    private fun legalMoves(board: IntArray, side: Int): IntArray {
        val pseudo = pseudoMoves(board, side)
        val result = ArrayList<Int>(pseudo.size)
        for (m in pseudo) {
            val from = fromOf(m)
            val to = toOf(m)
            val moving = board[from]
            val captured = board[to]
            board[to] = moving
            board[from] = EMPTY
            val ok = !isInCheck(board, side)
            board[from] = moving
            board[to] = captured
            if (ok) result.add(m)
        }
        return result.toIntArray()
    }

    /** 落子并返回被吃的子（[EMPTY] 表示未吃子），供 [unmakeMove] 还原。 */
    private fun makeMove(board: IntArray, move: Int): Int {
        val from = fromOf(move)
        val to = toOf(move)
        val captured = board[to]
        board[to] = board[from]
        board[from] = EMPTY
        return captured
    }

    private fun unmakeMove(board: IntArray, move: Int, captured: Int) {
        val from = fromOf(move)
        val to = toOf(move)
        board[from] = board[to]
        board[to] = captured
    }

    private fun orderMoves(board: IntArray, moves: IntArray) {
        if (moves.size <= 1) return
        val sorted = moves.toMutableList()
        sorted.sortByDescending { m -> moveScore(board, m) }
        for (i in sorted.indices) moves[i] = sorted[i]
    }

    /** MVV 排序权重：吃子价值越高越优先搜索，提升剪枝效率 */
    private fun moveScore(board: IntArray, move: Int): Int {
        val target = board[toOf(move)]
        return if (target == EMPTY) 0 else 100 + baseValue(typeOf(target))
    }

    // ==================== 局面评估 ====================

    private fun evaluate(board: IntArray, side: Int): Int {
        var score = 0
        for (i in 0 until N) {
            val p = board[i]
            if (p == EMPTY) continue
            val t = typeOf(p)
            var v = baseValue(t)
            when (t) {
                PAWN -> v += pawnBonus(p, i)
                HORSE -> v += colBonus(i % COLS) * 2
                CANNON -> v += colBonus(i % COLS)
            }
            score += if (p > 0) v else -v
        }
        return if (side == RED) score else -score
    }

    /** 子力价值 */
    private fun baseValue(t: Int): Int = when (t) {
        KING -> 10000
        ROOK -> 900
        CANNON -> 450
        HORSE -> 400
        ADVISOR -> 200
        ELEPHANT -> 200
        PAWN -> 100
        else -> 0
    }

    /** 兵/卒前进与过河奖励 */
    private fun pawnBonus(piece: Int, idx: Int): Int {
        val r = idx / COLS
        return if (piece > 0) {
            val crossed = r <= 4
            (6 - r).coerceAtLeast(0) * 4 + if (crossed) 10 else 0
        } else {
            val crossed = r >= 5
            (r - 3).coerceAtLeast(0) * 4 + if (crossed) 10 else 0
        }
    }

    /** 中列奖励：越靠近中路越高，鼓励马炮出动 */
    private fun colBonus(col: Int): Int = (2 - abs(col - 4)) * 3
}
