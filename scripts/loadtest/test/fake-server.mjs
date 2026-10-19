// A fake of the parts of Lapis Cloud that the load-test tool talks to: login, logout and the encounter-space RPC.
// It is used by the tests, by `selftest` and by `--dry-run`. It enforces the server limits of EncounterSpaceService
// (list 60/min, enter 30/min, leave 30/min, seat 1/s, table 1/2 s, tableToken 20/min) and, optionally, the rule of
// EncounterSpacePoller: presence of people who are not connected to LiveKit is deleted (older than 120 s, absent in two sweeps).
//
// Two adapters: fetch(url, init) answers in-process (no socket), listen() serves the same handler on 127.0.0.1.
import http from 'node:http';
import { METHODS } from '../lib/routes.mjs';
import { FakeMediaHub } from '../lib/media/fake.mjs';

const PKG = 'network.lapis.cloud.shared.rpc.';
const NAME_OF = Object.fromEntries(Object.entries(METHODS).map(([name, i]) => [i, name]));
const RPC_PATH = /^\/rpc\/routeEncounterSpaceServiceManager(\d+)$/;

class Refusal extends Error {
  constructor(type, message) {
    super(message);
    this.type = type;
  }
}

class SlidingWindow {
  #hits = new Map();
  constructor(clock, max, windowMs) {
    this.clock = clock;
    this.max = max;
    this.windowMs = windowMs;
  }
  allow(key) {
    const now = this.clock.now();
    const list = (this.#hits.get(key) ?? []).filter((t) => now - t < this.windowMs);
    if (list.length >= this.max) {
      this.#hits.set(key, list);
      return false;
    }
    list.push(now);
    this.#hits.set(key, list);
    return true;
  }
}

export function createFakeServer({
  clock,
  hub = new FakeMediaHub(),
  accounts = 40,
  password = 'fake-password',
  emailDomain = 'example.test',
  spaceId = '00000000-0000-4000-8000-0000000000a1',
  tables = { count: 4, seats: 6 },
  namePrefix = 'LT-',
  presenceRequiresMedia = false,
  foreignPresent = false,
  tableServerUrl = null, // serverUrl handed out ONLY by the tableToken answer (not by joinTable)
  wrongNameFor = new Set(), // account numbers whose display name has no test prefix
  failLoginFor = new Set(), // account numbers that cannot log in
  serverErrorMethods = new Set(), // RPC method names answered with HTTP 500
  slowMethods = new Map(), // RPC method name -> delay in real ms
  canaries = {},
} = {}) {
  const c = {
    cookie: canaries.cookie ?? 'COOKIE-',
    token: canaries.token ?? 'JOIN-TOKEN-',
    tableToken: canaries.tableToken ?? 'TABLE-TOKEN-',
    memberId: canaries.memberId ?? 'member-id-',
    errorText: canaries.errorText ?? 'error-text',
    body: canaries.body ?? 'response-body',
  };
  const limits = {
    list: new SlidingWindow(clock, 60, 60_000),
    enter: new SlidingWindow(clock, 30, 60_000),
    leave: new SlidingWindow(clock, 30, 60_000),
    seat: new SlidingWindow(clock, 1, 1000),
    table: new SlidingWindow(clock, 1, 2000),
    token: new SlidingWindow(clock, 20, 60_000),
  };
  const sessions = new Map(); // cookie value -> account number
  const present = new Map(); // account number -> {enteredAt, absent, seat, table, tableSeat}
  const tableSeats = Array.from({ length: tables.count }, () => ({ gen: 0, seats: new Map() })); // seat -> account
  const stats = { rpcCalls: 0, logins: 0, logouts: 0, rotations: 0, sweeps: 0, deletedBySweep: 0, rejected: {} };
  let counter = 0;
  let lastSweep = 0;

  const nn = (n) => String(n).padStart(2, '0');
  const identityOf = (n) => `identity-${n}`;
  const nameOf = (n) => (wrongNameFor.has(n) ? `Fremd ${nn(n)}` : `${namePrefix}${nn(n)}`);

  const rate = (group, n) => {
    if (!limits[group].allow(n)) {
      stats.rejected[group] = (stats.rejected[group] ?? 0) + 1;
      throw new Refusal(['seat', 'table', 'token'].includes(group) ? 'ServiceBusy' : 'Conflict', 'Too many requests');
    }
  };

  function rotate(t, exceptAccount) {
    const table = tableSeats[t];
    table.gen += 1;
    stats.rotations += 1;
    for (const other of table.seats.values()) if (other !== exceptAccount) hub.notifyRotation(identityOf(other));
  }
  function leaveTableSeat(n) {
    const p = present.get(n);
    if (!p || p.table === null) return;
    const t = p.table;
    tableSeats[t].seats.delete(p.tableSeat);
    p.table = null;
    p.tableSeat = null;
    rotate(t, n);
  }
  function drop(n) {
    leaveTableSeat(n);
    present.delete(n);
  }
  function sweep() {
    if (!presenceRequiresMedia) return;
    while (clock.now() - lastSweep >= 60_000) {
      lastSweep += 60_000;
      stats.sweeps += 1;
      for (const [n, p] of [...present]) {
        if (clock.now() - p.enteredAt < 120_000) continue;
        if (hub.isConnected(identityOf(n))) p.absent = 0;
        else if (++p.absent >= 2) {
          stats.deletedBySweep += 1;
          drop(n);
        }
      }
    }
  }

  const joinDto = (n, room, token, extra = {}) => ({
    roomId: 'room-0',
    livekitRoomName: room,
    serverUrl: 'wss://livekit.example.test/?auth=canary',
    token: `${token}${n}`,
    identity: identityOf(n),
    displayName: nameOf(n),
    role: 'PARTICIPANT',
    expiresAt: '2026-10-17T12:00:00',
    turnServers: [{ urls: ['turn:turn.example.test:3478'], username: `turn-user-${n}`, credential: `TURN-CREDENTIAL-${n}` }],
    ...extra,
  });
  const tableToken = (n, t, seat) => ({ join: joinDto(n, `table-${t}-g${tableSeats[t].gen}`, c.tableToken), canPublish: true, table: t, tableSeat: seat });
  const presentList = () => [
    ...[...present].map(([n, p]) => ({ memberId: `${c.memberId}${n}`, displayName: nameOf(n), role: 'CONGREGATION', isGuest: false, seat: p.seat, table: p.table, tableSeat: p.tableSeat })),
    // true = one foreign name, a number = that many
    ...Array.from({ length: foreignPresent === true ? 1 : Number(foreignPresent) || 0 }, (_, i) => ({ memberId: `${c.memberId}x${i}`, displayName: `Fremde Person ${i}`, role: 'PULPIT', isGuest: false })),
  ];

  const handlers = {
    enterSpace(n, [id]) {
      rate('enter', n);
      if (id !== spaceId) throw new Refusal('NotFound', 'Unknown space');
      if (!present.has(n)) present.set(n, { enteredAt: clock.now(), absent: 0, seat: null, table: null, tableSeat: null });
      return { join: joinDto(n, 'plenum-0', c.token), presenceRole: 'CONGREGATION', canPublish: false, canPublishData: true };
    },
    leaveSpace(n) {
      rate('leave', n);
      drop(n);
      return {};
    },
    listPresent(n) {
      rate('list', n);
      if (!present.has(n)) throw new Refusal('Forbidden', 'Not authorized');
      return presentList();
    },
    selectSeat(n, [, seat]) {
      rate('seat', n);
      const p = present.get(n);
      if (!p) throw new Refusal('Forbidden', 'Not authorized');
      if (seat !== null && (seat < 0 || seat >= 240)) throw new Refusal('BadRequest', 'Seat out of range');
      if (seat !== null && [...present].some(([m, q]) => m !== n && q.seat === seat)) throw new Refusal('Conflict', 'Seat taken');
      p.seat = seat;
      if (seat !== null) leaveTableSeat(n);
      return presentList();
    },
    joinTable(n, [, t, seat]) {
      rate('table', n);
      const p = present.get(n);
      if (!p) throw new Refusal('Forbidden', 'Not authorized');
      if (!(t >= 0 && t < tables.count && seat >= 0 && seat < tables.seats)) throw new Refusal('BadRequest', 'Table or seat out of range');
      const holder = tableSeats[t].seats.get(seat);
      if (holder !== undefined && holder !== n) throw new Refusal('Conflict', 'Seat taken');
      leaveTableSeat(n);
      p.seat = null;
      p.table = t;
      p.tableSeat = seat;
      tableSeats[t].seats.set(seat, n);
      rotate(t, n);
      return tableToken(n, t, seat);
    },
    leaveTable(n) {
      rate('table', n);
      leaveTableSeat(n);
      return {};
    },
    tableToken(n) {
      rate('token', n);
      const p = present.get(n);
      if (!p || p.table === null) return { token: null };
      const dto = tableToken(n, p.table, p.tableSeat);
      if (tableServerUrl) dto.join.serverUrl = tableServerUrl;
      return { token: dto };
    },
    listTables(n) {
      rate('list', n);
      if (!present.has(n)) throw new Refusal('Forbidden', 'Not authorized');
      return tableSeats.map((t, i) => ({ table: i, seats: tables.seats, occupiedSeats: [...t.seats.keys()].sort((a, b) => a - b), quieted: false }));
    },
  };

  const rpcBody = (id, result) => JSON.stringify({ id, result: JSON.stringify(result), error: null, exceptionType: null, exceptionJson: null, jsonrpc: '2.0' });
  const rpcRefusal = (id, type, message) =>
    JSON.stringify({
      id,
      result: null,
      error: `${message} ${c.errorText}`,
      exceptionType: `${PKG}${type}Exception`,
      exceptionJson: JSON.stringify({ type: `${PKG}${type}Exception`, message }),
      jsonrpc: '2.0',
    });

  /** The whole server as a function: {method, url, headers:{cookie}, body} -> {status, setCookie:[], body}. */
  async function handle({ url, headers = {}, body = '' }) {
    const path = new URL(url, 'http://127.0.0.1').pathname;
    sweep();
    if (path === '/api/auth/login') {
      const { email, password: given } = JSON.parse(body);
      const m = new RegExp(`^lt-(\\d+)@${emailDomain.replace('.', '\\.')}$`).exec(email ?? '');
      const n = m ? Number(m[1]) : 0;
      if (!(n >= 1 && n <= accounts) || given !== password || failLoginFor.has(n)) {
        return { status: 401, setCookie: [], body: JSON.stringify({ error: `bad credentials ${c.body}`, echo: given }) };
      }
      stats.logins += 1;
      const cookie = `${c.cookie}${n}-${++counter}`;
      sessions.set(cookie, n);
      return { status: 200, setCookie: [`lapis_session=${cookie}; Path=/; HttpOnly`], body: JSON.stringify({ memberId: `${c.memberId}${n}`, displayName: nameOf(n), role: 'MEMBER' }) };
    }
    if (path === '/api/auth/logout') {
      const cookie = /lapis_session=([^;]+)/.exec(headers.cookie ?? '')?.[1];
      if (cookie && sessions.delete(cookie)) stats.logouts += 1;
      return { status: 204, setCookie: ['lapis_session=; Path=/; Max-Age=0'], body: '' };
    }
    const rpc = RPC_PATH.exec(path);
    if (!rpc) return { status: 404, setCookie: [], body: '' };
    stats.rpcCalls += 1;
    const request = JSON.parse(body);
    const method = NAME_OF[Number(rpc[1])];
    if (slowMethods.has(method)) await new Promise((r) => setTimeout(r, slowMethods.get(method)));
    if (serverErrorMethods.has(method)) return { status: 500, setCookie: [], body: `internal error ${c.body}` };
    const cookie = /lapis_session=([^;]+)/.exec(headers.cookie ?? '')?.[1];
    const n = cookie ? sessions.get(cookie) : undefined;
    if (n === undefined) return { status: 200, setCookie: [], body: rpcRefusal(request.id, 'Unauthenticated', 'Missing, invalid, or expired session') };
    const params = (request.params ?? []).map((p) => JSON.parse(p));
    try {
      if (!handlers[method]) throw new Refusal('BadRequest', 'Unknown method');
      return { status: 200, setCookie: [], body: rpcBody(request.id, handlers[method](n, params)) };
    } catch (e) {
      if (e instanceof Refusal) return { status: 200, setCookie: [], body: rpcRefusal(request.id, e.type, e.message) };
      throw e;
    }
  }

  function fetchImpl(url, init = {}) {
    return handle({ url: String(url), headers: init.headers ?? {}, body: init.body ?? '' }).then(({ status, setCookie, body }) => {
      const headers = new Headers({ 'content-type': 'application/json' });
      for (const line of setCookie) headers.append('set-cookie', line);
      return new Response(status === 204 ? null : body, { status, headers });
    });
  }

  function listen() {
    const server = http.createServer(async (req, res) => {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      try {
        const r = await handle({ url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
        if (r.setCookie.length) res.setHeader('set-cookie', r.setCookie);
        res.writeHead(r.status, { 'content-type': 'application/json' });
        res.end(r.body);
      } catch {
        res.writeHead(500).end();
      }
    });
    return new Promise((resolve) => {
      server.listen(0, '127.0.0.1', () =>
        resolve({
          url: `http://127.0.0.1:${server.address().port}`,
          close: () => new Promise((r) => server.close(r)),
        }),
      );
    });
  }

  return {
    hub,
    spaceId,
    password,
    emailDomain,
    handle,
    fetch: fetchImpl,
    listen,
    stats,
    canaries: c,
    presentCount: () => present.size,
    tableOccupants: () => tableSeats.reduce((sum, t) => sum + t.seats.size, 0),
    openSessions: () => sessions.size,
    forgetSession: (n) => {
      for (const [cookie, who] of sessions) if (who === n) sessions.delete(cookie);
    },
  };
}
