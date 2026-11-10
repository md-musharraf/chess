package com.gambit.chess.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Strength levels. The weak ones score every root move with a full window at a fixed depth and
 * pick at random among moves within [margin] centipawns of the best, so they play plausible but
 * imperfect chess. The strong ones run a timed iterative-deepening search and play the best move.
 */
enum class Level(val label: String, val blurb: String, val depth: Int, val timeMs: Long, val margin: Int) {
    BEGINNER("Beginner", "Learning the moves", 1, 0, 300),
    CASUAL("Casual", "Plays for fun", 2, 0, 120),
    CLUB("Club", "Solid club player", 3, 0, 35),
    ADVANCED("Advanced", "Sharp and tactical", 64, 900, 0),
    MASTER("Master", "Thinks hard", 64, 2500, 0),
}

private const val INF = 1_000_000
private const val MATE = 100_000
private const val MAX_PLY = 96
private const val TT_BITS = 16
private const val TT_MASK = (1L shl TT_BITS) - 1

/** Not thread-safe: create one per search (they are cheap). [stop] may be called from any thread. */
class Engine {
    @Volatile private var stopped = false
    private var deadline = Long.MAX_VALUE
    private var nodes = 0L
    private var pos = Position.start()
    private val moveBufs = Array(MAX_PLY) { IntArray(256) }
    private val scoreBufs = Array(MAX_PLY) { IntArray(256) }
    // Best move per position, used only for move ordering (no score cutoffs, so no bound bugs).
    private val ttKey = LongArray(1 shl TT_BITS)
    private val ttMove = IntArray(1 shl TT_BITS)

    fun stop() {
        stopped = true
    }

    /** Best move for the side to move in [position] (left untouched), or 0 if there is none. */
    fun think(position: Position, level: Level, random: Random = Random.Default): Int {
        pos = position.copy()
        stopped = false
        nodes = 0
        val root = pos.legalMoves()
        if (root.size <= 1) return root.firstOrNull() ?: 0
        return if (level.margin > 0) pickWithin(root, level, random) else deepen(root, level.depth, level.timeMs)
    }

    private fun pickWithin(root: IntArray, level: Level, random: Random): Int {
        deadline = Long.MAX_VALUE
        val scores = IntArray(root.size) { i ->
            pos.make(root[i])
            val s = -search(level.depth - 1, -INF, INF, 1)
            pos.unmake()
            s
        }
        if (stopped) return 0
        val best = scores.max()
        val threshold = best - level.margin
        var count = 0
        for (i in root.indices) if (scores[i] >= threshold) count++
        var pick = random.nextInt(count)
        for (i in root.indices) {
            if (scores[i] >= threshold && pick-- == 0) return root[i]
        }
        return root[0]
    }

    private fun deepen(root: IntArray, maxDepth: Int, timeMs: Long): Int {
        deadline = System.currentTimeMillis() + timeMs
        val scores = IntArray(root.size)
        scoreMoves(root, scores, root.size, 0)
        val order = root.indices.sortedByDescending { scores[it] }.map { root[it] }.toMutableList()
        var best = order[0]
        for (depth in 1..maxDepth) {
            var alpha = -INF
            var iterationBest = 0
            for (m in order) {
                pos.make(m)
                val s = -search(depth - 1, -INF, -alpha, 1)
                pos.unmake()
                if (stopped) break
                if (s > alpha) {
                    alpha = s
                    iterationBest = m
                }
            }
            // The previous best is searched first, so even a cut-short iteration's best is sound.
            if (iterationBest != 0) {
                best = iterationBest
                order.remove(best)
                order.add(0, best)
            }
            if (stopped || abs(alpha) > MATE - MAX_PLY) break
        }
        return best
    }

    private fun tick() {
        if ((++nodes and 2047L) == 0L && System.currentTimeMillis() > deadline) stopped = true
    }

    private fun search(depthIn: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        tick()
        if (stopped) return 0
        if (pos.halfmove >= 100 || pos.repetitions() > 0) return 0
        val inCheck = pos.inCheck()
        val depth = if (inCheck) depthIn + 1 else depthIn
        if (depth <= 0 || ply >= MAX_PLY - 8) return quiesce(alphaIn, beta, ply)
        var alpha = alphaIn
        val moves = moveBufs[ply]
        val scores = scoreBufs[ply]
        val n = pos.generate(moves)
        val slot = (pos.hash and TT_MASK).toInt()
        scoreMoves(moves, scores, n, if (ttKey[slot] == pos.hash) ttMove[slot] else 0)
        var best = -INF
        var bestMove = 0
        var legal = 0
        for (i in 0 until n) {
            val m = pickNext(moves, scores, i, n)
            pos.make(m)
            if (pos.leftKingInCheck()) {
                pos.unmake()
                continue
            }
            legal++
            val s = -search(depth - 1, -beta, -alpha, ply + 1)
            pos.unmake()
            if (stopped) return 0
            if (s > best) {
                best = s
                bestMove = m
                if (s > alpha) alpha = s
                if (alpha >= beta) break
            }
        }
        if (legal == 0) return if (inCheck) -MATE + ply else 0
        ttKey[slot] = pos.hash
        ttMove[slot] = bestMove
        return best
    }

    private fun quiesce(alphaIn: Int, beta: Int, ply: Int): Int {
        tick()
        if (stopped) return 0
        val stand = evaluate(pos)
        if (stand >= beta || ply >= MAX_PLY - 1) return stand
        var alpha = max(alphaIn, stand)
        val moves = moveBufs[ply]
        val scores = scoreBufs[ply]
        val n = pos.generate(moves, capturesOnly = true)
        scoreMoves(moves, scores, n, 0)
        for (i in 0 until n) {
            val m = pickNext(moves, scores, i, n)
            pos.make(m)
            if (pos.leftKingInCheck()) {
                pos.unmake()
                continue
            }
            val s = -quiesce(-beta, -alpha, ply + 1)
            pos.unmake()
            if (stopped) return 0
            if (s >= beta) return s
            if (s > alpha) alpha = s
        }
        return alpha
    }

    /** Hash move first, then captures by most valuable victim / least valuable attacker, then promotions. */
    private fun scoreMoves(moves: IntArray, scores: IntArray, n: Int, hashMove: Int) {
        for (i in 0 until n) {
            val m = moves[i]
            scores[i] = when {
                m == hashMove -> 1_000_000
                isCapture(m) -> {
                    val victim = abs(pos.board[moveTo(m)]).let { if (it == 0) PAWN else it }
                    100_000 + 10 * VALUE[victim] - VALUE[abs(pos.board[moveFrom(m)])] / 10
                }
                movePromo(m) != 0 -> 90_000 + VALUE[movePromo(m)]
                else -> 0
            }
        }
    }

    private fun pickNext(moves: IntArray, scores: IntArray, i: Int, n: Int): Int {
        var bestIdx = i
        for (j in i + 1 until n) if (scores[j] > scores[bestIdx]) bestIdx = j
        if (bestIdx != i) {
            val m = moves[i]; moves[i] = moves[bestIdx]; moves[bestIdx] = m
            val s = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = s
        }
        return moves[i]
    }
}

internal val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 20000)
private val PHASE = intArrayOf(0, 0, 1, 1, 2, 4, 0)

// Piece-square tables from the Simplified Evaluation Function (chessprogramming.org), written
// from white's side with rank 8 first; a black piece on square sq reads index sq directly.
private val PST = arrayOf(
    IntArray(64),
    intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0,
    ),
    intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30, -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50,
    ),
    intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10, -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20,
    ),
    intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0, 5, 5, 0, 0, 0,
    ),
    intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 5, 5, 5, 0, -10, -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5, -10, 5, 5, 5, 5, 5, 0, -10, -10, 0, 5, 0, 0, 0, 0, -10, -20, -10, -10, -5, -5, -10, -10, -20,
    ),
)
private val KING_MIDDLE = intArrayOf(
    -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
    -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20,
)
private val KING_END = intArrayOf(
    -50, -40, -30, -20, -20, -30, -40, -50, -30, -20, -10, 0, 0, -10, -20, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30,
    -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -30, 0, 0, 0, 0, -30, -30, -50, -30, -30, -30, -30, -30, -30, -50,
)

/** Static evaluation in centipawns from the side to move's point of view. */
internal fun evaluate(p: Position): Int {
    var mid = 0
    var end = 0
    var phase = 0
    var whiteBishops = 0
    var blackBishops = 0
    for (sq in 0..63) {
        val pc = p.board[sq]
        if (pc == 0) continue
        val type = abs(pc)
        val sign = if (pc > 0) 1 else -1
        val idx = if (pc > 0) sq xor 56 else sq
        if (type == KING) {
            mid += sign * KING_MIDDLE[idx]
            end += sign * KING_END[idx]
        } else {
            val v = sign * (VALUE[type] + PST[type][idx])
            mid += v
            end += v
        }
        phase += PHASE[type]
        if (type == BISHOP) if (pc > 0) whiteBishops++ else blackBishops++
    }
    phase = min(phase, 24)
    var score = (mid * phase + end * (24 - phase)) / 24
    if (whiteBishops >= 2) score += 30
    if (blackBishops >= 2) score -= 30
    // Mop-up: in a won ending, drive the lone king to the edge and bring our king closer.
    if (phase <= 6 && abs(score) > 300) {
        val strong = if (score > 0) WHITE else BLACK
        val weakKing = p.kingSquare(-strong)
        val ourKing = p.kingSquare(strong)
        val fromCenter = abs(2 * fileOf(weakKing) - 7) + abs(2 * rankOf(weakKing) - 7)
        val kingsApart = abs(fileOf(weakKing) - fileOf(ourKing)) + abs(rankOf(weakKing) - rankOf(ourKing))
        score += strong * (fromCenter * 5 + (14 - kingsApart) * 3)
    }
    return score * p.side
}

/** Static evaluation in centipawns from White's perspective (+ means White leads, - means Black leads). */
fun evaluateFromWhite(p: Position): Int = evaluate(p) * p.side

