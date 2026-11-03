package com.gambit.chess.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gambit.chess.GambitApp
import com.gambit.chess.GameUi
import com.gambit.chess.LocalGameViewModel
import com.gambit.chess.OnlineViewModel
import com.gambit.chess.Settings
import com.gambit.chess.core.Outcome
import com.gambit.chess.core.WHITE

private enum class Screen { HOME, LOCAL, ONLINE, ONLINE_GAME, SETTINGS }

@Composable
fun GambitRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as GambitApp
    val local: LocalGameViewModel = viewModel()
    val online: OnlineViewModel = viewModel()
    val settings by app.settings.collectAsStateWithLifecycle()
    var stack by rememberSaveable { mutableStateOf(arrayListOf(Screen.HOME)) }
    val screen = stack.last()

    fun go(s: Screen) {
        stack = ArrayList(stack + s)
    }
    fun back() {
        if (stack.size > 1) stack = ArrayList(stack.dropLast(1)) else (context as? Activity)?.finish()
    }

    val inOnline = screen == Screen.ONLINE || screen == Screen.ONLINE_GAME
    LaunchedEffect(inOnline) { if (inOnline) online.start() else online.stop() }
    val onlineGame by online.game.collectAsStateWithLifecycle()
    LaunchedEffect(onlineGame?.key) {
        if (onlineGame != null && onlineGame?.outcome == null && stack.last() == Screen.ONLINE) go(Screen.ONLINE_GAME)
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(150)) },
        modifier = Modifier.fillMaxSize().background(Palette.Night),
        label = "screen",
    ) { s ->
        when (s) {
            Screen.HOME -> {
                // Re-read on each visit so "Continue" reflects the game we just left.
                val saved = remember(stack.size) { app.loadGame() }
                HomeScreen(
                    saved = saved,
                    settings = settings,
                    onContinue = {
                        if (local.ui.value == null || local.ui.value?.outcome != null) local.resume()
                        go(Screen.LOCAL)
                    },
                    onComputer = { level, color ->
                        app.updateSettings { it.copy(level = level, color = color) }
                        local.start(vsComputer = true, level = level, color = color)
                        go(Screen.LOCAL)
                    },
                    onFriend = {
                        local.start(vsComputer = false, level = settings.level, color = WHITE)
                        go(Screen.LOCAL)
                    },
                    onOnline = { go(Screen.ONLINE) },
                    onSettings = { go(Screen.SETTINGS) },
                )
            }
            Screen.LOCAL -> LocalGameRoute(local, settings, onHome = { stack = arrayListOf(Screen.HOME) })
            Screen.ONLINE -> {
                val lobby by online.lobby.collectAsStateWithLifecycle()
                OnlineLobby(
                    lobby = lobby,
                    gameInProgress = onlineGame != null && onlineGame?.outcome == null,
                    onBack = ::back,
                    onSeek = online::seek,
                    onCreate = online::createRoom,
                    onJoin = online::joinRoom,
                    onCancel = online::cancelSeek,
                    onReturnToGame = { go(Screen.ONLINE_GAME) },
                    onRetry = online::retryNow,
                )
            }
            // Idempotent: the outgoing game screen stays composed during the transition and may ask again.
            Screen.ONLINE_GAME -> OnlineGameRoute(online, settings, onLobby = { if (stack.last() == Screen.ONLINE_GAME) back() })
            Screen.SETTINGS -> SettingsScreen(settings, onChange = app::updateSettings, onRename = online::rename, onBack = ::back)
        }
    }
}

private fun outcomeText(o: Outcome, you: Int): Pair<String, Int> = when {
    o.winner == 0 -> "Draw" to 0
    you == 0 -> (if (o.winner == WHITE) "White wins" else "Black wins") to 1
    o.winner == you -> "You won!" to 1
    else -> "You lost" to -1
}

@Composable
private fun LocalGameRoute(vm: LocalGameViewModel, settings: Settings, onHome: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (vm.ui.value == null && !vm.resume()) onHome() }
    val game = ui ?: return Box(Modifier.fillMaxSize().background(Palette.Night))
    var confirmResign by remember { mutableStateOf(false) }
    val setup = vm.setup
    val result = game.outcome?.let { o ->
        val (title, tone) = outcomeText(o, game.you)
        ResultInfo(o.winner, title, reasonText(o.reason).replaceFirstChar { it.uppercase() }, tone, "Play again", vm::rematch, "Home", onHome)
    }
    val actions = buildList {
        add(GameAction(Icons.AutoMirrored.Rounded.Undo, "Undo", vm.canUndo && game.outcome == null) { vm.undo() })
        if (setup.vsComputer) add(GameAction(Icons.Rounded.Lightbulb, "Hint", game.view.legal.isNotEmpty()) { vm.hint() })
        add(GameAction(Icons.Rounded.Flag, "Resign", game.outcome == null) { confirmResign = true })
    }
    GameScreen(
        title = if (setup.vsComputer) "vs Computer" else "Pass & Play",
        subtitle = if (setup.vsComputer) "${setup.level.label} · you play ${if (setup.userColor == WHITE) "White" else "Black"}" else "Hand the phone over after each move",
        ui = game,
        settings = settings,
        onMove = vm::play,
        onBrowse = vm::browse,
        onFlip = vm::flip,
        onBack = onHome,
        actions = actions,
        result = result,
    )
    if (confirmResign) ConfirmDialog("Resign this game?", "Resign", onDismiss = { confirmResign = false }) {
        confirmResign = false
        vm.resign()
    }
}

@Composable
private fun OnlineGameRoute(vm: OnlineViewModel, settings: Settings, onLobby: () -> Unit) {
    val ui by vm.game.collectAsStateWithLifecycle()
    val lobby by vm.lobby.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(lobby.error) {
        lobby.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }
    val game: GameUi = ui ?: run {
        LaunchedEffect(Unit) { onLobby() }
        return
    }
    var confirmResign by remember { mutableStateOf(false) }
    val tc = vm.timeControl
    val leave = {
        vm.leaveFinished()
        onLobby()
    }
    val result = game.outcome?.let { o ->
        val (title, tone) = outcomeText(o, game.you)
        val mine = if (game.bottom.color == game.you) game.bottom else game.top
        ResultInfo(
            o.winner, title, reasonText(o.reason).replaceFirstChar { it.uppercase() } + " · rating ${mine.detail}", tone,
            "New opponent", {
                vm.leaveFinished()
                tc?.let(vm::seek)
                onLobby()
            },
            "Lobby", leave,
        )
    }
    val live = game.outcome == null
    GameScreen(
        title = "${game.top.name.removeSuffix(" (you)")} vs ${game.bottom.name.removeSuffix(" (you)")}",
        subtitle = "Rated · ${tc ?: ""}",
        ui = game,
        settings = settings,
        onMove = vm::play,
        onBrowse = vm::browse,
        onFlip = vm::flip,
        onBack = { if (live) onLobby() else leave() },
        actions = listOf(
            GameAction(Icons.Rounded.Handshake, "Draw", live && !game.drawOffered) { vm.offerDraw() },
            GameAction(Icons.Rounded.Flag, "Resign", live) { confirmResign = true },
        ),
        result = result,
        banner = game.banner?.let { text ->
            {
                BannerRow(text) {
                    if (game.drawOffered) {
                        TextButton(onClick = vm::declineDraw) { Text("Decline") }
                        TextButton(onClick = vm::offerDraw) { Text("Accept") }
                    }
                }
            }
        },
    )
    if (confirmResign) ConfirmDialog("Resign this game?", "Resign", onDismiss = { confirmResign = false }) {
        confirmResign = false
        vm.resign()
    }
}

@Composable
private fun ConfirmDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
