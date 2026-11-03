package com.gambit.chess.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gambit.chess.Connection
import com.gambit.chess.Lobby

private val TIME_CONTROLS = listOf("3+2" to "Blitz", "5+0" to "Blitz", "10+0" to "Rapid")

@Composable
fun OnlineLobby(
    lobby: Lobby,
    gameInProgress: Boolean,
    onBack: () -> Unit,
    onSeek: (String) -> Unit,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    onCancel: () -> Unit,
    onReturnToGame: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var tc by rememberSaveable { mutableStateOf("5+0") }
    var joining by rememberSaveable { mutableStateOf(false) }
    val online = lobby.connection == Connection.ONLINE
    val waiting = lobby.seeking != null || lobby.room != null

    Column(Modifier.fillMaxSize().background(Palette.Night).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Play Online", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ConnectionChip(lobby.connection)
            Spacer(Modifier.width(12.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileCard(lobby)

                lobby.error?.let { error ->
                    Surface(color = Palette.Danger.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                            if (!online) TextButton(onClick = onRetry) { Text("Retry") }
                        }
                    }
                }

                if (gameInProgress) {
                    Surface(color = Palette.Accent.copy(alpha = 0.16f), shape = RoundedCornerShape(16.dp), modifier = Modifier.pressable(onReturnToGame)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Game in progress", fontWeight = FontWeight.SemiBold)
                                Text("Your clock may be running", color = Palette.Muted, fontSize = 13.sp)
                            }
                            Button(onClick = onReturnToGame) { Text("Return") }
                        }
                    }
                }

                Text("Time control", color = Palette.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((value, kind) in TIME_CONTROLS) {
                        val on = value == tc
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Palette.Accent.copy(alpha = 0.14f) else Palette.Surface)
                                .border(BorderStroke(1.5.dp, if (on) Palette.Accent else Color.Transparent), RoundedCornerShape(12.dp))
                                .clickable(enabled = !waiting) { tc = value }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(kind, color = Palette.Muted, fontSize = 12.sp)
                        }
                    }
                }

                when {
                    lobby.seeking != null -> WaitingCard("Finding an opponent…", "${lobby.seeking} · rated", onCancel)
                    lobby.room != null -> RoomCard(lobby.room, lobby.roomTc ?: tc, onCancel)
                    else -> {
                        Button(
                            onClick = { onSeek(tc) },
                            enabled = online && !gameInProgress,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) { Text("Find opponent", fontSize = 16.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { onCreate(tc) }, enabled = online && !gameInProgress, modifier = Modifier.weight(1f)) { Text("Create room") }
                            OutlinedButton(onClick = { joining = true }, enabled = online && !gameInProgress, modifier = Modifier.weight(1f)) { Text("Join room") }
                        }
                    }
                }

                if (lobby.leaderboard.isNotEmpty()) {
                    SectionTitle("Leaderboard")
                    Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            lobby.leaderboard.take(20).forEachIndexed { i, p ->
                                val me = p.id == lobby.me?.id
                                Row(
                                    Modifier.fillMaxWidth().background(if (me) Palette.Accent.copy(alpha = 0.10f) else Color.Transparent)
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${i + 1}", modifier = Modifier.width(30.dp), fontWeight = FontWeight.Bold,
                                        color = when (i) { 0 -> Palette.Gold; 1 -> Color(0xFFC9CED6); 2 -> Color(0xFFD08B5B); else -> Palette.Muted },
                                    )
                                    Text(p.name + if (me) " (you)" else "", modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${p.wins}/${p.draws}/${p.losses}", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 14.dp))
                                    Text("${p.rating}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                if (lobby.recent.isNotEmpty()) {
                    SectionTitle("Your recent games")
                    Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            for (g in lobby.recent) {
                                val won = (g.result == "1-0" && g.color == "w") || (g.result == "0-1" && g.color == "b")
                                val draw = g.result == "1/2-1/2"
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                            .background(if (draw) Palette.Gold else if (won) Palette.Accent else Palette.Danger),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(if (draw) "½" else if (won) "W" else "L", color = Palette.OnAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("vs ${g.opponent}", maxLines = 1)
                                        Text(
                                            "${g.tc} · ${reasonText(g.reason)} · " +
                                                ago(g.endedAt),
                                            color = Palette.Muted, fontSize = 12.sp, maxLines = 1,
                                        )
                                    }
                                    Text(if (g.delta >= 0) "+${g.delta}" else "${g.delta}", color = if (g.delta >= 0) Palette.Accent else Palette.Danger, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (joining) JoinDialog(onDismiss = { joining = false }) { code ->
        joining = false
        onJoin(code)
    }
}

private fun ago(time: Long): CharSequence {
    val now = System.currentTimeMillis()
    return if (now - time < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS)
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun ConnectionChip(c: Connection) {
    val (color, label) = when (c) {
        Connection.ONLINE -> Palette.Accent to "Online"
        Connection.CONNECTING -> Palette.Gold to "Connecting"
        Connection.OFFLINE -> Palette.Danger to "Offline"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ProfileCard(lobby: Lobby) {
    val me = lobby.me
    Surface(color = Palette.Surface, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                Text(me?.name?.take(1)?.uppercase() ?: "?", color = Palette.OnAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me?.name ?: "Signing in…", style = MaterialTheme.typography.titleMedium)
                if (me != null) Text("${me.wins} won · ${me.draws} drawn · ${me.losses} lost", color = Palette.Muted, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(me?.rating?.toString() ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("rating", color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WaitingCard(title: String, detail: String, onCancel: () -> Unit) {
    Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Palette.Muted, fontSize = 13.sp)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun RoomCard(code: String, tc: String, onCancel: () -> Unit) {
    val context = LocalContext.current
    Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Share this code with a friend", color = Palette.Muted, fontSize = 13.sp)
            Text(code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Waiting for them to join · $tc", color = Palette.Muted, fontSize = 13.sp)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Play chess with me on Gambit! Room code: $code")
                    context.startActivity(Intent.createChooser(send, "Share room code"))
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Share")
                }
            }
        }
    }
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a room") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(5) },
                label = { Text("Room code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
        },
        confirmButton = { TextButton(onClick = { onJoin(code) }, enabled = code.length == 5) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
