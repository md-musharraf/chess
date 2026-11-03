package com.gambit.chess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.gambit.chess.core.BISHOP
import com.gambit.chess.core.KING
import com.gambit.chess.core.KNIGHT
import com.gambit.chess.core.PAWN
import com.gambit.chess.core.QUEEN
import com.gambit.chess.core.ROOK
import kotlin.math.abs

// Gambit's own piece set, drawn on a 100×100 grid. Each piece is a stack of filled, outlined
// parts painted bottom-up (so the seams read as carved joints) plus a few detail strokes.
private const val BASE = "M22 81 Q22 76 27 76 L73 76 Q78 76 78 81 L78 85 Q78 90 73 90 L27 90 Q22 90 22 85 Z"
private const val COLLAR_HIGH = "M31 62 Q31 58 35 58 L65 58 Q69 58 69 62 Q69 66 65 66 L35 66 Q31 66 31 62 Z"
private const val SKIRT = "M34 61 L66 61 C66 67 68 72 70 77 L30 77 C32 72 34 67 34 61 Z"
private fun ball(x: Float, y: Float, r: Float) = "M${x - r} $y A$r $r 0 1 1 ${x + r} $y A$r $r 0 1 1 ${x - r} $y Z"

private val ART: Map<Int, Pair<List<String>, List<String>>> = mapOf(
    PAWN to (
        listOf(
            "M35 77 C37 66 42 58 44 51 L56 51 C58 58 63 66 65 77 Z",
            BASE,
            "M36 47.5 Q36 43 40.5 43 L59.5 43 Q64 43 64 47.5 Q64 52 59.5 52 L40.5 52 Q36 52 36 47.5 Z",
            ball(50f, 30f, 13f),
        ) to emptyList()
        ),
    KNIGHT to (
        listOf(
            "M29 77 C29 66 34 60 43 55 C37 57 30 60 24 61 C18 62 14 57 16 52 C17 47 21 45 25 42 L35 31 " +
                "C37 25 39 18 44 12 L49 21 C66 22 77 39 75 60 L73 77 Z",
            BASE,
        ) to listOf(ball(33.5f, 35.5f, 2.2f), "M20 53 L24 52", "M49 22 C60 26 68 38 69 55")
        ),
    BISHOP to (
        listOf(
            "M37 77 C39 67 42 61 44 57 L56 57 C58 61 61 67 63 77 Z",
            BASE,
            "M35 54 Q35 50 39 50 L61 50 Q65 50 65 54 Q65 58 61 58 L39 58 Q35 58 35 54 Z",
            "M50 15 C62 24 68 33 67 41 C66 48 59 51 50 51 C41 51 34 48 33 41 C32 33 38 24 50 15 Z",
            ball(50f, 11f, 4.5f),
        ) to listOf("M55 27 L46 39")
        ),
    ROOK to (
        listOf(
            "M33 77 L36 41 L64 41 L67 77 Z",
            "M29 69 L71 69 L71 77 L29 77 Z",
            BASE,
            "M27 17 L38 17 L38 25 L45 25 L45 17 L55 17 L55 25 L62 25 L62 17 L73 17 L73 35 Q73 39 69 41 L31 41 Q27 39 27 35 Z",
        ) to emptyList()
        ),
    QUEEN to (
        listOf(
            SKIRT,
            BASE,
            "M25 31 L34 47 L38 23 L45 44 L50 17 L55 44 L62 23 L66 47 L75 31 L67 62 L33 62 Z",
            COLLAR_HIGH,
            ball(25f, 30f, 4.5f) + ball(38f, 22f, 4.5f) + ball(50f, 15f, 4.5f) + ball(62f, 22f, 4.5f) + ball(75f, 30f, 4.5f),
        ) to emptyList()
        ),
    KING to (
        listOf(
            SKIRT,
            BASE,
            "M47 6 L53 6 L53 12 L59 12 L59 18 L53 18 L53 34 L47 34 L47 18 L41 18 L41 12 L47 12 Z",
            "M50 36 C46 27 39 25 33 27 C25 30 24 40 29 48 C32 53 35 57 36 61 L64 61 C65 57 68 53 71 48 " +
                "C76 40 75 30 67 27 C61 25 54 27 50 36 Z",
            COLLAR_HIGH,
        ) to listOf("M50 37 L50 57")
        ),
)

private class Art(val parts: List<Path>, val details: List<Path>)

private val shapes: Map<Int, Art> by lazy {
    fun parse(d: String) = PathParser().parsePathString(d).toPath()
    ART.mapValues { (_, art) -> Art(art.first.map(::parse), art.second.map(::parse)) }
}

private val WhiteFill = Color(0xFFF7F5F0)
private val BlackFill = Color(0xFF2E2E33)
private val Ink = Color(0xFF1D1D21)
private val BlackDetail = Color(0xFFE9E6DF)
private val DarkUiInk = Color(0xFFB4B9C2)
private val Shadow = Color(0x24000000)
private val OutlineStroke = Stroke(width = 3f, join = StrokeJoin.Round)
private val DetailStroke = Stroke(width = 2.6f, cap = StrokeCap.Round)

/**
 * Draws [piece] (signed, white > 0) filling a [size]-sided square at [topLeft]. [onDark] gives
 * black pieces a light outline so they stay visible on the app's dark surfaces (not on the board).
 */
fun DrawScope.drawPiece(piece: Int, topLeft: Offset, size: Float, alpha: Float = 1f, onDark: Boolean = false) {
    if (piece == 0 || alpha <= 0f) return
    val art = shapes.getValue(abs(piece))
    val white = piece > 0
    val ink = if (onDark && !white) DarkUiInk else Ink
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(size / 100f, size / 100f, pivot = Offset.Zero)
    }) {
        translate(0f, 2.5f) { for (p in art.parts) drawPath(p, Shadow, alpha) }
        for (p in art.parts) {
            drawPath(p, if (white) WhiteFill else BlackFill, alpha)
            drawPath(p, ink, alpha, OutlineStroke)
        }
        for (p in art.details) drawPath(p, if (white) Ink else BlackDetail, alpha, DetailStroke)
    }
}

@Composable
fun PieceIcon(piece: Int, modifier: Modifier = Modifier, onDark: Boolean = false) {
    Canvas(modifier) {
        drawPiece(piece, Offset((size.width - size.minDimension) / 2, (size.height - size.minDimension) / 2), size.minDimension, onDark = onDark)
    }
}
