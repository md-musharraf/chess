package com.gambit.chess.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gambit.chess.core.BLACK
import com.gambit.chess.core.Outcome
import com.gambit.chess.core.WHITE
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

private val WhiteBarColor = Color(0xFFF0F0F0)
private val BlackBarColor = Color(0xFF21252B)

/**
 * Vertical evaluation bar showing White vs Black advantage.
 * [centipawns] is from White's perspective (+ means White leads, - means Black leads).
 */
@Composable
fun EvalBar(
    centipawns: Int,
    flipped: Boolean,
    outcome: Outcome?,
    modifier: Modifier = Modifier,
) {
    // 0.0 (all black) to 1.0 (all white)
    val targetWhiteFraction: Float = when {
        outcome?.winner == WHITE -> 1f
        outcome?.winner == BLACK -> 0f
        outcome != null -> 0.5f
        else -> {
            // Standard sigmoid: 1 / (1 + 10^(-cp / 400))
            val winProb = (1.0 / (1.0 + exp(-0.0035 * centipawns))).toFloat()
            winProb.coerceIn(0.04f, 0.96f)
        }
    }

    val animatedWhiteFraction by animateFloatAsState(
        targetValue = targetWhiteFraction,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "evalBar",
    )

    val shape = RoundedCornerShape(6.dp)

    // Score label text
    val label = when {
        outcome?.winner == WHITE -> "1-0"
        outcome?.winner == BLACK -> "0-1"
        outcome != null -> "½"
        abs(centipawns) >= 9000 -> if (centipawns > 0) "+M" else "-M"
        else -> {
            val pawns = centipawns / 100.0
            if (centipawns > 0) "+${String.format(Locale.US, "%.1f", pawns)}"
            else if (centipawns < 0) String.format(Locale.US, "%.1f", pawns)
            else "0.0"
        }
    }

    // Determine which side is at the bottom:
    // If not flipped: White is bottom, Black is top.
    // If flipped: Black is bottom, White is top.
    val bottomFraction = (if (flipped) 1f - animatedWhiteFraction else animatedWhiteFraction).coerceIn(0f, 1f)
    val bottomColor = if (flipped) BlackBarColor else WhiteBarColor
    val topColor = if (flipped) WhiteBarColor else BlackBarColor

    Box(
        modifier = modifier
            .clip(shape)
            .background(topColor)
            .border(1.dp, Palette.Line, shape),
    ) {
        // Bottom portion
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(bottomFraction)
                .align(Alignment.BottomCenter)
                .background(bottomColor),
        )

        // Numerical score indicator placed on the advantageous side
        val labelOnBottom = when {
            outcome?.winner == WHITE -> !flipped
            outcome?.winner == BLACK -> flipped
            else -> (centipawns >= 0 && !flipped) || (centipawns < 0 && flipped)
        }
        val labelColor = if (labelOnBottom) {
            if (flipped) WhiteBarColor else Color(0xFF1E2024)
        } else {
            if (flipped) Color(0xFF1E2024) else WhiteBarColor
        }

        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor,
            modifier = Modifier
                .padding(vertical = 3.dp)
                .align(if (labelOnBottom) Alignment.BottomCenter else Alignment.TopCenter),
        )
    }
}
