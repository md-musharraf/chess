package com.gambit.chess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gambit.chess.GambitApp
import com.gambit.chess.GameUi
import com.gambit.chess.PlayerUi
import com.gambit.chess.Settings
import com.gambit.chess.Sfx
import com.gambit.chess.core.BLACK
import com.gambit.chess.core.KING
import com.gambit.chess.core.PAWN
import com.gambit.chess.core.QUEEN
import com.gambit.chess.core.WHITE
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

data class GameAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** The end-of-game card: [tone] is 1 for a win, 0 draw, -1 loss (colours the title). */
data class ResultInfo(
    /** WHITE, BLACK, or 0 for a draw; picks the king shown on the card. */
    val winner: Int,
    val title: String,
    val detail: String,
    val tone: Int,
    val primary: String,
    val onPrimary: () -> Unit,
    val secondary: String,
    val onSecondary: () -> Unit,
)

private val START_COUNT = intArrayOf(0, 8, 2, 2, 2, 1, 0)
private val POINTS = intArrayOf(0, 1, 3, 3, 5, 9, 0)

/** Pieces of the opponent of [color] no longer on the board, cheapest first. */
private fun capturedBy(board: IntArray, color: Int): List<Int> = buildList {
    for (type in PAWN..QUEEN) repeat(max(0, START_COUNT[type] - board.count { it == -color * type })) { add(-color * type) }
}

private fun material(board: IntArray, color: Int) = board.sumOf { if (it * color > 0) POINTS[abs(it)] else 0 }

@Composable
fun GameScreen(
    title: String,
    subtitle: String,
    ui: GameUi,
    settings: Settings,
    onMove: (Int) -> Unit,
    onBrowse: (Int) -> Unit,
    onFlip: () -> Unit,
    onBack: () -> Unit,
    actions: List<GameAction>,
    result: ResultInfo?,
    banner: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val theme = BoardThemes[settings.theme.coerceIn(BoardThemes.indices)]
    val sfx = (LocalContext.current.applicationContext as GambitApp).sfx
    val view = ui.view

    // Sound only for plies that arrive while watching, not for the position we open on.
    val heard = remember(ui.key) { intArrayOf(view.livePly) }
    LaunchedEffect(ui.key, view.livePly) {
        if (view.livePly > heard[0] && settings.sound) {
            val san = view.sans.last()
            sfx.play(
                when {
                    '#' in san || '+' in san -> Sfx.Kind.CHECK
                    'x' in san -> Sfx.Kind.CAPTURE
                    else -> Sfx.Kind.MOVE
                },
            )
        }
        heard[0] = view.livePly
    }
    val endHeard = remember(ui.key) { booleanArrayOf(ui.outcome != null) }
    LaunchedEffect(ui.key, ui.outcome) {
        if (ui.outcome != null && !endHeard[0]) {
            endHeard[0] = true
            delay(250)
            if (settings.sound) sfx.play(Sfx.Kind.END)
        }
    }

    // Let the final move land before the result card slides in; "View board" hides it.
    var resultShown by remember(ui.key) { mutableStateOf(ui.outcome != null) }
    var resultDismissed by remember(ui.key) { mutableStateOf(false) }
    LaunchedEffect(ui.key, result != null) {
        if (result != null && !resultShown) {
            delay(700)
            resultShown = true
        } else if (result == null) {
            resultShown = false
            resultDismissed = false
        }
    }

    val board = view.board
    val context = LocalContext.current
    val copyToClipboard = { label: String, text: String ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }
    val sharePgn = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Gambit Chess Game")
            putExtra(Intent.EXTRA_TEXT, view.pgn)
        }
        context.startActivity(Intent.createChooser(intent, "Share PGN"))
    }
    val effectiveSubtitle = when {
        view.opening != null && subtitle.isNotEmpty() -> "$subtitle · ${view.opening}"
        view.opening != null -> view.opening
        else -> subtitle
    }

    val browse = listOf(
        GameAction(Icons.Rounded.ChevronLeft, "Back", view.ply > 0) { onBrowse(view.ply - 1) },
        GameAction(Icons.Rounded.ChevronRight, "Next", view.ply < view.livePly) { onBrowse(view.ply + 1) },
    )
    val allActions = browse + actions

    @Composable
    fun Player(p: PlayerUi) {
        val advantage = material(board, p.color) - material(board, -p.color)
        PlayerBar(p, capturedBy(board, p.color), advantage, over = ui.outcome != null)
    }

    @Composable
    fun Board(modifier: Modifier) {
        ChessBoard(view, ui.flipped, theme, modifier, showLegal = settings.showLegal, hint = ui.hint, onMove = onMove)
    }

    Box(Modifier.fillMaxSize().background(Palette.Night).safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth <= maxHeight) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        title = title,
                        subtitle = effectiveSubtitle,
                        onBack = onBack,
                        onFlip = onFlip,
                        onCopyFen = { copyToClipboard("FEN", view.fen) },
                        onCopyPgn = { copyToClipboard("PGN", view.pgn) },
                        onSharePgn = sharePgn,
                    )
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Player(ui.top)
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val barWidth = 22.dp
                            val barGap = 6.dp
                            val totalBar = if (settings.showEvalBar) barWidth + barGap else 0.dp
                            val boardSize = minOf(maxWidth - totalBar, maxHeight)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (settings.showEvalBar) {
                                    EvalBar(
                                        centipawns = view.evalScore,
                                        flipped = ui.flipped,
                                        outcome = ui.outcome,
                                        modifier = Modifier.size(width = barWidth, height = boardSize),
                                    )
                                    Spacer(Modifier.width(barGap))
                                }
                                Board(Modifier.size(boardSize))
                            }
                        }
                        Player(ui.bottom)
                        banner?.invoke(this)
                    }
                    MoveList(view.sans, view.ply, onBrowse)
                    ControlBar(allActions)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    val barWidth = 22.dp
                    val barGap = 6.dp
                    if (settings.showEvalBar) {
                        EvalBar(
                            centipawns = view.evalScore,
                            flipped = ui.flipped,
                            outcome = ui.outcome,
                            modifier = Modifier.width(barWidth).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        )
                        Spacer(Modifier.width(barGap))
                    }
                    Board(Modifier.fillMaxHeight().padding(8.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(
                            title = title,
                            subtitle = effectiveSubtitle,
                            onBack = onBack,
                            onFlip = onFlip,
                            onCopyFen = { copyToClipboard("FEN", view.fen) },
                            onCopyPgn = { copyToClipboard("PGN", view.pgn) },
                            onSharePgn = sharePgn,
                        )
                        Player(ui.top)
                        MoveList(view.sans, view.ply, onBrowse)
                        banner?.invoke(this)
                        Spacer(Modifier.weight(1f))
                        Player(ui.bottom)
                        ControlBar(allActions)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = result != null && resultShown && !resultDismissed,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (result != null) ResultCard(result, onViewBoard = { resultDismissed = true }, onSharePgn = sharePgn)
        }
        if (result != null && resultDismissed) {
            Button(
                onClick = { resultDismissed = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp),
            ) { Text(result.title) }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onFlip: () -> Unit,
    onCopyFen: () -> Unit,
    onCopyPgn: () -> Unit,
    onSharePgn: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onFlip) { Icon(Icons.Rounded.SwapVert, contentDescription = "Flip board") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More actions") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy FEN") },
                    onClick = {
                        menuOpen = false
                        onCopyFen()
                    },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("Copy PGN") },
                    onClick = {
                        menuOpen = false
                        onCopyPgn()
                    },
                    leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("Share PGN") },
                    onClick = {
                        menuOpen = false
                        onSharePgn()
                    },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun PlayerBar(p: PlayerUi, captured: List<Int>, advantage: Int, over: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if (p.color == WHITE) Color(0xFFE9E6DF) else Color(0xFF3A3D44))
                .border(BorderStroke(2.dp, if (p.active) Palette.Accent else Color.Transparent), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { PieceIcon(KING * p.color, Modifier.size(30.dp), onDark = true) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.detail.isNotEmpty()) Text("  ${p.detail}", color = Palette.Muted, fontSize = 13.sp, maxLines = 1)
                if (p.offline) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Danger))
                }
            }
            Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                if (p.thinking) ThinkingDots() else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                    for (piece in captured) PieceIcon(piece, Modifier.size(17.dp), onDark = true)
                    if (advantage > 0) Text("   +$advantage", color = Palette.Muted, fontSize = 12.sp)
                }
            }
        }
        p.clockMs?.let { ClockBox(it, p.clockAt, running = p.active && !over) }
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "thinking")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900)), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Thinking", color = Palette.Muted, fontSize = 12.sp)
        repeat(3) { i -> Box(Modifier.padding(start = 3.dp).size(4.dp).alpha(if (phase.toInt() == i) 1f else 0.3f).clip(CircleShape).background(Palette.Muted)) }
    }
}

@Composable
private fun ClockBox(ms: Long, at: Long, running: Boolean) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (running) LaunchedEffect(ms, at) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }
    val left = if (running) (ms - (now - at)).coerceAtLeast(0) else ms
    val low = left < 20_000
    val text = if (left < 10_000) "0:%02d.%d".format(left / 1000, left % 1000 / 100) else "%d:%02d".format(left / 60_000, left / 1000 % 60)
    Surface(
        color = when {
            running && low -> Palette.Danger
            running -> Color(0xFFF2F2F0)
            else -> Palette.Raised
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = if (running && !low) Color(0xFF15171A) else if (running) Color.White else Palette.Muted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MoveList(sans: List<String>, ply: Int, onBrowse: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(sans.size, ply) { if (sans.isNotEmpty()) state.animateScrollToItem(max(0, ply - 4)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        if (sans.isEmpty()) item { Text("Moves will appear here", color = Palette.Muted, fontSize = 13.sp) }
        itemsIndexed(sans) { i, san ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i % 2 == 0) Text("${i / 2 + 1}.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, end = 2.dp))
                val current = i + 1 == ply
                Text(
                    san,
                    fontSize = 14.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = if (current) Palette.Text else Palette.Text.copy(alpha = 0.75f),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (current) Palette.Raised else Color.Transparent)
                        .clickable { onBrowse(i + 1) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlBar(actions: List<GameAction>) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (a in actions) {
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = a.enabled, onClick = a.onClick)
                    .alpha(if (a.enabled) 1f else 0.35f).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, contentDescription = a.label)
                Text(a.label, fontSize = 11.sp, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun ResultCard(r: ResultInfo, onViewBoard: () -> Unit, onSharePgn: () -> Unit) {
    Surface(
        color = Palette.Raised,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 16.dp,
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PieceIcon(KING * if (r.winner == BLACK) BLACK else WHITE, Modifier.size(34.dp), onDark = true)
                Spacer(Modifier.width(10.dp))
                Text(
                    r.title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (r.tone) {
                        1 -> Palette.Accent
                        0 -> Palette.Gold
                        else -> Palette.Text
                    },
                )
            }
            Text(r.detail, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            Button(onClick = r.onPrimary, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(r.primary, fontSize = 16.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewBoard, modifier = Modifier.weight(1f)) { Text("Board") }
                OutlinedButton(onClick = onSharePgn, modifier = Modifier.weight(1f)) { Text("Share") }
                OutlinedButton(onClick = r.onSecondary, modifier = Modifier.weight(1f)) { Text(r.secondary) }
            }
        }
    }
}

/** "by checkmate", "on time" … for the result card. */
fun reasonText(reason: String) = when (reason) {
    "timeout" -> "on time"
    "agreement" -> "by agreement"
    "threefold repetition" -> "by repetition"
    "fifty-move rule" -> "by the fifty-move rule"
    else -> "by $reason"
}

@Composable
fun BannerRow(text: String, content: @Composable () -> Unit = {}) {
    Surface(color = Palette.Raised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            content()
        }
    }
}
