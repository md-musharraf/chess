package com.gambit.chess.core

import kotlin.math.abs

/** [winner] is WHITE, BLACK, or 0 for a draw. */
data class Outcome(val winner: Int, val reason: String)

/** A game record on top of [Position]: history, SAN, a board per ply for browsing, and the result. */
class Game {
    val position = Position.start()
    private val played = ArrayList<Int>()
    private val sans = ArrayList<String>()
    private val boards = arrayListOf(position.board.copyOf())

    val moves: List<Int> get() = played
    val sanMoves: List<String> get() = sans
    var outcome: Outcome? = null
        private set

    fun boardAt(ply: Int): IntArray = boards[ply]
    fun legalMoves(): IntArray = if (outcome != null) IntArray(0) else position.legalMoves()

    fun play(m: Int) {
        sans += san(m)
        position.make(m)
        played += m
        boards += position.board.copyOf()
        outcome = ruleOutcome()
    }

    /** Plays a UCI move ("e7e8q") if legal; returns false otherwise. */
    fun playUci(text: String): Boolean {
        val m = position.legalMoves().firstOrNull { uci(it) == text } ?: return false
        play(m)
        return true
    }

    fun undo() {
        if (played.isEmpty()) return
        position.unmake()
        played.removeAt(played.lastIndex)
        sans.removeAt(sans.lastIndex)
        boards.removeAt(boards.lastIndex)
        outcome = null
    }

    /** Ends the game for a reason the board can't see: resignation, timeout, agreement. */
    fun end(outcome: Outcome) {
        this.outcome = outcome
    }

    private fun ruleOutcome(): Outcome? {
        val p = position
        if (p.legalMoves().isEmpty()) return if (p.inCheck()) Outcome(-p.side, "checkmate") else Outcome(0, "stalemate")
        if (p.insufficientMaterial()) return Outcome(0, "insufficient material")
        if (p.halfmove >= 100) return Outcome(0, "fifty-move rule")
        if (p.repetitions() >= 2) return Outcome(0, "threefold repetition")
        return null
    }

    /** Standard algebraic notation for a legal move in the current position. */
    fun san(m: Int): String {
        val p = position
        val from = moveFrom(m)
        val to = moveTo(m)
        val piece = abs(p.board[from])
        val sb = StringBuilder()
        if (moveFlags(m) and CASTLE != 0) {
            sb.append(if (to > from) "O-O" else "O-O-O")
        } else {
            if (piece == PAWN) {
                if (isCapture(m)) sb.append('a' + fileOf(from)).append('x')
            } else {
                sb.append("NBRQK"[piece - KNIGHT])
                val rivals = p.legalMoves().filter { moveTo(it) == to && moveFrom(it) != from && abs(p.board[moveFrom(it)]) == piece }
                if (rivals.isNotEmpty()) when {
                    rivals.none { fileOf(moveFrom(it)) == fileOf(from) } -> sb.append('a' + fileOf(from))
                    rivals.none { rankOf(moveFrom(it)) == rankOf(from) } -> sb.append('1' + rankOf(from))
                    else -> sb.append(squareName(from))
                }
                if (isCapture(m)) sb.append('x')
            }
            sb.append(squareName(to))
            if (movePromo(m) != 0) sb.append('=').append("NBRQ"[movePromo(m) - KNIGHT])
        }
        p.make(m)
        if (p.inCheck()) sb.append(if (p.legalMoves().isEmpty()) '#' else '+')
        p.unmake()
        return sb.toString()
    }
}
