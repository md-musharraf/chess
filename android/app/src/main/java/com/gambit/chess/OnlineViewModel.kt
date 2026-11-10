package com.gambit.chess

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gambit.chess.core.BLACK
import com.gambit.chess.core.Game
import com.gambit.chess.core.Outcome
import com.gambit.chess.core.WHITE
import com.gambit.chess.core.uci
import com.gambit.chess.net.ApiError
import com.gambit.chess.net.GameRecord
import com.gambit.chess.net.JSON
import com.gambit.chess.net.Profile
import com.gambit.chess.net.RoomInfo
import com.gambit.chess.net.ServerGame
import com.gambit.chess.net.ServerMsg
import com.gambit.chess.net.ServerStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import kotlin.random.Random

enum class Connection { OFFLINE, CONNECTING, ONLINE }

data class Lobby(
    val connection: Connection = Connection.OFFLINE,
    val me: Profile? = null,
    val seeking: String? = null,
    val room: String? = null,
    val roomTc: String? = null,
    val leaderboard: List<Profile> = emptyList(),
    val recent: List<GameRecord> = emptyList(),
    val stats: ServerStats? = null,
    val openRooms: List<RoomInfo> = emptyList(),
    val error: String? = null,
)

/** Online play: account, lobby socket, and a local mirror of the server's game. The server is authoritative. */
class OnlineViewModel(app: Application) : AndroidViewModel(app) {
    private val services = app as GambitApp
    private val api get() = services.api

    private val lobbyState = MutableStateFlow(Lobby())
    val lobby: StateFlow<Lobby> = lobbyState
    private val gameState = MutableStateFlow<GameUi?>(null)
    val game: StateFlow<GameUi?> = gameState

    private var socket: WebSocket? = null
    private var wanted = false
    private var attempts = 0
    private var connectJob: Job? = null
    private var retryJob: Job? = null

    private var server: ServerGame? = null
    private var serverAt = 0L
    private var chess = Game()
    private var viewPly: Int? = null
    private var flipped = false

    val timeControl: String? get() = server?.tc
    val gameInProgress: Boolean get() = server != null && server?.over == null

    fun start() {
        wanted = true
        if (socket == null && connectJob?.isActive != true) connect()
        refresh()
    }

    /** Leaves the lobby. A game in progress keeps its socket so the clock and presence stay honest. */
    fun stop() {
        if (gameInProgress) return
        wanted = false
        retryJob?.cancel()
        socket?.close(1000, null)
        socket = null
        lobbyState.update { it.copy(connection = Connection.OFFLINE, seeking = null, room = null) }
    }

    private fun connect() {
        connectJob = viewModelScope.launch {
            lobbyState.update { it.copy(connection = Connection.CONNECTING) }
            attempt { account() }
                .onSuccess { token -> socket = api.socket(token, Listener()) }
                .onFailure { e ->
                    lobbyState.update { it.copy(connection = Connection.OFFLINE, error = explain(e)) }
                    reconnectLater()
                }
        }
    }

    private fun reconnectLater() {
        if (!wanted) return
        val wait = 1_000L shl attempts.coerceAtMost(3)
        attempts++
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(wait)
            if (wanted && socket == null) connect()
        }
    }

    fun retryNow() {
        attempts = 0
        lobbyState.update { it.copy(error = null) }
        start()
    }

    private suspend fun account(): String {
        services.token?.let { token ->
            try {
                val me = api.me(token)
                lobbyState.update { it.copy(me = me) }
                return token
            } catch (e: ApiError) {
                if (e.status != 401) throw e // 401: the server forgot us (e.g. fresh database), sign up again
            }
        }
        val name = services.settings.value.name.ifBlank { "Guest" + Random.nextInt(1000, 10000) }
        val profile = api.register(name)
        services.token = profile.token
        if (services.settings.value.name.isBlank()) services.updateSettings { it.copy(name = name) }
        lobbyState.update { it.copy(me = profile) }
        return profile.token!!
    }

    private fun explain(e: Throwable) = when (e) {
        is ApiError -> e.message ?: "Server error"
        is IOException -> "Can't reach the server at ${services.settings.value.serverUrl}"
        is IllegalArgumentException -> "The server address in Settings isn't valid"
        else -> "Something went wrong: ${e.message}"
    }

    fun refresh() {
        viewModelScope.launch {
            val leaderboardDeferred = async { attempt { api.leaderboard() } }
            val statsDeferred = async { attempt { api.stats() } }
            val roomsDeferred = async { attempt { api.openRooms() } }
            val token = services.token
            val meDeferred = if (token != null) async { attempt { api.me(token) } } else null
            val gamesDeferred = if (token != null) async { attempt { api.myGames(token) } } else null

            leaderboardDeferred.await().onSuccess { list -> lobbyState.update { it.copy(leaderboard = list) } }
            statsDeferred.await().onSuccess { stats -> lobbyState.update { it.copy(stats = stats) } }
            roomsDeferred.await().onSuccess { rooms -> lobbyState.update { it.copy(openRooms = rooms) } }
            meDeferred?.await()?.onSuccess { me -> lobbyState.update { it.copy(me = me) } }
            gamesDeferred?.await()?.onSuccess { list -> lobbyState.update { it.copy(recent = list) } }
        }
    }

    fun rename(name: String) {
        val token = services.token ?: return
        viewModelScope.launch { attempt { api.rename(token, name) }.onSuccess { me -> lobbyState.update { it.copy(me = me) } } }
    }

    private inner class Listener : WebSocketListener() {
        private fun onMain(block: () -> Unit) {
            viewModelScope.launch { block() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = onMain {
            if (webSocket !== socket) return@onMain
            attempts = 0
            lobbyState.update { it.copy(connection = Connection.ONLINE, error = null) }
            publish()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onMain {
            if (webSocket === socket) attempt { JSON.decodeFromString<ServerMsg>(text) }.onSuccess(::handle)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            onMain { dropped(webSocket, if (code == 4000) reason else null) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onMain {
            dropped(webSocket, if (response?.code == 401) AUTH else null)
        }
    }

    private fun dropped(webSocket: WebSocket, why: String?) {
        if (webSocket !== socket) return
        socket = null
        lobbyState.update { it.copy(connection = Connection.OFFLINE, seeking = null, room = null) }
        when (why) {
            null -> reconnectLater()
            AUTH -> {
                services.token = null
                reconnectLater()
            }
            else -> {
                wanted = false // signed in on another device; don't fight it
                lobbyState.update { it.copy(error = why) }
            }
        }
        publish()
    }

    private fun handle(msg: ServerMsg) {
        when (msg.t) {
            "hello" -> {
                lobbyState.update { it.copy(me = msg.me ?: it.me, stats = msg.stats ?: it.stats) }
                if (msg.game != null) sync(msg.game)
                else if (gameInProgress) {
                    // It finished while we were away; the result is in recent games.
                    server = null
                    publish()
                    refresh()
                }
            }
            "stats" -> lobbyState.update { it.copy(stats = msg.stats ?: it.stats) }
            "seeking" -> lobbyState.update { it.copy(seeking = msg.tc, room = null, error = null) }
            "room" -> lobbyState.update { it.copy(room = msg.code, roomTc = msg.tc, seeking = null, error = null) }
            "idle" -> lobbyState.update { it.copy(seeking = null, room = null) }
            "game" -> {
                lobbyState.update { it.copy(seeking = null, room = null) }
                msg.game?.let(::sync)
            }
            "error" -> lobbyState.update { it.copy(error = msg.msg) }
        }
    }

    private fun sync(sg: ServerGame) {
        if (sg.id != server?.id) {
            chess = Game()
            viewPly = null
            flipped = sg.color == "b"
        }
        val local = chess.moves.map(::uci)
        if (sg.moves.size < local.size || sg.moves.subList(0, local.size) != local) chess = Game() // our move was rejected
        for (m in sg.moves.drop(chess.moves.size)) if (!chess.playUci(m)) break
        sg.over?.let { chess.end(Outcome(winnerOf(it.result), it.reason)) }
        server = sg
        serverAt = SystemClock.elapsedRealtime()
        if (sg.over != null) refresh()
        publish()
    }

    private fun winnerOf(result: String) = when (result) {
        "1-0" -> WHITE
        "0-1" -> BLACK
        else -> 0
    }

    private val myColor get() = if (server?.color == "b") BLACK else WHITE

    private fun canMove() = server?.over == null && server != null && chess.outcome == null &&
        chess.position.side == myColor && lobbyState.value.connection == Connection.ONLINE

    /** Plays [m] immediately and tells the server; the server's echo confirms or reverts it. */
    fun play(m: Int) {
        val sg = server ?: return
        if (!canMove() || m !in chess.legalMoves()) return
        val now = SystemClock.elapsedRealtime()
        val increment = (sg.tc.substringAfter('+', "").toLongOrNull() ?: 0) * 1000
        val white = sg.color == "w"
        val left = (if (white) sg.clock.w else sg.clock.b) - (now - serverAt) + increment
        chess.play(m)
        server = sg.copy(
            moves = sg.moves + uci(m),
            turn = if (white) "b" else "w",
            clock = if (white) sg.clock.copy(w = left) else sg.clock.copy(b = left),
        )
        serverAt = now
        viewPly = null
        send("move") { put("uci", uci(m)) }
        publish()
    }

    private fun send(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val sent = socket?.send(buildJsonObject { put("t", type); fields() }.toString()) ?: false
        if (!sent) lobbyState.update { it.copy(error = "Not connected — reconnecting…") }
    }

    fun seek(tc: String) = send("seek") { put("tc", tc) }
    fun createRoom(tc: String) = send("create") { put("tc", tc) }
    fun joinRoom(code: String) = send("join") { put("code", code.trim()) }
    fun cancelSeek() = send("cancel")
    fun resign() = send("resign")
    fun offerDraw() = send("draw")
    fun declineDraw() = send("decline")
    fun clearError() = lobbyState.update { it.copy(error = null) }

    fun flip() {
        flipped = !flipped
        publish()
    }

    fun browse(ply: Int) {
        viewPly = if (ply >= chess.moves.size) null else ply.coerceAtLeast(0)
        publish()
    }

    /** Forget a finished game so the lobby is clean. */
    fun leaveFinished() {
        if (server?.over != null) {
            server = null
            publish()
        }
    }

    private fun publish() {
        val sg = server ?: run { gameState.value = null; return }
        val me = myColor
        val bottom = if (flipped) BLACK else WHITE
        val live = sg.over == null
        fun player(color: Int): PlayerUi {
            val seat = if (color == WHITE) sg.white else sg.black
            val delta = sg.over?.delta?.get(if (color == WHITE) "w" else "b")
            return PlayerUi(
                name = if (color == me) "${seat.name} (you)" else seat.name,
                detail = delta?.let { "${seat.rating + it} (${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)})" } ?: "${seat.rating}",
                color = color,
                active = live && sg.turn == (if (color == WHITE) "w" else "b"),
                clockMs = if (color == WHITE) sg.clock.w else sg.clock.b,
                clockAt = serverAt,
                offline = live && color != me && !sg.opponentOnline,
            )
        }
        val opponentColor = if (me == WHITE) "b" else "w"
        gameState.value = GameUi(
            key = "online-${sg.id}",
            view = BoardView.of(chess, viewPly, canMove()),
            flipped = flipped,
            top = player(-bottom),
            bottom = player(bottom),
            outcome = chess.outcome,
            banner = when {
                !live -> null
                lobbyState.value.connection != Connection.ONLINE -> "Connection lost — reconnecting…"
                sg.drawOffer == opponentColor -> "Your opponent offers a draw"
                sg.drawOffer != null -> "Draw offered — waiting for a reply"
                !sg.opponentOnline -> "Opponent disconnected — their clock is still running"
                else -> null
            },
            drawOffered = live && sg.drawOffer == opponentColor,
            you = me,
        )
    }

    override fun onCleared() {
        socket?.close(1000, null)
    }

    private companion object {
        const val AUTH = "auth"
    }
}
