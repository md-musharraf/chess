package com.gambit.chess.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

// Pieces are signed ints on a 64-square board (a1 = 0, h8 = 63): white positive, black negative.
const val WHITE = 1
const val BLACK = -1
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

// A move is one Int: from | to << 6 | promotion piece type << 12 | flags << 15.
const val CAPTURE = 1
const val EN_PASSANT = 2
const val CASTLE = 4
const val DOUBLE_PUSH = 8

fun move(from: Int, to: Int, promo: Int = 0, flags: Int = 0) = from or (to shl 6) or (promo shl 12) or (flags shl 15)
fun moveFrom(m: Int) = m and 63
fun moveTo(m: Int) = (m shr 6) and 63
fun movePromo(m: Int) = (m shr 12) and 7
fun moveFlags(m: Int) = m shr 15
fun isCapture(m: Int) = moveFlags(m) and CAPTURE != 0

fun fileOf(sq: Int) = sq and 7
fun rankOf(sq: Int) = sq shr 3
fun squareName(sq: Int) = "${'a' + fileOf(sq)}${rankOf(sq) + 1}"
fun uci(m: Int) = squareName(moveFrom(m)) + squareName(moveTo(m)) + if (movePromo(m) != 0) "${"nbrq"[movePromo(m) - KNIGHT]}" else ""

private fun targets(sq: Int, deltas: List<Pair<Int, Int>>) = deltas.mapNotNull { (df, dr) ->
    val f = fileOf(sq) + df
    val r = rankOf(sq) + dr
    if (f in 0..7 && r in 0..7) r * 8 + f else null
}.toIntArray()

private val KNIGHT_DELTAS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
private val KING_DELTAS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
/** Directions 0..3 are rook moves, 4..7 bishop moves. */
private val DIRECTIONS = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, -1 to 1, 1 to -1, -1 to -1)

internal val knightTargets = Array(64) { targets(it, KNIGHT_DELTAS) }
internal val kingTargets = Array(64) { targets(it, KING_DELTAS) }
/** rays[direction][square] = squares walked from square to the board edge, nearest first. */
internal val rays = Array(8) { d ->
    val (df, dr) = DIRECTIONS[d]
    Array(64) { sq -> targets(sq, (1..7).map { df * it to dr * it }) }
}

// Castling rights bits: 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O.
// Moving from or capturing on one of these squares clears the matching rights.
private val castleMask = IntArray(64) { 15 }.apply {
    this[0] = 13; this[4] = 12; this[7] = 14; this[56] = 7; this[60] = 3; this[63] = 11
}

private val zobristRng = Random(0x6A3B1C5)
private val zPiece = Array(13) { LongArray(64) { zobristRng.nextLong() } }
private val zCastle = LongArray(16) { zobristRng.nextLong() }
private val zEp = LongArray(8) { zobristRng.nextLong() }
private val zSide = zobristRng.nextLong()

/** Mutable board with make/unmake, used both by the game and by the search. */
class Position private constructor() {
    val board = IntArray(64)
    var side = WHITE; private set
    var castling = 0; private set
    var ep = -1; private set
    var halfmove = 0; private set
    var hash = 0L; private set
    var ply = 0; private set
    private val kings = IntArray(2)

    // Undo stack, grown on demand. Hashes double as the repetition history.
    private var undoMove = IntArray(128)
    private var undoCaptured = IntArray(128)
    private var undoState = IntArray(128)
    private var undoHash = LongArray(128)

    fun kingSquare(color: Int) = kings[if (color == WHITE) 0 else 1]
    fun inCheck() = isAttacked(kingSquare(side), -side)
    /** After [make]: true when the move left the mover's own king attacked, i.e. it was illegal. */
    fun leftKingInCheck() = isAttacked(kingSquare(-side), side)

    fun copy(): Position = Position().also { c ->
        board.copyInto(c.board)
        c.side = side; c.castling = castling; c.ep = ep; c.halfmove = halfmove; c.hash = hash; c.ply = ply
        kings.copyInto(c.kings)
        c.undoMove = undoMove.copyOf(); c.undoCaptured = undoCaptured.copyOf()
        c.undoState = undoState.copyOf(); c.undoHash = undoHash.copyOf()
    }

    fun make(m: Int) {
        if (ply == undoMove.size) {
            val size = ply * 2
            undoMove = undoMove.copyOf(size); undoCaptured = undoCaptured.copyOf(size)
            undoState = undoState.copyOf(size); undoHash = undoHash.copyOf(size)
        }
        val from = moveFrom(m)
        val to = moveTo(m)
        val flags = moveFlags(m)
        val piece = board[from]
        val capSq = if (flags and EN_PASSANT != 0) to - 8 * side else to
        val captured = board[capSq]
        undoMove[ply] = m
        undoCaptured[ply] = captured
        undoState[ply] = castling or ((ep + 1) shl 4) or (halfmove shl 11)
        undoHash[ply] = hash
        ply++

        var h = hash xor zPiece[piece + 6][from]
        board[from] = 0
        if (captured != 0) {
            h = h xor zPiece[captured + 6][capSq]
            board[capSq] = 0
        }
        val placed = if (movePromo(m) != 0) movePromo(m) * side else piece
        board[to] = placed
        h = h xor zPiece[placed + 6][to]
        if (flags and CASTLE != 0) {
            val rookFrom = if (to > from) from + 3 else from - 4
            val rookTo = if (to > from) from + 1 else from - 1
            val rook = board[rookFrom]
            board[rookTo] = rook
            board[rookFrom] = 0
            h = h xor zPiece[rook + 6][rookFrom] xor zPiece[rook + 6][rookTo]
        }
        if (abs(piece) == KING) kings[if (side == WHITE) 0 else 1] = to
        h = h xor zCastle[castling]
        castling = castling and castleMask[from] and castleMask[to]
        h = h xor zCastle[castling]
        if (ep >= 0) h = h xor zEp[fileOf(ep)]
        ep = if (flags and DOUBLE_PUSH != 0) (from + to) / 2 else -1
        if (ep >= 0) h = h xor zEp[fileOf(ep)]
        halfmove = if (abs(piece) == PAWN || captured != 0) 0 else halfmove + 1
        side = -side
        hash = h xor zSide
    }

    fun unmake() {
        ply--
        val m = undoMove[ply]
        val from = moveFrom(m)
        val to = moveTo(m)
        val flags = moveFlags(m)
        side = -side
        val piece = if (movePromo(m) != 0) PAWN * side else board[to]
        board[from] = piece
        board[to] = 0
        val captured = undoCaptured[ply]
        if (captured != 0) board[if (flags and EN_PASSANT != 0) to - 8 * side else to] = captured
        if (flags and CASTLE != 0) {
            val rookFrom = if (to > from) from + 3 else from - 4
            val rookTo = if (to > from) from + 1 else from - 1
            board[rookFrom] = board[rookTo]
            board[rookTo] = 0
        }
        if (abs(piece) == KING) kings[if (side == WHITE) 0 else 1] = from
        val state = undoState[ply]
        castling = state and 15
        ep = ((state shr 4) and 127) - 1
        halfmove = state shr 11
        hash = undoHash[ply]
    }

    fun isAttacked(sq: Int, by: Int): Boolean {
        val f = fileOf(sq)
        if (by == WHITE) {
            if (f > 0 && sq >= 9 && board[sq - 9] == PAWN) return true
            if (f < 7 && sq >= 7 && board[sq - 7] == PAWN) return true
        } else {
            if (f < 7 && sq <= 54 && board[sq + 9] == -PAWN) return true
            if (f > 0 && sq <= 56 && board[sq + 7] == -PAWN) return true
        }
        for (t in knightTargets[sq]) if (board[t] == KNIGHT * by) return true
        for (t in kingTargets[sq]) if (board[t] == KING * by) return true
        for (d in 0..7) {
            val slider = (if (d < 4) ROOK else BISHOP) * by
            for (t in rays[d][sq]) {
                val p = board[t]
                if (p != 0) {
                    if (p == slider || p == QUEEN * by) return true
                    break
                }
            }
        }
        return false
    }

    /** Pseudo-legal moves into [out]; returns the count. Callers filter with [leftKingInCheck]. */
    fun generate(out: IntArray, capturesOnly: Boolean = false): Int {
        var n = 0
        val us = side
        for (from in 0..63) {
            val p = board[from] * us
            if (p <= 0) continue
            when (p) {
                PAWN -> {
                    val fwd = from + 8 * us
                    val promoting = rankOf(fwd) == if (us == WHITE) 7 else 0
                    if (board[fwd] == 0) {
                        if (promoting) n = addPromotions(out, n, from, fwd, 0)
                        else if (!capturesOnly) {
                            out[n++] = move(from, fwd)
                            val two = fwd + 8 * us
                            if (rankOf(from) == (if (us == WHITE) 1 else 6) && board[two] == 0) out[n++] = move(from, two, 0, DOUBLE_PUSH)
                        }
                    }
                    val f = fileOf(from)
                    if (f > 0) {
                        val to = fwd - 1
                        if (board[to] * us < 0) {
                            if (promoting) n = addPromotions(out, n, from, to, CAPTURE) else out[n++] = move(from, to, 0, CAPTURE)
                        } else if (to == ep) out[n++] = move(from, to, 0, CAPTURE or EN_PASSANT)
                    }
                    if (f < 7) {
                        val to = fwd + 1
                        if (board[to] * us < 0) {
                            if (promoting) n = addPromotions(out, n, from, to, CAPTURE) else out[n++] = move(from, to, 0, CAPTURE)
                        } else if (to == ep) out[n++] = move(from, to, 0, CAPTURE or EN_PASSANT)
                    }
                }
                KNIGHT -> for (to in knightTargets[from]) n = addStep(out, n, from, to, capturesOnly)
                KING -> {
                    for (to in kingTargets[from]) n = addStep(out, n, from, to, capturesOnly)
                    if (!capturesOnly) n = addCastles(out, n)
                }
                else -> {
                    val first = if (p == BISHOP) 4 else 0
                    val last = if (p == ROOK) 3 else 7
                    for (d in first..last) for (to in rays[d][from]) {
                        val t = board[to]
                        if (t == 0) {
                            if (!capturesOnly) out[n++] = move(from, to)
                        } else {
                            if (t * us < 0) out[n++] = move(from, to, 0, CAPTURE)
                            break
                        }
                    }
                }
            }
        }
        return n
    }

    private fun addStep(out: IntArray, n: Int, from: Int, to: Int, capturesOnly: Boolean): Int {
        val t = board[to]
        if (t == 0) {
            if (!capturesOnly) out[n] = move(from, to) else return n
        } else if (t * side < 0) out[n] = move(from, to, 0, CAPTURE) else return n
        return n + 1
    }

    private fun addPromotions(out: IntArray, n: Int, from: Int, to: Int, flags: Int): Int {
        for (promo in QUEEN downTo KNIGHT) out[n + QUEEN - promo] = move(from, to, promo, flags)
        return n + 4
    }

    private fun addCastles(out: IntArray, start: Int): Int {
        var n = start
        val them = -side
        val (rank, kingSide, queenSide) = if (side == WHITE) Triple(0, 1, 2) else Triple(56, 4, 8)
        val e = rank + 4
        if (castling and (kingSide or queenSide) == 0 || isAttacked(e, them)) return n
        if (castling and kingSide != 0 && board[e + 1] == 0 && board[e + 2] == 0 &&
            !isAttacked(e + 1, them) && !isAttacked(e + 2, them)
        ) out[n++] = move(e, e + 2, 0, CASTLE)
        if (castling and queenSide != 0 && board[e - 1] == 0 && board[e - 2] == 0 && board[e - 3] == 0 &&
            !isAttacked(e - 1, them) && !isAttacked(e - 2, them)
        ) out[n++] = move(e, e - 2, 0, CASTLE)
        return n
    }

    fun legalMoves(): IntArray {
        val buf = IntArray(256)
        val n = generate(buf)
        var k = 0
        for (i in 0 until n) {
            make(buf[i])
            if (!leftKingInCheck()) buf[k++] = buf[i]
            unmake()
        }
        return buf.copyOf(k)
    }

    /** How many earlier positions (same side to move, since the last irreversible move) equal this one. */
    fun repetitions(): Int {
        var count = 0
        var i = ply - 2
        val stop = max(0, ply - halfmove)
        while (i >= stop) {
            if (undoHash[i] == hash) count++
            i -= 2
        }
        return count
    }

    fun insufficientMaterial(): Boolean {
        var knights = 0
        var bishops = 0
        var bishopSquareColors = 0
        for (sq in 0..63) when (abs(board[sq])) {
            PAWN, ROOK, QUEEN -> return false
            KNIGHT -> knights++
            BISHOP -> {
                bishops++
                bishopSquareColors = bishopSquareColors or (1 shl ((fileOf(sq) + rankOf(sq)) and 1))
            }
        }
        return knights + bishops <= 1 || (knights == 0 && bishopSquareColors != 3)
    }

    fun toFen(fullmove: Int = 1): String = buildString {
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val pc = board[r * 8 + f]
                if (pc == 0) {
                    empty++
                } else {
                    if (empty > 0) {
                        append(empty)
                        empty = 0
                    }
                    val char = "pnbrqk"[abs(pc) - 1]
                    append(if (pc > 0) char.uppercaseChar() else char)
                }
            }
            if (empty > 0) append(empty)
            if (r > 0) append('/')
        }
        append(if (side == WHITE) " w " else " b ")
        val rights = StringBuilder()
        if (castling and 1 != 0) rights.append('K')
        if (castling and 2 != 0) rights.append('Q')
        if (castling and 4 != 0) rights.append('k')
        if (castling and 8 != 0) rights.append('q')
        append(if (rights.isEmpty()) "-" else rights.toString())
        append(' ')
        append(if (ep >= 0) squareName(ep) else "-")
        append(" $halfmove $fullmove")
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): Position {
            val p = Position()
            val parts = fen.trim().split(Regex("\\s+"))
            var rank = 7
            var file = 0
            for (c in parts[0]) when {
                c == '/' -> { rank--; file = 0 }
                c.isDigit() -> file += c - '0'
                else -> {
                    val type = "pnbrqk".indexOf(c.lowercaseChar()) + 1
                    require(type > 0) { "Bad FEN piece '$c'" }
                    p.board[rank * 8 + file++] = if (c.isUpperCase()) type else -type
                }
            }
            p.side = if (parts.getOrNull(1) == "b") BLACK else WHITE
            val rights = parts.getOrElse(2) { "-" }
            p.castling = "KQkq".withIndex().sumOf { (i, c) -> if (c in rights) 1 shl i else 0 }
            p.ep = parts.getOrElse(3) { "-" }.let { if (it == "-") -1 else (it[0] - 'a') + (it[1] - '1') * 8 }
            p.halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
            for (sq in 0..63) if (abs(p.board[sq]) == KING) p.kings[if (p.board[sq] > 0) 0 else 1] = sq
            var h = 0L
            for (sq in 0..63) if (p.board[sq] != 0) h = h xor zPiece[p.board[sq] + 6][sq]
            h = h xor zCastle[p.castling]
            if (p.ep >= 0) h = h xor zEp[fileOf(p.ep)]
            if (p.side == BLACK) h = h xor zSide
            p.hash = h
            return p
        }

        fun start() = fromFen(START_FEN)
    }
}
