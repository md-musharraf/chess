# Gambit Chess

An Android chess app (Kotlin + Jetpack Compose) with a Node backend for rated online play.

- **vs Computer** — five levels (Beginner → Master), play as White/Black/Random, undo, hint arrow
- **Pass & Play** — two people, one phone
- **Online** — quick match (3+2, 5+0, 10+0), private rooms by 5-letter code, server clocks,
  draw offers, resign, Elo ratings, leaderboard and recent games; reconnecting resumes your game
- Drag-and-drop or tap-to-move, legal-move dots, animated moves (castling, en passant, undo and
  history browsing all animate), promotion picker, check glow, captured pieces, move list you can
  scrub through, synthesised move sounds, haptics, four board themes, portrait and landscape
- Original vector piece set (no third-party art), 1.5 MB release APK

## Run the server

Node 22.13+ (uses the built-in `node:sqlite`).

```bash
cd server
npm install
npm start
```

It listens on `:8080` (`PORT` to change) and stores players and games in `gambit.db`
(`DB_PATH` to change). Tests: `npm test`.

## Run the app

Open `android/` in Android Studio, or:

```bash
cd android
./gradlew installDebug
```

The emulator reaches your computer's server at `http://10.0.2.2:8080` (the default). On a phone,
open **Settings → Online server** and enter your computer's LAN address, e.g.
`http://192.168.1.20:8080`. Engine and rules tests: `./gradlew testDebugUnitTest`.

## How it fits together

```
android/app/src/main/java/com/gambit/chess/
  core/        rules engine (make/unmake, legal moves, SAN, draws) and the AI search — pure Kotlin
  net/Api.kt   REST + WebSocket client and message types
  ui/          board canvas, pieces, screens
  LocalGameViewModel.kt   vs computer / pass & play, saved to prefs after every move
  OnlineViewModel.kt      account, lobby socket, local mirror of the server's game
server/server.js          HTTP API + WebSocket play, chess.js validation, clocks, Elo, SQLite
```

**The server is authoritative.** Every move is validated with chess.js, clocks run on the server
(the side to move loses when its flag falls) and only the server writes results and ratings. The
app plays your move immediately for responsiveness and resyncs from the server's full-state message,
so a rejected move simply snaps back.

### API

| Method | Path | |
|---|---|---|
| `POST` | `/api/players` `{name}` | create a guest player → profile + `token` (stored hashed) |
| `GET` / `PUT` | `/api/me` | your profile / rename (`Authorization: Bearer <token>`) |
| `GET` | `/api/me/games` | your last 20 games with rating changes |
| `GET` | `/api/leaderboard` | top 50 rated players |
| `WS` | `/ws` | live play (Bearer token on the upgrade) |

WebSocket messages, client → server: `seek {tc}`, `create {tc}`, `join {code}`, `cancel`,
`move {uci}`, `resign`, `draw` (offer, or accept the opponent's), `decline`.
Server → client: `hello {me, game}`, `seeking`, `room {code}`, `idle`, `game {game}`, `error {msg}`.

## Before shipping publicly

- Serve the backend over HTTPS/WSS and drop `usesCleartextTraffic` from the manifest.
- Guest accounts live on one device (the token); there is no login/recovery yet.
- Games aren't aborted if a player never makes a first move — their clock simply runs out.
