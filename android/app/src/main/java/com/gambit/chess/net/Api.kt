package com.gambit.chess.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val rating: Int,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val token: String? = null,
)

@Serializable data class Seat(val id: String, val name: String, val rating: Int)
@Serializable data class Clock(val w: Long, val b: Long)
@Serializable data class Over(val result: String, val reason: String, val delta: Map<String, Int> = emptyMap())

/** The server's full view of a live game, sent on every change. */
@Serializable
data class ServerGame(
    val id: String,
    val tc: String,
    val color: String,
    val white: Seat,
    val black: Seat,
    val moves: List<String>,
    val turn: String,
    val clock: Clock,
    val drawOffer: String? = null,
    val opponentOnline: Boolean = true,
    val over: Over? = null,
)

@Serializable
data class ServerMsg(
    val t: String,
    val me: Profile? = null,
    val game: ServerGame? = null,
    val code: String? = null,
    val tc: String? = null,
    val msg: String? = null,
)

@Serializable
data class GameRecord(
    val id: String,
    val result: String,
    val reason: String,
    val tc: String,
    val endedAt: Long,
    val color: String,
    val delta: Int,
    val opponent: String,
)

class ApiError(val status: Int, message: String) : Exception(message)

class Api(private val baseUrl: () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = baseUrl().trim().trimEnd('/') + path

    private suspend fun request(method: String, path: String, token: String? = null, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url(path))
                .method(method, body?.toRequestBody(jsonType))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val message = runCatching { JSON.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    throw ApiError(res.code, message ?: "Server error ${res.code}")
                }
                text
            }
        }

    private fun nameBody(name: String) = buildJsonObject { put("name", name) }.toString()

    suspend fun register(name: String): Profile = JSON.decodeFromString(request("POST", "/api/players", body = nameBody(name)))
    suspend fun me(token: String): Profile = JSON.decodeFromString(request("GET", "/api/me", token))
    suspend fun rename(token: String, name: String): Profile = JSON.decodeFromString(request("PUT", "/api/me", token, nameBody(name)))
    suspend fun leaderboard(): List<Profile> = JSON.decodeFromString(request("GET", "/api/leaderboard"))
    suspend fun myGames(token: String): List<GameRecord> = JSON.decodeFromString(request("GET", "/api/me/games", token))

    fun socket(token: String, listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(url("/ws")).header("Authorization", "Bearer $token").build(), listener)
}
