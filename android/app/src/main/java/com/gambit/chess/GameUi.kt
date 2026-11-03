package com.gambit.chess

import androidx.compose.runtime.Immutable
import com.gambit.chess.core.BLACK
import com.gambit.chess.core.Game
import com.gambit.chess.core.KING
import com.gambit.chess.core.Outcome
import com.gambit.chess.core.WHITE

/** What the board shows: the live position or one being browsed in the move list. */
@Immutable
class BoardView(
    val board: IntArray,
    val lastMove: Int,
    val check: Int,
    /** Moves the user may play right now; empty when it's not their turn or they're browsing. */
    val legal: IntArray,
    val sans: List<String>,
    val ply: Int,
    val livePly: Int,
) {
    companion object {
        fun of(game: Game, viewPly: Int?, canMove: Boolean): BoardView {
            val live = game.moves.size
            val ply = viewPly?.coerceIn(0, live) ?: live
            val board = game.boardAt(ply)
            val toMove = if (ply % 2 == 0) WHITE else BLACK
            val checked = ply > 0 && game.sanMoves[ply - 1].last() in "+#"
            return BoardView(
                board = board,
                lastMove = if (ply > 0) game.moves[ply - 1] else 0,
                check = if (checked) board.indexOfFirst { it == KING * toMove } else -1,
                legal = if (canMove && ply == live) game.legalMoves() else IntArray(0),
                sans = game.sanMoves.toList(),
                ply = ply,
                livePly = live,
            )
        }
    }
}

@Immutable
data class PlayerUi(
    val name: String,
    val detail: String,
    val color: Int,
    val active: Boolean,
    val thinking: Boolean = false,
    /** Remaining clock time as of [clockAt] (elapsedRealtime); null for untimed games. */
    val clockMs: Long? = null,
    val clockAt: Long = 0,
    val offline: Boolean = false,
)

@Immutable
data class GameUi(
    /** Changes when a different game starts, so screens can reset per-game state. */
    val key: String,
    val view: BoardView,
    val flipped: Boolean,
    val top: PlayerUi,
    val bottom: PlayerUi,
    val outcome: Outcome? = null,
    val hint: Int = 0,
    val banner: String? = null,
    val drawOffered: Boolean = false,
    /** The colour this device plays, or 0 when both sides are human here (pass & play). */
    val you: Int = 0,
)
