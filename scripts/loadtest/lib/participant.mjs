// One simulated participant: a logged-in test account that sits in the room like a person would.
// State machine (also drawn in docs/architecture/encounter-load-test.adoc):
//   new -> present -> (bench seat) -> atTable <-> atTable (switch) -> present -> left
//   any state -> lost (presence deleted by the server) -> present again by re-entering
//   any state -> aborted (a guard fired: this participant makes NO further request)
import { CookieJar } from './http.mjs';
import { KiluaClient } from './kilua.mjs';
import { login, logout } from './auth.mjs';
import { emailFor } from './config.mjs';
import { ClientThrottle } from './scheduler.mjs';
import { assertLiveKitAllowed, assertTestIdentity, GuardError } from './guard.mjs';

export const REACTION_TOPIC = 'lapis-encounter-reaction';
export const CHAT_TOPIC = 'lapis-chat';
const encoder = new TextEncoder();

/** Who turned a microphone on and when, so that the listeners' "heard it" can be turned into a delay. In memory only. */
export class AudibleBoard {
  #episodes = new Map();
  #clock;
  constructor(clock) {
    this.#clock = clock;
  }
  micOn(identity) {
    this.#episodes.set(identity, { t0: this.#clock.now(), heardBy: new Set() });
  }
  micOff(identity) {
    this.#episodes.delete(identity);
  }
  /** Milliseconds from the microphone going on to this listener hearing it, once per listener and episode; otherwise null. */
  heard(speaker, listener) {
    const episode = this.#episodes.get(speaker);
    if (!episode || episode.heardBy.has(listener)) return null;
    episode.heardBy.add(listener);
    return this.#clock.now() - episode.t0;
  }
}

export class SimParticipant {
  idx;
  state = 'new';
  #cfg;
  #d;
  #jar;
  #kilua;
  #throttle;
  #media;
  #identity = null;
  #table = null; // {table, seat}
  #needsReenter = false;
  #micOn = false; // the person wants the microphone on: re-applied after every table (re)connect, like a browser would
  #refreshing = false;
  #rotationSince = null;
  #chatCount = 0;
  #lock = Promise.resolve();

  /** deps: clock, recorder, bucket, fetchImpl, secrets, board, rng, signal, onFatal, baseUrl, media, relay */
  constructor({ idx, cfg, deps }) {
    this.idx = idx;
    this.#cfg = cfg;
    this.#d = deps;
    this.#jar = new CookieJar({ onSet: (v) => deps.secrets.add(v) });
    this.#kilua = new KiluaClient({
      baseUrl: deps.baseUrl,
      jar: this.#jar,
      fetchImpl: deps.fetchImpl,
      recorder: deps.recorder,
      participantIdx: idx,
      secrets: deps.secrets,
    });
    this.#throttle = new ClientThrottle(deps.clock, { bypass: cfg.unsafeIgnoreClientThrottle });
    this.#media = deps.media;
    this.#media.onAudible(({ speakerIdentity }) => {
      const ms = deps.board.heard(speakerIdentity, this.#identity);
      if (ms !== null) deps.recorder.mark('heard', idx, ms);
    });
    this.#media.onDisconnect((which, reason) => this.#onMediaDisconnect(which, reason));
  }

  get isAborted() {
    return this.state === 'aborted';
  }
  get isPresent() {
    return this.state === 'present' || this.state === 'atTable';
  }

  // ---- plumbing -------------------------------------------------------------------------------------------------------

  /**
   * One RPC through the client throttle and the global rate. The calls of ONE participant are strictly serialized, so the throttle
   * spacing is measured from the moment each request really leaves. `final` calls (clean-up) ignore the abort signal.
   */
  async #rpc(method, params, { final = false } = {}) {
    if (this.isAborted) return { ok: false, kind: 'aborted' };
    const previous = this.#lock;
    let release;
    this.#lock = new Promise((resolve) => (release = resolve));
    await previous;
    try {
      const signal = final ? undefined : this.#d.signal;
      if (!(await this.#throttle.wait(method, signal))) return { ok: false, kind: 'aborted' };
      if (!(await this.#d.bucket.acquire(signal))) return { ok: false, kind: 'aborted' };
      this.#throttle.sent(method);
      const result = await this.#kilua.call(method, params);
      if (!result.ok && result.kind === 'exception' && result.exceptionType === 'Forbidden' && !['enterSpace', 'leaveSpace', 'leaveTable'].includes(method)) {
        // the server no longer knows us as present (EncounterSpacePoller, removal, session closed)
        this.#d.recorder.mark('presence_lost', this.idx);
        this.#needsReenter = true;
        this.state = 'lost';
        this.#table = null;
        this.#micOn = false;
        // the table connection (and its tone pump) must not outlive the seat the server just forgot
        await this.#media.disconnectTable().catch(() => {});
      }
      return result;
    } finally {
      release();
    }
  }

  #abort(guardError) {
    this.state = 'aborted';
    this.#jar.clear();
    this.#d.onFatal(`participant-${guardError.code}`, { p: this.idx });
  }

  async #login() {
    if (this.#jar.has('lapis_session')) return true;
    if (!(await this.#d.bucket.acquire(this.#d.signal))) return false;
    const email = emailFor(this.#cfg, this.idx);
    const r = await login({
      baseUrl: this.#d.baseUrl,
      jar: this.#jar,
      fetchImpl: this.#d.fetchImpl,
      recorder: this.#d.recorder,
      idx: this.idx,
      email,
      password: this.#cfg.password,
    });
    return r.ok;
  }

  // ---- steps ----------------------------------------------------------------------------------------------------------

  async exec(step) {
    if (this.isAborted || this.#d.signal.aborted) return;
    if (this.#needsReenter && !['enter', 'leave'].includes(step.op)) await this.enter();
    switch (step.op) {
      case 'enter':
        return this.enter();
      case 'seat':
        return this.seat();
      case 'table':
        return this.joinAnyTable();
      case 'leaveTable':
        return this.leaveTable();
      case 'micOn':
        return this.mic(true);
      case 'micOff':
        return this.mic(false);
      case 'react':
        return this.#d.media.publishData(REACTION_TOPIC, encoder.encode(JSON.stringify({ r: 'APPLAUSE' })));
      case 'chat':
        return this.chat();
      case 'watch':
        return this.watch();
      case 'leave':
        return this.leave();
      default:
        throw new Error('unknown step');
    }
  }

  async enter() {
    if (this.isPresent) return;
    if (!(await this.#login())) {
      this.#d.recorder.mark('login_failed', this.idx);
      return;
    }
    const r = await this.#rpc('enterSpace', [this.#cfg.spaceId, null]);
    if (!r.ok) return;
    const join = r.value.join;
    try {
      assertTestIdentity(join.displayName, this.#cfg.namePrefix);
      if (this.#cfg.media !== 'none') assertLiveKitAllowed(join.serverUrl, this.#cfg);
    } catch (e) {
      if (e instanceof GuardError) return this.#abort(e);
      throw e;
    }
    this.#identity = join.identity;
    this.#needsReenter = false;
    this.state = 'present'; // the server knows us from here on, so clean-up must leave even if the media connection fails
    await this.#media.connect(join);
  }

  async seat() {
    if (!this.isPresent) return;
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await this.#rpc('selectSeat', [this.#cfg.spaceId, this.#d.rng.int(0, 24)]);
      if (r.ok || r.kind !== 'exception' || r.exceptionType !== 'Conflict') return; // taken -> try another one
    }
  }

  async joinAnyTable() {
    if (!this.isPresent) return;
    for (let attempt = 0; attempt < 3 && this.isPresent; attempt++) {
      const tables = await this.#rpc('listTables', [this.#cfg.spaceId]);
      if (!tables.ok) return;
      const choice = this.#pickTable(tables.value);
      if (!choice) return; // every table is full
      const started = this.#d.clock.now();
      const r = await this.#rpc('joinTable', [this.#cfg.spaceId, choice.table, choice.seat]);
      if (r.ok) {
        try {
          if (this.#cfg.media !== 'none') assertLiveKitAllowed(r.value.join.serverUrl, this.#cfg);
        } catch (e) {
          if (e instanceof GuardError) return this.#abort(e);
          throw e;
        }
        this.#table = { table: choice.table, seat: choice.seat };
        this.state = 'atTable';
        await this.#connectTableMedia(r.value);
        this.#d.recorder.mark('table_assigned', this.idx, this.#d.clock.now() - started);
        return;
      }
      if (!(r.kind === 'exception' && r.exceptionType === 'Conflict')) return;
    }
  }

  /** Connects the table media and puts the microphone back on if the person had it on (a reconnect silences it in the media layer). */
  async #connectTableMedia(tokenDto) {
    await this.#media.connectTable(tokenDto);
    if (this.#micOn && this.state === 'atTable') {
      this.#d.board.micOn(this.#identity); // a new episode: "heard" now measures the delay from the re-publish, not from the first mic-on
      await this.#media.setMic(true);
    }
  }

  #pickTable(tables) {
    const free = [];
    for (const t of tables) {
      if (this.#table && t.table === this.#table.table) continue; // a switch means another table
      const seats = [];
      for (let s = 0; s < t.seats; s++) if (!t.occupiedSeats.includes(s)) seats.push(s);
      if (seats.length) free.push({ table: t.table, seat: this.#d.rng.pick(seats) });
    }
    return free.length ? this.#d.rng.pick(free) : null;
  }

  async leaveTable({ final = false } = {}) {
    if (this.state !== 'atTable') return;
    await this.#rpc('leaveTable', [this.#cfg.spaceId], { final });
    this.#micOn = false;
    await this.#media.disconnectTable();
    this.#table = null;
    if (this.state === 'atTable') this.state = 'present';
  }

  async mic(on) {
    if (this.state !== 'atTable') return;
    this.#micOn = on;
    if (on) this.#d.board.micOn(this.#identity);
    else this.#d.board.micOff(this.#identity);
    await this.#media.setMic(on);
  }

  async chat() {
    this.#chatCount += 1;
    const message = { senderMemberId: 'lt-sim', senderDisplayName: 'LT-sim', text: `[LT] synthetic message ${this.#chatCount}`, sentAtEpochMs: Date.now() };
    await this.#media.publishData(CHAT_TOPIC, encoder.encode(JSON.stringify(message)));
  }

  /** The foreign-presence check: only the NUMBER of names without the test prefix is looked at, nothing is kept. */
  async watch() {
    if (!this.isPresent) return;
    const r = await this.#rpc('listPresent', [this.#cfg.spaceId]);
    if (!r.ok) return;
    const foreign = r.value.filter((x) => !String(x.displayName).startsWith(this.#cfg.namePrefix)).length;
    if (foreign > 0) {
      this.#d.recorder.mark('foreign_presence', this.idx, foreign);
      if (this.#cfg.abortOnForeignPresence) this.#d.onFatal('foreign-presence', { count: foreign });
    }
  }

  async leave({ final = false } = {}) {
    if (this.state === 'new' || this.state === 'left' || this.isAborted) return;
    await this.leaveTable({ final });
    if (this.isPresent || this.state === 'lost') await this.#rpc('leaveSpace', [this.#cfg.spaceId], { final });
    await this.#media.close();
    this.#needsReenter = false;
    this.state = 'left';
  }

  /** Clean-up at the end or on abort: leave table and room, close the media, log out. Never throws. */
  async cleanup() {
    try {
      if (!this.isAborted) {
        await this.leave({ final: true });
        if (this.#jar.has('lapis_session')) {
          await logout({ baseUrl: this.#d.baseUrl, jar: this.#jar, fetchImpl: this.#d.fetchImpl, recorder: this.#d.recorder, idx: this.idx });
        }
      }
    } catch {
      /* clean-up is best effort; the failure is already in the samples */
    }
    await this.#media.close().catch(() => {});
  }

  // ---- table rotation ---------------------------------------------------------------------------------------------------

  #onMediaDisconnect(which, reason) {
    if (this.isAborted || this.#d.signal.aborted) return;
    if (which === 'table' && this.state === 'atTable') {
      this.#d.recorder.mark('rotation', this.idx);
      this.#rotationSince ??= this.#d.clock.now();
      this.#refreshToken().catch(() => this.#d.recorder.mark('table_lost', this.idx));
    } else {
      this.#d.recorder.mark(`disconnect:${reason}`, this.idx);
    }
  }

  /** Asks for a fresh table token after a rotation. Rotations that arrive meanwhile are merged into the next ask. */
  async #refreshToken() {
    if (this.#refreshing) return;
    this.#refreshing = true;
    try {
      while (this.#rotationSince !== null && this.state === 'atTable' && !this.#d.signal.aborted) {
        const since = this.#rotationSince;
        this.#rotationSince = null;
        const askedAt = this.#table;
        for (let tries = 0; tries < 4; tries++) {
          const r = await this.#rpc('tableToken', [this.#cfg.spaceId]);
          // leaveTable / leave / a switch may have run while the ask was queued: the answer then belongs to a seat that is gone
          if (this.state !== 'atTable' || this.#table !== askedAt) break;
          if (r.ok) {
            if (r.value.token) {
              try {
                if (this.#cfg.media !== 'none') assertLiveKitAllowed(r.value.token.join.serverUrl, this.#cfg);
              } catch (e) {
                if (e instanceof GuardError) return this.#abort(e);
                throw e;
              }
              await this.#connectTableMedia(r.value.token);
              this.#d.recorder.mark('rotation_token', this.idx, this.#d.clock.now() - since);
            } else {
              this.#d.recorder.mark('table_lost', this.idx);
              this.#table = null;
              this.#micOn = false;
              if (this.state === 'atTable') this.state = 'present';
            }
            break;
          }
          // The seat changed while the token was minted (Conflict) or a limit answered: wait a little and ask again.
          if (r.kind === 'aborted' || tries === 3) break;
          await this.#d.clock.sleep(500 * 2 ** tries, this.#d.signal);
        }
      }
    } finally {
      this.#refreshing = false;
    }
  }
}
