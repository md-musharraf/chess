import http from "node:http";
import { createHash, randomBytes, randomInt, randomUUID } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { pathToFileURL } from "node:url";
import { WebSocketServer } from "ws";
import { Chess } from "chess.js";

/** Time controls offered to players: name → [base ms, increment ms]. */
export const TIME_CONTROLS = { "3+2": [180_000, 2_000], "5+0": [300_000, 0], "10+0": [600_000, 0] };
const K_FACTOR = 32;
const CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const ALLOWED_EMOJIS = new Set(["👍", "👏", "🔥", "😮", "♟️", "💡"]);

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const hashToken = (token) => createHash("sha256").update(token).digest("hex");

function cleanName(raw) {
  const name = String(raw ?? "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 20);
  if (name.length < 2) throw new HttpError(400, "Name must be 2–20 characters");
  return name;
}

function json(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 16 * 1024) throw new HttpError(413, "Request too large");
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw new HttpError(400, "Invalid JSON");
  }
}

export function createServer({ dbPath = ":memory:", timeControls = TIME_CONTROLS, signupsPerHour = 10 } = {}) {
  const db = new DatabaseSync(dbPath);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA busy_timeout = 5000;
    CREATE TABLE IF NOT EXISTS players (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      rating INTEGER NOT NULL DEFAULT 1200,
      wins INTEGER NOT NULL DEFAULT 0,
      losses INTEGER NOT NULL DEFAULT 0,
      draws INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS games (
      id TEXT PRIMARY KEY,
      white TEXT NOT NULL REFERENCES players(id),
      black TEXT NOT NULL REFERENCES players(id),
      result TEXT NOT NULL,
      reason TEXT NOT NULL,
      time_control TEXT NOT NULL,
      white_delta INTEGER NOT NULL,
      black_delta INTEGER NOT NULL,
      pgn TEXT NOT NULL,
      ended_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS games_by_white ON games(white, ended_at);
    CREATE INDEX IF NOT EXISTS games_by_black ON games(black, ended_at);
  `);

  const sql = {
    insertPlayer: db.prepare("INSERT INTO players (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)"),
    byToken: db.prepare("SELECT id FROM players WHERE token_hash = ?"),
    profile: db.prepare("SELECT id, name, rating, wins, losses, draws FROM players WHERE id = ?"),
    rename: db.prepare("UPDATE players SET name = ? WHERE id = ?"),
    leaderboard: db.prepare(
      "SELECT id, name, rating, wins, losses, draws FROM players WHERE wins + losses + draws > 0 ORDER BY rating DESC, wins DESC LIMIT 50",
    ),
    myGames: db.prepare(`
      SELECT g.id, g.result, g.reason, g.time_control AS tc, g.ended_at AS endedAt,
             CASE WHEN g.white = :me THEN 'w' ELSE 'b' END AS color,
             CASE WHEN g.white = :me THEN g.white_delta ELSE g.black_delta END AS delta,
             o.name AS opponent
      FROM games g JOIN players o ON o.id = CASE WHEN g.white = :me THEN g.black ELSE g.white END
      WHERE g.white = :me OR g.black = :me
      ORDER BY g.ended_at DESC LIMIT 20`),
    rate: db.prepare("UPDATE players SET rating = rating + ?, wins = wins + ?, losses = losses + ?, draws = draws + ? WHERE id = ?"),
    insertGame: db.prepare(
      "INSERT INTO games (id, white, black, result, reason, time_control, white_delta, black_delta, pgn, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ),
  };

  const playerFor = (authorization) => {
    const token = /^Bearer (\S+)$/.exec(authorization ?? "")?.[1];
    return token ? sql.byToken.get(hashToken(token))?.id : undefined;
  };

  const publicProfile = (id) => {
    const p = sql.profile.get(id);
    return p ? { id: p.id, name: p.name, rating: p.rating } : { id, name: "Unknown", rating: 1200 };
  };

  // In-memory per-IP signup limiter
  const signups = new Map();
  function signupLimited(ip) {
    const now = Date.now();
    const recent = (signups.get(ip) ?? []).filter((t) => now - t < 3_600_000);
    recent.push(now);
    signups.set(ip, recent);
    return recent.length > signupsPerHour;
  }

  // ---- Live play state ---------------------------------------------------------------------

  const sockets = new Map(); // playerId → ws
  const activeGame = new Map(); // playerId → game
  const gamesById = new Map(); // gameId → game
  const queue = new Map(); // tc → Array<{ playerId, rating, enteredAt }>
  const rooms = new Map(); // code → { host, tc, createdAt }
  const spectatingGame = new Map(); // playerId → gameId

  const currentStats = () => {
    let seeking = 0;
    for (const list of queue.values()) seeking += list.length;
    return {
      online: sockets.size,
      activeGames: gamesById.size,
      seeking,
      openRooms: rooms.size,
    };
  };

  async function route(req, res) {
    const { pathname } = new URL(req.url, "http://localhost");
    if (req.method === "GET" && pathname === "/api/health") {
      return json(res, 200, { ok: true, timeControls: Object.keys(timeControls), ...currentStats() });
    }
    if (req.method === "GET" && pathname === "/api/stats") {
      return json(res, 200, currentStats());
    }
    if (req.method === "GET" && pathname === "/api/rooms") {
      const list = [];
      for (const [code, r] of rooms) {
        if (sockets.has(r.host)) {
          list.push({ code, host: r.hostProfile || publicProfile(r.host), tc: r.tc, createdAt: r.createdAt });
        }
      }
      return json(res, 200, list);
    }
    if (req.method === "GET" && pathname === "/api/games/live") {
      const list = [];
      for (const g of gamesById.values()) {
        if (!g.over) {
          list.push({
            id: g.id,
            tc: g.tc,
            white: g.players.w,
            black: g.players.b,
            movesCount: g.moves.length,
            startedAt: g.startedAt,
            spectatorsCount: g.spectators.size,
          });
        }
      }
      return json(res, 200, list);
    }
    if (req.method === "GET" && pathname === "/api/leaderboard") return json(res, 200, sql.leaderboard.all());
    if (req.method === "POST" && pathname === "/api/players") {
      if (signupLimited(req.socket.remoteAddress)) throw new HttpError(429, "Too many new players from this network, try later");
      const name = cleanName((await readJson(req)).name);
      const id = randomUUID();
      const token = randomBytes(32).toString("base64url");
      sql.insertPlayer.run(id, name, hashToken(token), Date.now());
      return json(res, 201, { ...sql.profile.get(id), token });
    }
    if (pathname === "/api/me" || pathname === "/api/me/games") {
      const me = playerFor(req.headers.authorization);
      if (!me) throw new HttpError(401, "Unknown player");
      if (req.method === "GET" && pathname === "/api/me") return json(res, 200, sql.profile.get(me));
      if (req.method === "GET") return json(res, 200, sql.myGames.all({ me }));
      if (req.method === "PUT" && pathname === "/api/me") {
        sql.rename.run(cleanName((await readJson(req)).name), me);
        return json(res, 200, sql.profile.get(me));
      }
    }
    throw new HttpError(404, "Not found");
  }

  const server = http.createServer((req, res) => {
    route(req, res).catch((e) => {
      if (!(e instanceof HttpError)) console.error(e);
      if (!res.headersSent) json(res, e.status ?? 500, { error: e instanceof HttpError ? e.message : "Server error" });
    });
  });

  // ---- Live play logic ---------------------------------------------------------------------

  const send = (playerId, msg) => {
    const ws = sockets.get(playerId);
    if (ws?.readyState === 1) ws.send(JSON.stringify(msg));
  };

  const colorOf = (g, playerId) => (g.white === playerId ? "w" : g.black === playerId ? "b" : "s");
  const other = (color) => (color === "w" ? "b" : "w");
  const remaining = (g, color) => (g.over || g.chess.turn() !== color ? g.clock[color] : g.clock[color] - (Date.now() - g.turnStart));

  function state(g, playerId) {
    const isPlayer = g.white === playerId || g.black === playerId;
    const color = colorOf(g, playerId);
    const opponent = isPlayer ? (color === "w" ? g.black : g.white) : null;
    return {
      id: g.id,
      tc: g.tc,
      color,
      white: g.players.w,
      black: g.players.b,
      moves: g.moves,
      turn: g.chess.turn(),
      clock: { w: Math.max(0, remaining(g, "w")), b: Math.max(0, remaining(g, "b")) },
      drawOffer: isPlayer ? g.drawOffer : null,
      opponentOnline: isPlayer ? sockets.has(opponent) : (sockets.has(g.white) && sockets.has(g.black)),
      over: g.over,
      spectatorsCount: g.spectators.size,
    };
  }

  const broadcast = (g) => {
    send(g.white, { t: "game", game: state(g, g.white) });
    send(g.black, { t: "game", game: state(g, g.black) });
    if (g.spectators.size > 0) {
      const spectatorPayload = JSON.stringify({ t: "game", game: state(g, "s") });
      for (const specId of g.spectators) {
        const ws = sockets.get(specId);
        if (ws?.readyState === 1) ws.send(spectatorPayload);
      }
    }
  };

  function leaveLobby(playerId) {
    for (const [tc, list] of queue) {
      const filtered = list.filter((e) => e.playerId !== playerId);
      if (filtered.length !== list.length) {
        if (filtered.length > 0) queue.set(tc, filtered);
        else queue.delete(tc);
      }
    }
    for (const [code, room] of rooms) {
      if (room.host === playerId) rooms.delete(code);
    }
  }

  function unspectate(playerId) {
    const gameId = spectatingGame.get(playerId);
    if (!gameId) return;
    spectatingGame.delete(playerId);
    const g = gamesById.get(gameId);
    if (g) {
      g.spectators.delete(playerId);
      broadcast(g);
    }
  }

  function tryMatch(tc) {
    while (true) {
      const list = queue.get(tc);
      if (!list || list.length < 2) return;

      // Prune stale seekers
      const valid = list.filter((e) => sockets.has(e.playerId) && !activeGame.has(e.playerId));
      if (valid.length !== list.length) {
        if (valid.length > 0) queue.set(tc, valid);
        else queue.delete(tc);
      }
      if (valid.length < 2) return;

      // Pair up: take the longest waiting player and pair with the closest rating opponent
      const first = valid[0];
      let bestIdx = 1;
      let minDiff = Math.abs(first.rating - valid[1].rating);
      for (let i = 2; i < valid.length; i++) {
        const diff = Math.abs(first.rating - valid[i].rating);
        if (diff < minDiff) {
          minDiff = diff;
          bestIdx = i;
        }
      }
      const second = valid[bestIdx];

      // Atomically remove both
      const remainingQueue = valid.filter((_, idx) => idx !== 0 && idx !== bestIdx);
      if (remainingQueue.length > 0) queue.set(tc, remainingQueue);
      else queue.delete(tc);

      startGame(first.playerId, second.playerId, tc);
    }
  }

  function startGame(a, b, tc) {
    [a, b].forEach(leaveLobby);
    unspectate(a);
    unspectate(b);
    const [white, black] = randomInt(2) ? [a, b] : [b, a];
    const [base, inc] = timeControls[tc];
    const g = {
      id: randomUUID(),
      tc,
      white,
      black,
      players: { w: publicProfile(white), b: publicProfile(black) },
      chess: new Chess(),
      moves: [],
      clock: { w: base, b: base },
      inc,
      turnStart: Date.now(),
      startedAt: Date.now(),
      drawOffer: null,
      over: null,
      spectators: new Set(),
    };
    activeGame.set(white, g);
    activeGame.set(black, g);
    gamesById.set(g.id, g);
    armFlag(g);
    broadcast(g);
  }

  // The side to move loses when its clock runs out; one timer per game, re-armed after every move.
  function armFlag(g) {
    clearTimeout(g.timer);
    const color = g.chess.turn();
    g.timer = setTimeout(() => {
      if (g.over) return;
      if (remaining(g, color) <= 0) finish(g, color === "w" ? "0-1" : "1-0", "timeout");
      else armFlag(g);
    }, remaining(g, color) + 20);
  }

  function finish(g, result, reason) {
    clearTimeout(g.timer);
    const turn = g.chess.turn();
    g.clock[turn] = Math.max(0, remaining(g, turn));
    const score = result === "1-0" ? 1 : result === "0-1" ? 0 : 0.5;
    const rw = sql.profile.get(g.white).rating;
    const rb = sql.profile.get(g.black).rating;
    const expected = 1 / (1 + 10 ** ((rb - rw) / 400));
    const dw = Math.round(K_FACTOR * (score - expected));
    const pgnChess = g.chess;
    pgnChess.setHeader("Event", "Gambit online");
    pgnChess.setHeader("White", g.players.w.name);
    pgnChess.setHeader("Black", g.players.b.name);
    pgnChess.setHeader("Result", result);
    pgnChess.setHeader("TimeControl", g.tc);
    pgnChess.setHeader("Termination", reason);
    db.exec("BEGIN");
    try {
      sql.rate.run(dw, +(score === 1), +(score === 0), +(score === 0.5), g.white);
      sql.rate.run(-dw, +(score === 0), +(score === 1), +(score === 0.5), g.black);
      sql.insertGame.run(g.id, g.white, g.black, result, reason, g.tc, dw, -dw, pgnChess.pgn(), Date.now());
      db.exec("COMMIT");
    } catch (e) {
      db.exec("ROLLBACK");
      throw e;
    }
    g.over = { result, reason, delta: { w: dw, b: -dw } };
    g.drawOffer = null;
    activeGame.delete(g.white);
    activeGame.delete(g.black);
    gamesById.delete(g.id);
    for (const specId of g.spectators) {
      spectatingGame.delete(specId);
    }
    broadcast(g);
  }

  function play(g, color, uci) {
    if (g.chess.turn() !== color) throw new Error("Not your turn");
    if (!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(uci ?? "")) throw new Error("Bad move");
    const now = Date.now();
    const left = remaining(g, color);
    if (left <= 0) return finish(g, color === "w" ? "0-1" : "1-0", "timeout");
    try {
      const moveObj = g.chess.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] });
      g.moves.push(moveObj.lan);
    } catch {
      throw new Error("Illegal move");
    }
    g.clock[color] = left + g.inc;
    g.turnStart = now;
    if (g.drawOffer === other(color)) g.drawOffer = null; // replying with a move declines the offer
    const c = g.chess;
    if (c.isCheckmate()) return finish(g, color === "w" ? "1-0" : "0-1", "checkmate");
    if (c.isStalemate()) return finish(g, "1/2-1/2", "stalemate");
    if (c.isInsufficientMaterial()) return finish(g, "1/2-1/2", "insufficient material");
    if (c.isThreefoldRepetition()) return finish(g, "1/2-1/2", "threefold repetition");
    if (c.isDrawByFiftyMoves()) return finish(g, "1/2-1/2", "fifty-move rule");
    armFlag(g);
    broadcast(g);
  }

  function handle(me, msg) {
    const g = activeGame.get(me);
    const inGame = () => {
      if (!g) throw new Error("You are not in a game");
      return colorOf(g, me);
    };
    switch (msg.t) {
      case "seek": {
        if (g) throw new Error("Finish your current game first");
        if (!timeControls[msg.tc]) throw new Error("Unknown time control");
        leaveLobby(me);
        unspectate(me);
        const myRating = sql.profile.get(me)?.rating ?? 1200;
        const list = queue.get(msg.tc) ?? [];
        list.push({ playerId: me, rating: myRating, enteredAt: Date.now() });
        queue.set(msg.tc, list);
        send(me, { t: "seeking", tc: msg.tc });
        tryMatch(msg.tc);
        return;
      }
      case "create": {
        if (g) throw new Error("Finish your current game first");
        if (!timeControls[msg.tc]) throw new Error("Unknown time control");
        leaveLobby(me);
        unspectate(me);
        let code;
        do code = Array.from({ length: 5 }, () => CODE_CHARS[randomInt(CODE_CHARS.length)]).join("");
        while (rooms.has(code));
        rooms.set(code, { host: me, hostProfile: publicProfile(me), tc: msg.tc, createdAt: Date.now() });
        return send(me, { t: "room", code, tc: msg.tc });
      }
      case "join": {
        if (g) throw new Error("Finish your current game first");
        const code = String(msg.code ?? "").trim().toUpperCase();
        const room = rooms.get(code);
        if (!room || !sockets.has(room.host)) throw new Error("Room not found");
        if (room.host === me) throw new Error("That's your own room — share the code with a friend");
        rooms.delete(code);
        return startGame(room.host, me, room.tc);
      }
      case "spectate": {
        if (g) throw new Error("Finish your current game first");
        leaveLobby(me);
        unspectate(me);
        const target = gamesById.get(msg.id);
        if (!target || target.over) throw new Error("Game not found or finished");
        target.spectators.add(me);
        spectatingGame.set(me, target.id);
        send(me, { t: "game", game: state(target, me) });
        return;
      }
      case "unspectate": {
        unspectate(me);
        return send(me, { t: "idle" });
      }
      case "react": {
        const emoji = String(msg.emoji ?? "");
        if (!ALLOWED_EMOJIS.has(emoji)) throw new Error("Invalid emoji");
        const targetGame = g || gamesById.get(spectatingGame.get(me));
        if (!targetGame) throw new Error("You are not in or watching a game");
        const senderProfile = publicProfile(me);
        const reactionMsg = { t: "reaction", from: senderProfile.name, emoji };
        send(targetGame.white, reactionMsg);
        send(targetGame.black, reactionMsg);
        for (const specId of targetGame.spectators) send(specId, reactionMsg);
        return;
      }
      case "cancel":
        leaveLobby(me);
        unspectate(me);
        return send(me, { t: "idle" });
      case "move": {
        const color = inGame();
        return play(g, color, msg.uci);
      }
      case "resign": {
        const color = inGame();
        return finish(g, color === "w" ? "0-1" : "1-0", "resignation");
      }
      case "draw": {
        const color = inGame();
        if (g.drawOffer === other(color)) return finish(g, "1/2-1/2", "agreement");
        g.drawOffer = color;
        return broadcast(g);
      }
      case "decline": {
        const color = inGame();
        if (g.drawOffer === other(color)) g.drawOffer = null;
        return broadcast(g);
      }
      default:
        throw new Error("Unknown message");
    }
  }

  const wss = new WebSocketServer({ noServer: true, maxPayload: 4096 });
  server.on("upgrade", (req, socket, head) => {
    const me = new URL(req.url, "http://localhost").pathname === "/ws" && playerFor(req.headers.authorization);
    if (!me) {
      socket.end("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => connect(ws, me));
  });

  function connect(ws, me) {
    sockets.get(me)?.close(4000, "Signed in on another device");
    sockets.set(me, ws);
    ws.alive = true;
    ws.on("pong", () => (ws.alive = true));
    ws.on("message", (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return ws.send(JSON.stringify({ t: "error", msg: "Invalid JSON" }));
      }
      try {
        handle(me, msg);
      } catch (e) {
        send(me, { t: "error", msg: e.message });
        const g = activeGame.get(me);
        if (g && msg.t === "move") send(me, { t: "game", game: state(g, me) }); // resync a rejected move
      }
    });
    ws.on("close", () => {
      if (sockets.get(me) !== ws) return; // replaced by a newer connection
      sockets.delete(me);
      leaveLobby(me);
      unspectate(me);
      const g = activeGame.get(me);
      if (g) broadcast(g); // opponent sees "offline"; the clock keeps running
    });
    const g = activeGame.get(me);
    ws.send(JSON.stringify({ t: "hello", me: sql.profile.get(me), game: g ? state(g, me) : null, stats: currentStats() }));
    if (g) broadcast(g);
  }

  // Drop half-open mobile connections so "opponent offline" is honest.
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.alive) ws.terminate();
      ws.alive = false;
      ws.ping();
    }
  }, 30_000);

  // Upgraded sockets keep http.Server#close waiting forever, so shut live play down first.
  const closeHttp = server.close.bind(server);
  server.close = (callback) => {
    clearInterval(heartbeat);
    for (const g of new Set(activeGame.values())) clearTimeout(g.timer);
    for (const ws of wss.clients) ws.terminate();
    closeHttp(() => {
      db.close();
      callback?.();
    });
    return server;
  };

  return server;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT) || 8080;
  createServer({ dbPath: process.env.DB_PATH || "gambit.db" }).listen(port, () => console.log(`Gambit server on :${port}`));
}
