import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import WebSocket from "ws";
import { createServer } from "./server.js";

let server, base;
before(async () => {
  server = createServer({ timeControls: { "5+0": [300_000, 0], bullet: [400, 0] }, signupsPerHour: 1000 }).listen(0);
  await once(server, "listening");
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const api = async (path, { token, ...init } = {}) => {
  const res = await fetch(base + path, {
    ...init,
    headers: { "Content-Type": "application/json", ...(token && { Authorization: `Bearer ${token}` }) },
  });
  return { status: res.status, body: await res.json() };
};
const signup = async (name) => (await api("/api/players", { method: "POST", body: JSON.stringify({ name }) })).body;

async function connect(token) {
  const ws = new WebSocket(base.replace("http", "ws") + "/ws", { headers: { Authorization: `Bearer ${token}` } });
  const inbox = [];
  const waiters = [];
  ws.on("message", (data) => {
    const msg = JSON.parse(data);
    const i = waiters.findIndex((w) => w.match(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
    else inbox.push(msg);
  });
  await once(ws, "open");
  return {
    ws,
    send: (msg) => ws.send(JSON.stringify(msg)),
    next(match = () => true) {
      const i = inbox.findIndex(match);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        waiters.push({ match, resolve });
        setTimeout(() => reject(new Error("no matching message")), 3000).unref();
      });
    },
  };
}
const gameWith = (pred) => (m) => m.t === "game" && pred(m.game);

/** Two connected players matched into a game; returns them as {white, black}. */
async function matched(tc = "5+0") {
  const [a, b] = [await signup("Alice"), await signup("Bob")];
  const [ca, cb] = [await connect(a.token), await connect(b.token)];
  ca.send({ t: "seek", tc });
  await ca.next((m) => m.t === "seeking");
  cb.send({ t: "seek", tc });
  const ga = (await ca.next((m) => m.t === "game")).game;
  await cb.next((m) => m.t === "game");
  const [white, black] = ga.color === "w" ? [ca, cb] : [cb, ca];
  white.profile = ga.color === "w" ? a : b;
  black.profile = ga.color === "w" ? b : a;
  return { white, black };
}

test("quick match to fool's mate updates ratings, history and leaderboard", async () => {
  const { white, black } = await matched();
  const moves = [[white, "f2f3"], [black, "e7e5"], [white, "g2g4"], [black, "d8h4"]];
  for (const [i, [side, uci]] of moves.entries()) {
    side.send({ t: "move", uci });
    await side.next(gameWith((g) => g.moves.length === i + 1));
  }
  const end = (await white.next(gameWith((g) => g.over))).game;
  assert.deepEqual(end.moves, ["f2f3", "e7e5", "g2g4", "d8h4"]);
  assert.deepEqual(end.over, { result: "0-1", reason: "checkmate", delta: { w: -16, b: 16 } });

  const me = (await api("/api/me", { token: black.profile.token })).body;
  assert.equal(me.rating, 1216);
  assert.equal(me.wins, 1);
  const games = (await api("/api/me/games", { token: black.profile.token })).body;
  assert.equal(games[0].opponent, white.profile.name);
  assert.equal(games[0].delta, 16);
  const board = (await api("/api/leaderboard")).body;
  assert.ok(board.some((p) => p.id === black.profile.id && p.rating === 1216));
  assert.ok(!("token_hash" in board[0]));
});

test("illegal and out-of-turn moves are rejected and resynced", async () => {
  const { white, black } = await matched();
  black.send({ t: "move", uci: "e7e5" });
  assert.equal((await black.next((m) => m.t === "error")).msg, "Not your turn");
  white.send({ t: "move", uci: "e2e5" });
  assert.equal((await white.next((m) => m.t === "error")).msg, "Illegal move");
  assert.deepEqual((await white.next((m) => m.t === "game")).game.moves, []);
  white.send({ t: "resign" });
  assert.equal((await black.next(gameWith((g) => g.over))).game.over.reason, "resignation");
});

test("private room, draw offer and acceptance", async () => {
  const [a, b] = [await signup("Host"), await signup("Guest")];
  const [host, guest] = [await connect(a.token), await connect(b.token)];
  host.send({ t: "create", tc: "5+0" });
  const { code } = await host.next((m) => m.t === "room");
  assert.match(code, /^[A-Z2-9]{5}$/);
  guest.send({ t: "join", code: code.toLowerCase() });
  const g = (await host.next((m) => m.t === "game")).game;
  const [w, bl] = g.color === "w" ? [host, guest] : [guest, host];
  w.send({ t: "draw" });
  assert.equal((await bl.next(gameWith((s) => s.drawOffer === "w"))).game.drawOffer, "w");
  bl.send({ t: "draw" });
  const end = (await w.next(gameWith((s) => s.over))).game;
  assert.equal(end.over.result, "1/2-1/2");
  assert.equal(end.over.reason, "agreement");
});

test("flag: the side to move loses on time", async () => {
  const { white, black } = await matched("bullet");
  const end = (await black.next(gameWith((g) => g.over))).game;
  assert.deepEqual(end.over.result, "0-1");
  assert.equal(end.over.reason, "timeout");
  assert.equal(end.clock.w, 0);
  white.ws.close();
});

test("reconnecting resumes the game and the opponent sees presence", async () => {
  const { white, black } = await matched();
  white.send({ t: "move", uci: "e2e4" });
  await black.next(gameWith((g) => g.moves.length === 1));
  black.ws.close();
  assert.equal((await white.next(gameWith((g) => !g.opponentOnline))).game.opponentOnline, false);
  const again = await connect(black.profile.token);
  const hello = await again.next((m) => m.t === "hello");
  assert.deepEqual(hello.game.moves, ["e2e4"]);
  assert.equal(hello.game.color, "b");
  await white.next(gameWith((g) => g.opponentOnline));
  again.send({ t: "resign" });
  await white.next(gameWith((g) => g.over));
});

test("auth and input validation", async () => {
  assert.equal((await api("/api/me")).status, 401);
  assert.equal((await api("/api/players", { method: "POST", body: JSON.stringify({ name: " x " }) })).status, 400);
  const p = await signup("Renamer");
  assert.equal((await api("/api/me", { method: "PUT", token: p.token, body: JSON.stringify({ name: "Renamed" }) })).body.name, "Renamed");
  const ws = new WebSocket(base.replace("http", "ws") + "/ws", { headers: { Authorization: "Bearer nope" } });
  const [err] = await once(ws, "error");
  assert.match(err.message, /401/);
  const c = await connect(p.token);
  c.send({ t: "join", code: "ZZZZZ" });
  assert.equal((await c.next((m) => m.t === "error")).msg, "Room not found");
  c.ws.close();
});
