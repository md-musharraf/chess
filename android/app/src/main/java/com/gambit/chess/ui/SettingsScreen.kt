package com.gambit.chess.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gambit.chess.BuildConfig
import com.gambit.chess.Settings
import com.gambit.chess.core.KNIGHT
import com.gambit.chess.core.WHITE

@Composable
fun SettingsScreen(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onRename: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var name by rememberSaveable { mutableStateOf(settings.name) }
    var server by rememberSaveable { mutableStateOf(settings.server) }
    val nameValid = name.trim().length in 2..20

    Column(Modifier.fillMaxSize().background(Palette.Night).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("Your name")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(20) },
                        singleLine = true,
                        isError = name.isNotEmpty() && !nameValid,
                        placeholder = { Text("Shown to opponents online") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onChange { it.copy(name = name.trim()) }
                            onRename(name.trim())
                        },
                        enabled = nameValid && name.trim() != settings.name,
                    ) { Text("Save") }
                }

                Label("Board")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BoardThemes.forEachIndexed { i, theme ->
                        val on = i == settings.theme
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                    .border(BorderStroke(if (on) 3.dp else 0.dp, if (on) Palette.Accent else Color.Transparent), RoundedCornerShape(10.dp))
                                    .clickable { onChange { it.copy(theme = i) } },
                            ) {
                                val s = size.width / 2
                                for (r in 0..1) for (c in 0..1) drawRect(if ((r + c) % 2 == 0) theme.light else theme.dark, Offset(c * s, r * s), Size(s, s))
                                drawPiece(KNIGHT * WHITE, Offset(s * 0.5f, s * 0.5f), s)
                            }
                            Text(theme.name, fontSize = 12.sp, color = if (on) Palette.Text else Palette.Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }

                Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp)) {
                    Column {
                        Toggle("Move sounds", "Wooden clicks, captures and checks", settings.sound) { v -> onChange { it.copy(sound = v) } }
                        Toggle("Show legal moves", "Dots on the squares a piece can reach", settings.showLegal) { v -> onChange { it.copy(showLegal = v) } }
                        Toggle("Evaluation bar", "Show real-time advantage bar and score", settings.showEvalBar) { v -> onChange { it.copy(showEvalBar = v) } }
                    }
                }

                Label("Online server")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it.trim() },
                        singleLine = true,
                        placeholder = { Text(BuildConfig.DEFAULT_SERVER) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onChange { it.copy(server = server) } }, enabled = server != settings.server) { Text("Save") }
                }
                Text(
                    "Emulator: ${BuildConfig.DEFAULT_SERVER}. On a phone, use your computer's LAN address, e.g. http://192.168.1.20:8080.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Gambit ${BuildConfig.VERSION_NAME} · original piece set", color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, color = Palette.Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
