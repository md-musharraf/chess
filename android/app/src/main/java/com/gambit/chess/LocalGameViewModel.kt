package com.gambit.chess

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gambit.chess.core.BLACK
import com.gambit.chess.core.Engine
import com.gambit.chess.core.Game
import com.gambit.chess.core.Level
import com.gambit.chess.core.Outcome
import com.gambit.chess.core.WHITE
import com.gambit.chess.core.uci
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The computer never replies faster than this, so its move reads as a move rather than a flicker. */
private const val MIN_THINK_MS = 450L

/** Games on this device: against the computer, or two people passing the phone. */
class LocalGameViewModel(app: Application) : AndroidViewModel(app) {
    private val services = app as GambitApp
    private var game = Game()
    var setup = SavedGame(vsComputer = true, level = Level.CASUAL, userColor = WHITE, moves = emptyList())
        private set
    private var viewPly: Int? = null
    private var hint = 0
    private var flipped = false
    private var thinking = false
    private var hinting = false
    private var round = 0
    private var aiEngine: Engine? = null
    private var hintEngine: Engine? = null
    private var aiJob: Job? = null
    private var hintJob: Job? = null

    private val state = MutableStateFlow<GameUi?>(null)
    val ui: StateFlow<GameUi?> = state

    fun start(vsComputer: Boolean, level: Level, color: Int) {
        val userColor = if (color == 0) listOf(WHITE, BLACK).random() else color
        load(SavedGame(vsComputer, level, userColor, emptyList()))
    }

    fun resume(): Boolean {
        load(services.loadGame() ?: return false)
        return true
    }

    fun rematch() = load(setup.copy(moves = emptyList()))

    private fun load(saved: SavedGame) {
        cancelThinking()
        setup = saved
        game = Game().apply { saved.moves.forEach { playUci(it) } }
        viewPly = null
        hint = 0
        round++
        flipped = saved.vsComputer && saved.userColor == BLACK
        publish()
        persist()
        computerTurn()
    }

    private fun userCanMove() =
        game.outcome == null && !thinking && (!setup.vsComputer || game.position.side == setup.userColor)

    fun play(m: Int) {
        if (userCanMove() && m in game.legalMoves()) apply(m)
    }

    private fun apply(m: Int) {
        hintEngine?.stop()
        hintJob?.cancel()
        hinting = false
        game.play(m)
        hint = 0
        viewPly = null
        publish()
        persist()
        computerTurn()
    }

    private fun computerTurn() {
        if (!setup.vsComputer || game.outcome != null || game.position.side == setup.userColor) return
        thinking = true
        publish()
        val position = game.position.copy()
        val engine = Engine().also { aiEngine = it }
        aiJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val m = withContext(Dispatchers.Default) { engine.think(position, setup.level) }
            delay(MIN_THINK_MS - (SystemClock.elapsedRealtime() - started))
            thinking = false
            if (m != 0) apply(m) else publish()
        }
    }

    private fun cancelThinking() {
        aiEngine?.stop()
        hintEngine?.stop()
        aiJob?.cancel()
        hintJob?.cancel()
        thinking = false
        hinting = false
    }

    val canUndo: Boolean
        get() = if (setup.vsComputer) game.moves.size >= (if (setup.userColor == WHITE) 1 else 2) else game.moves.isNotEmpty()

    fun undo() {
        if (!canUndo) return
        cancelThinking()
        game.undo()
        if (setup.vsComputer) while (game.moves.isNotEmpty() && game.position.side != setup.userColor) game.undo()
        hint = 0
        viewPly = null
        publish()
        persist()
        computerTurn()
    }

    fun hint() {
        if (!userCanMove() || hinting) return
        hinting = true
        publish()
        val position = game.position.copy()
        val engine = Engine().also { hintEngine = it }
        hintJob = viewModelScope.launch {
            hint = withContext(Dispatchers.Default) { engine.think(position, Level.ADVANCED) }
            hinting = false
            viewPly = null
            publish()
        }
    }

    fun resign() {
        if (game.outcome != null) return
        cancelThinking()
        val loser = if (setup.vsComputer) setup.userColor else game.position.side
        game.end(Outcome(-loser, "resignation"))
        publish()
        persist()
    }

    fun flip() {
        flipped = !flipped
        publish()
    }

    fun browse(ply: Int) {
        viewPly = if (ply >= game.moves.size) null else ply.coerceAtLeast(0)
        publish()
    }

    private fun persist() =
        services.saveGame(if (game.outcome == null) setup.copy(moves = game.moves.map(::uci)) else null)

    private fun publish() {
        val bottomColor = if (flipped) BLACK else WHITE
        val turn = game.position.side
        val you = services.settings.value.name.ifBlank { "You" }
        fun player(color: Int) = PlayerUi(
            name = when {
                !setup.vsComputer -> if (color == WHITE) "White" else "Black"
                color == setup.userColor -> you
                else -> "Computer"
            },
            detail = when {
                setup.vsComputer && color != setup.userColor -> setup.level.label
                hinting && color == turn -> "finding a hint…"
                else -> ""
            },
            color = color,
            active = game.outcome == null && turn == color,
            thinking = thinking && turn == color,
        )
        state.value = GameUi(
            key = "local-$round",
            view = BoardView.of(game, viewPly, userCanMove()),
            flipped = flipped,
            top = player(-bottomColor),
            bottom = player(bottomColor),
            outcome = game.outcome,
            hint = hint,
            you = if (setup.vsComputer) setup.userColor else 0,
        )
    }

    override fun onCleared() = cancelThinking()
}
