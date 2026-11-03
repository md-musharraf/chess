package com.gambit.chess.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Night = Color(0xFF121417)
    val Surface = Color(0xFF1B1E23)
    val Raised = Color(0xFF252931)
    val Line = Color(0xFF30353E)
    val Text = Color(0xFFECEDEF)
    val Muted = Color(0xFF9AA0A9)
    val Accent = Color(0xFF81B64C)
    val OnAccent = Color(0xFF10170A)
    val Gold = Color(0xFFE8B341)
    val Danger = Color(0xFFE5534B)
}

@Immutable
data class BoardTheme(val name: String, val light: Color, val dark: Color, val highlight: Color)

val BoardThemes = listOf(
    BoardTheme("Classic", Color(0xFFEBECD0), Color(0xFF739552), Color(0x99F5F682)),
    BoardTheme("Walnut", Color(0xFFF0D9B5), Color(0xFFB58863), Color(0x99F7EC5E)),
    BoardTheme("Ocean", Color(0xFFDEE3E6), Color(0xFF8CA2AD), Color(0x99B9D86A)),
    BoardTheme("Graphite", Color(0xFFD9DADC), Color(0xFF7D828B), Color(0x99F5D36A)),
)

@Composable
fun GambitTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Palette.OnAccent,
        secondary = Palette.Gold,
        background = Palette.Night,
        onBackground = Palette.Text,
        surface = Palette.Surface,
        onSurface = Palette.Text,
        surfaceVariant = Palette.Raised,
        onSurfaceVariant = Palette.Muted,
        surfaceContainerLowest = Palette.Night,
        surfaceContainerLow = Palette.Surface,
        surfaceContainer = Palette.Surface,
        surfaceContainerHigh = Palette.Raised,
        surfaceContainerHighest = Palette.Raised,
        outline = Palette.Line,
        outlineVariant = Palette.Line,
        error = Palette.Danger,
    )
    val type = Typography(
        headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 14.sp),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = scheme, typography = type) {
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
