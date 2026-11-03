package com.gambit.chess

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gambit.chess.core.Level
import com.gambit.chess.core.WHITE
import com.gambit.chess.net.Api
import com.gambit.chess.net.JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val name: String = "",
    val theme: Int = 0,
    val sound: Boolean = true,
    val showLegal: Boolean = true,
    val server: String = "",
    val level: Level = Level.CASUAL,
    /** WHITE, BLACK, or 0 for random. */
    val color: Int = WHITE,
) {
    val serverUrl: String get() = server.ifBlank { BuildConfig.DEFAULT_SERVER }
}

/** An unfinished local game, replayed from its moves on resume. */
@Serializable
data class SavedGame(val vsComputer: Boolean, val level: Level, val userColor: Int, val moves: List<String>)

class GambitApp : Application() {
    private val prefs: SharedPreferences by lazy { getSharedPreferences("gambit", MODE_PRIVATE) }
    private val settingsState by lazy {
        MutableStateFlow(prefs.getString("settings", null)?.let { attempt { JSON.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings())
    }
    val settings: StateFlow<Settings> get() = settingsState
    val api by lazy { Api { settings.value.serverUrl } }
    val sfx by lazy { Sfx() }

    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(settingsState.value)
        settingsState.value = next
        prefs.edit { putString("settings", JSON.encodeToString(next)) }
    }

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit { putString("token", value) }

    fun loadGame(): SavedGame? = prefs.getString("saved", null)?.let { attempt { JSON.decodeFromString<SavedGame>(it) }.getOrNull() }
    fun saveGame(game: SavedGame?) = prefs.edit { putString("saved", game?.let { JSON.encodeToString(it) }) }
}

/** runCatching that never swallows coroutine cancellation. */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
