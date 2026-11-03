# Gambit Chess — plan

Android chess app (Kotlin + Jetpack Compose) with a Node backend for online play.

## Architecture

```
android/   Kotlin/Compose app (package com.gambit.chess, minSdk 26)
  chess/   pure-Kotlin rules engine + AI (no Android deps, unit-tested with perft)
  ui/      board canvas, screens, theme
  net/     REST + WebSocket client (OkHttp, kotlinx.serialization)
server/    Node 24, `ws` + `chess.js`, built-in `node:sqlite`
```

**Server is authoritative online**: every move is validated with chess.js, clocks run on the
server, results and Elo are written by the server. The client applies its own move optimistically
and resyncs from the server's full state message.

## Tasks

1. [x] Plan (this file)
2. [x] Backend
   - guest accounts (`POST /api/players` → token, stored hashed), `GET/PUT /api/me`
   - `GET /api/leaderboard`, `GET /api/me/games`
   - WebSocket `/ws` (Bearer token): quick match by time control, private rooms by code,
     moves, resign, draw offer/accept/decline, reconnect-resume, server clocks + flag
   - Elo (K=32), game records with PGN in SQLite
   - `node --test`: full game over sockets, illegal move rejected, rooms, resign, draw
3. [x] Chess engine (Kotlin)
   - board, make/unmake, legal move gen (castling, en passant, promotion), Zobrist
   - SAN, UCI, status: checkmate, stalemate, 50-move, threefold, insufficient material
   - AI: iterative deepening alpha-beta + quiescence, PST eval, 5 levels
   - JVM tests: perft on 5 standard positions, SAN, mate-in-one
4. [x] Android UI
   - original vector piece set, 4 board themes, coordinates
   - tap-to-move + drag-and-drop, legal-move dots, last move / check highlight
   - animated moves (generic board diff: castling, en passant, undo, resync all animate)
   - promotion picker, move list, history browsing, captured pieces + material
   - vs computer (level + colour), pass & play, undo, hint arrow, resign
   - online lobby: profile, quick match, rooms, leaderboard; online game with clocks,
     draw offers, opponent-offline banner, rating change
   - sound (synthesised, no assets) + haptics, saved game resume, portrait + landscape
5. [x] Verify: unit tests, server tests, build APK, play on emulator against the local server

## Status (2026-10-05)

All five tasks done. Verified: 12 JVM tests (perft on 5 positions, SAN, draws, engine) and 6 server
tests pass; on the emulator against the local server — tap and drag moves, AI replies, hint, undo
(including un-promotion), Fool's Mate result card, promotion picker, landscape, two rated online
games (match, moves, draw decline, resign, Elo, leaderboard, history) and resume after the app is
killed mid-game. Release APK (R8) is 1.5 MB and works online.
