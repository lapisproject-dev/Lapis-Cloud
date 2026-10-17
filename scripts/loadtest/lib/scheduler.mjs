// Time, pacing and randomness. All scenario times are VIRTUAL milliseconds: scale 1 is real time, scale N compresses
// time by N (tests and dry runs). now() is derived from the real clock, and sleepUntil() never returns early, so a
// component that waits until T and a component that checks "now >= T" always agree (the fake server uses the same clock).

export class Clock {
  #origin;
  #scale;
  #real;
  constructor({ scale = 1, real = () => performance.now() } = {}) {
    this.#scale = scale;
    this.#real = real;
    this.#origin = real();
  }
  get scale() {
    return this.#scale;
  }
  now() {
    return (this.#real() - this.#origin) * this.#scale;
  }
  /** Waits until virtual time `t` (never earlier). Resolves false if `signal` aborted first. */
  async sleepUntil(t, signal) {
    for (;;) {
      if (signal?.aborted) return false;
      const remaining = t - this.now();
      if (remaining <= 0) return true;
      await new Promise((resolve) => {
        const timer = setTimeout(done, Math.max(1, Math.min(remaining / this.#scale, 1000)));
        function done() {
          signal?.removeEventListener('abort', done);
          clearTimeout(timer);
          resolve();
        }
        signal?.addEventListener('abort', done, { once: true });
      });
    }
  }
  sleep(ms, signal) {
    return this.sleepUntil(this.now() + ms, signal);
  }
}

/**
 * A discrete-event clock for runs against an in-process fake (no sockets): time stands still while code runs and jumps to the
 * next sleeper once the event loop is idle. Deterministic, as fast as the CPU allows, and the fake server reads the same clock,
 * so a request is judged at EXACTLY the virtual time at which the tool sent it.
 */
export class VirtualClock {
  #now = 0;
  #queue = [];
  #pending = false;
  #seq = 0;
  get scale() {
    return Infinity;
  }
  now() {
    return this.#now;
  }
  sleepUntil(t, signal) {
    if (signal?.aborted) return Promise.resolve(false);
    if (t <= this.#now) return Promise.resolve(true);
    return new Promise((resolve) => {
      const entry = { t, seq: this.#seq++, resolve: null };
      const onAbort = () => {
        const i = this.#queue.indexOf(entry);
        if (i >= 0) this.#queue.splice(i, 1);
        resolve(false);
      };
      entry.resolve = () => {
        signal?.removeEventListener('abort', onAbort);
        resolve(true);
      };
      signal?.addEventListener('abort', onAbort, { once: true });
      this.#queue.push(entry);
      this.#queue.sort((a, b) => a.t - b.t || a.seq - b.seq);
      this.#arm();
    });
  }
  sleep(ms, signal) {
    return this.sleepUntil(this.#now + ms, signal);
  }
  #arm() {
    if (this.#pending || this.#queue.length === 0) return;
    this.#pending = true;
    setImmediate(() => {
      this.#pending = false;
      const next = this.#queue.shift();
      if (next) {
        this.#now = Math.max(this.#now, next.t);
        next.resolve();
      }
      this.#arm();
    });
  }
}

/** Spaces all requests of the whole run: at most `rps` per (virtual) second. */
export class TokenBucket {
  #clock;
  #interval;
  #next = 0;
  constructor(clock, rps) {
    this.#clock = clock;
    this.#interval = 1000 / rps;
  }
  async acquire(signal) {
    const slot = Math.max(this.#next, this.#clock.now());
    this.#next = slot + this.#interval;
    return this.#clock.sleepUntil(slot, signal);
  }
}

// Client side spacing per participant, a little ABOVE the server limits (EncounterSpaceService / Application.kt):
// list 60/min, enter 30/min, leave 30/min, seat 1/s, table 1/2 s (join and leave share it), tableToken 20/min.
export const THROTTLE_MS = Object.freeze({ list: 1100, enter: 2100, leave: 2100, seat: 1100, table: 2200, token: 3100 });

const GROUP_OF = Object.freeze({
  listSpaces: 'list',
  getSpace: 'list',
  getEntryInfo: 'list',
  listPresent: 'list',
  listTables: 'list',
  enterSpace: 'enter',
  leaveSpace: 'leave',
  selectSeat: 'seat',
  joinTable: 'table',
  leaveTable: 'table',
  tableToken: 'token',
});

export function throttleGroup(method) {
  return GROUP_OF[method] ?? null;
}

export class ClientThrottle {
  #clock;
  #next = new Map();
  #bypass;
  constructor(clock, { bypass = false } = {}) {
    this.#clock = clock;
    this.#bypass = bypass;
  }
  /** Waits until this participant may send the next call of the method's group. Resolves false if aborted. */
  async wait(method, signal) {
    const group = GROUP_OF[method];
    if (this.#bypass || !group) return !signal?.aborted;
    return this.#clock.sleepUntil(this.#next.get(group) ?? 0, signal);
  }
  /** Call this at the moment the request really leaves: the spacing counts from the send, not from the wish. */
  sent(method) {
    const group = GROUP_OF[method];
    if (!this.#bypass && group) this.#next.set(group, this.#clock.now() + THROTTLE_MS[group]);
  }
}

/** mulberry32: small, seedable, good enough for scenario jitter. */
export function createRng(seed) {
  let a = (Math.floor(seed) >>> 0) || 1;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    next,
    between: (lo, hi) => lo + next() * (hi - lo),
    int: (lo, hiExclusive) => lo + Math.floor(next() * (hiExclusive - lo)),
    pick: (arr) => arr[Math.floor(next() * arr.length)],
  };
}
