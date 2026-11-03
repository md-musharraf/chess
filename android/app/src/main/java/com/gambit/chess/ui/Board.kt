package com.gambit.chess.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gambit.chess.BoardView
import com.gambit.chess.core.PAWN
import com.gambit.chess.core.fileOf
import com.gambit.chess.core.moveFrom
import com.gambit.chess.core.movePromo
import com.gambit.chess.core.moveTo
import com.gambit.chess.core.rankOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

private const val MOVE_MS = 220
private val TargetColor = Color(0x2E000000)
private val ArrowColor = Color(0xD9F29B1D)
private val CheckColor = Color(0xFFFF3B30)

private class Drag(val from: Int, val piece: Int, val at: Offset)
private class Track(val piece: Int, val from: Int, val to: Int)

/** How to get from one board to the next: pieces that slide, fade out (captured) or fade in. */
private class Transition(val tracks: List<Track>, val fadeOut: List<Pair<Int, Int>>, val fadeIn: Set<Int>) {
    val arriving: Set<Int> = tracks.mapTo(HashSet()) { it.to } + fadeIn

    companion object {
        val NONE = Transition(emptyList(), emptyList(), emptySet())
    }
}

/**
 * Diffs two boards instead of replaying a move, so castling, en passant, promotion, undo, history
 * browsing and server resyncs all animate the same way. [dropFrom]/[dropTo] is a piece the user
 * just dragged there by hand; it lands without sliding.
 */
private fun transition(old: IntArray, new: IntArray, dropFrom: Int, dropTo: Int): Transition {
    if (old.contentEquals(new)) return Transition.NONE
    val gone = (0..63).filterTo(ArrayList()) { old[it] != 0 && old[it] != new[it] }
    val tracks = ArrayList<Track>()
    val fadeIn = HashSet<Int>()
    for (to in 0..63) {
        val p = new[to]
        if (p == 0 || old[to] == p) continue
        val src = gone.filter { old[it] == p }.minByOrNull { distance(it, to) }
            ?: gone.filter { old[it] == PAWN * p.sign && rankOf(to) % 7 == 0 }.minByOrNull { distance(it, to) }
        if (src == null) {
            fadeIn += to
            continue
        }
        gone.remove(src)
        tracks += Track(p, if (src == dropFrom && to == dropTo) to else src, to)
    }
    return Transition(tracks, gone.map { old[it] to it }, fadeIn)
}

private fun distance(a: Int, b: Int) = max(abs(fileOf(a) - fileOf(b)), abs(rankOf(a) - rankOf(b)))

@Composable
fun ChessBoard(
    view: BoardView,
    flipped: Boolean,
    theme: BoardTheme,
    modifier: Modifier = Modifier,
    showLegal: Boolean = true,
    hint: Int = 0,
    onMove: (Int) -> Unit = {},
) {
    val board = view.board
    val haptics = LocalHapticFeedback.current
    var selected by remember { mutableIntStateOf(-1) }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var promotion by remember { mutableStateOf<List<Int>>(emptyList()) }
    val lastDrop = remember { intArrayOf(-1, -1) }

    val previous = remember { arrayOf(board) }
    val anim = remember(board) { transition(previous[0], board, lastDrop[0], lastDrop[1]).also { lastDrop.fill(-1) } }
    SideEffect { previous[0] = board }
    val progress = remember(board) { Animatable(if (anim === Transition.NONE) 1f else 0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(MOVE_MS, easing = FastOutSlowInEasing)) }

    LaunchedEffect(view.legal) {
        if (view.legal.none { moveFrom(it) == selected }) selected = -1
        promotion = emptyList()
    }

    val legalNow by rememberUpdatedState(view.legal)
    val boardNow by rememberUpdatedState(board)
    val flippedNow by rememberUpdatedState(flipped)
    val onMoveNow by rememberUpdatedState(onMove)

    fun commit(from: Int, to: Int, dropped: Boolean) {
        val options = legalNow.filter { moveFrom(it) == from && moveTo(it) == to }
        selected = -1
        if (options.isEmpty()) return
        if (dropped) {
            lastDrop[0] = from
            lastDrop[1] = to
        }
        if (options.size > 1) {
            promotion = options
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onMoveNow(options[0])
        }
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val files = remember(measurer) { "abcdefgh".map { measurer.measure("$it", labelStyle) } }
    val ranks = remember(measurer) { "12345678".map { measurer.measure("$it", labelStyle) } }

    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = "Chess board" }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                fun squareAt(at: Offset): Int {
                    val s = size.width / 8f
                    val col = (at.x / s).toInt()
                    val row = (at.y / s).toInt()
                    if (at.x < 0 || at.y < 0 || col > 7 || row > 7) return -1
                    return if (flippedNow) row * 8 + (7 - col) else (7 - row) * 8 + col
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val legal = legalNow
                    if (legal.isEmpty() || promotion.isNotEmpty()) return@awaitEachGesture
                    val sq = squareAt(down.position)
                    val sel = selected
                    if (sel >= 0 && legal.any { moveFrom(it) == sel && moveTo(it) == sq }) {
                        if (waitForUpOrCancellation() != null) commit(sel, sq, dropped = false)
                        return@awaitEachGesture
                    }
                    if (legal.none { moveFrom(it) == sq }) {
                        selected = -1
                        return@awaitEachGesture
                    }
                    val wasSelected = sel == sq
                    selected = sq
                    var dragging = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                val to = squareAt(change.position)
                                if (to != sq) commit(sq, to, dropped = true)
                            } else if (wasSelected) {
                                selected = -1
                            }
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            change.consume()
                            drag = Drag(sq, boardNow[sq], change.position)
                        }
                    }
                    drag = null
                }
            },
        ) {
            val s = size.width / 8f
            val cell = Size(s, s)
            fun origin(sq: Int) = Offset(
                (if (flipped) 7 - fileOf(sq) else fileOf(sq)) * s,
                (if (flipped) rankOf(sq) else 7 - rankOf(sq)) * s,
            )
            fun center(sq: Int) = origin(sq) + Offset(s / 2, s / 2)
            fun isDark(sq: Int) = (fileOf(sq) + rankOf(sq)) % 2 == 0

            for (sq in 0..63) drawRect(if (isDark(sq)) theme.dark else theme.light, origin(sq), cell)
            if (view.lastMove != 0) {
                drawRect(theme.highlight, origin(moveFrom(view.lastMove)), cell)
                drawRect(theme.highlight, origin(moveTo(view.lastMove)), cell)
            }
            if (selected >= 0) drawRect(theme.highlight, origin(selected), cell)
            if (view.check >= 0) {
                drawRect(
                    Brush.radialGradient(
                        0f to CheckColor, 0.45f to CheckColor.copy(alpha = 0.6f), 1f to Color.Transparent,
                        center = center(view.check), radius = s * 0.72f,
                    ),
                    origin(view.check), cell,
                )
            }
            for (i in 0..7) {
                val file = if (flipped) 7 - i else i
                val bottom = (if (flipped) 7 else 0) * 8 + file
                val f = files[file]
                drawText(
                    f, color = if (isDark(bottom)) theme.light else theme.dark,
                    topLeft = Offset((i + 1) * s - f.size.width - s * 0.06f, 8 * s - f.size.height - s * 0.02f),
                )
                val rank = if (flipped) i else 7 - i
                val left = rank * 8 + if (flipped) 7 else 0
                drawText(ranks[rank], color = if (isDark(left)) theme.light else theme.dark, topLeft = Offset(s * 0.06f, i * s + s * 0.03f))
            }

            val targets = if (selected >= 0 && showLegal) view.legal.filter { moveFrom(it) == selected } else emptyList()
            for (m in targets) if (board[moveTo(m)] != 0) drawCircle(TargetColor, s * 0.44f, center(moveTo(m)), style = Stroke(s * 0.09f))

            val p = progress.value
            val held = drag?.from ?: -1
            for (sq in 0..63) if (board[sq] != 0 && sq !in anim.arriving && sq != held) drawPiece(board[sq], origin(sq), s)
            for ((piece, sq) in anim.fadeOut) drawPiece(piece, origin(sq), s, alpha = 1f - p)
            for (sq in anim.fadeIn) if (sq != held) drawPiece(board[sq], origin(sq), s, alpha = p)
            for (t in anim.tracks) if (t.to != held) drawPiece(t.piece, lerp(origin(t.from), origin(t.to), p), s)

            for (m in targets) if (board[moveTo(m)] == 0) drawCircle(TargetColor, s * 0.16f, center(moveTo(m)))
            if (hint != 0) drawArrow(center(moveFrom(hint)), center(moveTo(hint)), s)

            drag?.let { d ->
                val col = (d.at.x / s).toInt()
                val row = (d.at.y / s).toInt()
                if (d.at.x >= 0 && d.at.y >= 0 && col <= 7 && row <= 7) {
                    drawCircle(Color.White.copy(alpha = 0.28f), s * 0.78f, Offset(col * s + s / 2, row * s + s / 2))
                }
                drawPiece(d.piece, d.at - Offset(s * 0.6f, s * 0.75f), s * 1.2f)
            }
        }

        if (promotion.isNotEmpty()) {
            Box(
                Modifier.matchParentSize().background(Color(0x99000000)).pointerInput(Unit) {
                    detectTapGestures {
                        promotion = emptyList()
                        lastDrop.fill(-1)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = Palette.Raised, shadowElevation = 12.dp) {
                    Row(Modifier.padding(8.dp)) {
                        val color = board[moveFrom(promotion[0])].sign
                        for (m in promotion.sortedByDescending { movePromo(it) }) {
                            Box(
                                Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                    promotion = emptyList()
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onMoveNow(m)
                                }.padding(6.dp),
                            ) { PieceIcon(movePromo(m) * color, Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, s: Float) {
    val dir = (to - from) / (to - from).getDistance()
    val head = s * 0.45f
    val neck = to - dir * head
    drawLine(ArrowColor, from, neck + dir * 2f, strokeWidth = s * 0.18f, cap = StrokeCap.Round)
    val side = Offset(-dir.y, dir.x) * (head * 0.6f)
    drawPath(
        Path().apply {
            moveTo(to.x, to.y)
            lineTo(neck.x + side.x, neck.y + side.y)
            lineTo(neck.x - side.x, neck.y - side.y)
            close()
        },
        ArrowColor,
    )
}
