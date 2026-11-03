package com.gambit.chess.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gambit.chess.BoardView
import com.gambit.chess.SavedGame
import com.gambit.chess.Settings
import com.gambit.chess.core.BLACK
import com.gambit.chess.core.Game
import com.gambit.chess.core.KING
import com.gambit.chess.core.KNIGHT
import com.gambit.chess.core.Level
import com.gambit.chess.core.WHITE

/** Clickable that gently shrinks while pressed; put it first so the scale covers clip and background. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(18.dp))
        .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
}

@Composable
fun HomeScreen(
    saved: SavedGame?,
    settings: Settings,
    onContinue: () -> Unit,
    onComputer: (Level, Int) -> Unit,
    onFriend: () -> Unit,
    onOnline: () -> Unit,
    onSettings: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val theme = BoardThemes[settings.theme.coerceIn(BoardThemes.indices)]
    val preview = remember(saved) {
        val game = Game()
        val moves = saved?.moves ?: listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6")
        moves.forEach { game.playUci(it) }
        BoardView.of(game, null, canMove = false)
    }

    Column(
        Modifier.fillMaxSize().background(Palette.Night).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Accent), contentAlignment = Alignment.Center) {
                PieceIcon(KNIGHT * WHITE, Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Gambit", style = MaterialTheme.typography.headlineMedium)
                Text("Chess, beautifully simple", color = Palette.Muted, fontSize = 13.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
        }

        ChessBoard(
            preview,
            flipped = saved?.let { it.vsComputer && it.userColor == BLACK } ?: false,
            theme,
            Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saved != null) {
                Surface(color = Palette.Accent, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().pressable(onContinue)) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Continue game", color = Palette.OnAccent, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (saved.vsComputer) "vs Computer · ${saved.level.label}" else "Pass & Play") + " · move ${saved.moves.size / 2 + 1}",
                                color = Palette.OnAccent.copy(alpha = 0.75f), fontSize = 13.sp,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Palette.OnAccent)
                    }
                }
            }
            ModeCard(Icons.Rounded.SmartToy, "Play vs Computer", "Five levels, from Beginner to Master") { picking = true }
            ModeCard(Icons.Rounded.Groups, "Pass & Play", "Two players on one device", onFriend)
            ModeCard(Icons.Rounded.Public, "Play Online", "Rated games, quick match and private rooms", onOnline)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (picking) ComputerSetup(settings, onDismiss = { picking = false }) { level, color ->
        picking = false
        onComputer(level, color)
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(color = Palette.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().pressable(onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Palette.Accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Palette.Muted, fontSize = 13.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Palette.Muted)
        }
    }
}

@Composable
private fun ComputerSetup(settings: Settings, onDismiss: () -> Unit, onStart: (Level, Int) -> Unit) {
    var level by remember { mutableStateOf(settings.level) }
    var color by remember { mutableStateOf(settings.color) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Play vs Computer", style = MaterialTheme.typography.titleLarge)
            Text("Strength", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Level.entries.forEachIndexed { i, l ->
                    val on = l == level
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) Palette.Accent.copy(alpha = 0.14f) else Palette.Raised)
                            .border(BorderStroke(1.5.dp, if (on) Palette.Accent else Color.Transparent), RoundedCornerShape(12.dp))
                            .clickable { level = l }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(l.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                        Text(l.blurb, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Row { repeat(5) { s -> Box(Modifier.padding(start = 3.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (s <= i) Palette.Accent else Palette.Line)) } }
                    }
                }
            }
            Text("Play as", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((value, label) in listOf(WHITE to "White", 0 to "Random", BLACK to "Black")) {
                    val on = value == color
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (on) Palette.Accent.copy(alpha = 0.14f) else Palette.Raised)
                            .border(BorderStroke(1.5.dp, if (on) Palette.Accent else Color.Transparent), RoundedCornerShape(12.dp))
                            .clickable { color = value }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (value == 0) Row {
                            PieceIcon(KING * WHITE, Modifier.size(30.dp))
                            PieceIcon(KING * BLACK, Modifier.size(30.dp), onDark = true)
                        } else PieceIcon(KING * value, Modifier.size(30.dp), onDark = true)
                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Button(onClick = { onStart(level, color) }, modifier = Modifier.fillMaxWidth().padding(top = 22.dp).height(52.dp)) {
                Text("Start game", fontSize = 16.sp)
            }
        }
    }
}
