import { dryRun } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AUDIBLE_RMS, rms, sineFrames } from '../lib/tone.mjs';
import { createLiveKitMedia, MediaUnavailableError } from '../lib/media/livekit.mjs';
import { createMediaFactory } from '../lib/media/index.mjs';
import { startRun } from '../lib/runner.mjs';
import { parseConfig, withOverrides } from '../lib/config.mjs';
import { GuardError } from '../lib/guard.mjs';

test('the test tone is a pure 440 Hz sine, phase continuous, clearly above the audibility threshold', () => {
  const frames = sineFrames(440, 48000, 10);
  const a = frames.next().value;
  const b = frames.next().value;
  assert.equal(a.length, 480);
  assert.ok(Math.abs(rms(a) - (0.25 * 32767) / Math.SQRT2) < 80);
  assert.ok(rms(a) > AUDIBLE_RMS * 10);
  const expectedNext = Math.round(Math.sin((2 * Math.PI * 440 * 480) / 48000) * 0.25 * 32767);
  assert.equal(b[0], expectedNext);
  assert.equal(rms(new Int16Array(480)), 0);
});

test('"heard" and "table assigned" are measured against the media delays that are injected', async () => {
  const { result } = await dryRun({
    args: ['--scenario', 'A', '--participants', '8'],
    mediaFakeOptions: { audibleDelayMs: 700, connectDelayMs: 150 },
  });
  const s = result.summary;
  assert.equal(s.hearMs.p50, 700);
  assert.equal(s.hearMs.max, 700);
  assert.ok(s.hearMs.count > 0);
  assert.ok(s.tableAssignMs.p50 >= 150 && s.tableAssignMs.p50 < 400, `table assigned ${s.tableAssignMs.p50}`);
});

test('scenarios C, D and E are refused without a media layer unless --accept-presence-loss is given', async () => {
  for (const scenario of ['C', 'D', 'E']) {
    await assert.rejects(dryRun({ args: ['--scenario', scenario, '--media', 'none'] }), (e) => e instanceof GuardError && e.code === 'presence-loss');
  }
  const cfg = parseConfig(['run', '--dry-run', '--scenario', 'C', '--media', 'none', '--accept-presence-loss'], {});
  assert.equal(cfg.acceptPresenceLoss, true);
});

test('without media the server forgets people after about two minutes: the run shows it (presence_lost) and re-enters', async () => {
  const { result } = await dryRun({ args: ['--scenario', 'A', '--participants', '6', '--media', 'none'] });
  assert.ok(result.summary.presenceLost > 0, `presenceLost ${result.summary.presenceLost}`);
  assert.equal(result.exitCode, 0);
});

test('the LiveKit adapter imports without the package and says so clearly', async () => {
  const media = createLiveKitMedia({
    importer: async () => {
      throw new Error('Cannot find package');
    },
  });
  await assert.rejects(media.connect({ serverUrl: 'wss://livekit.example.test', token: 't' }), (e) => e instanceof MediaUnavailableError && /not installed/.test(e.message));
});

// A stand-in for @livekit/rtc-node: records what the adapter asks for.
function stubRtc() {
  const rooms = [];
  class Room extends EventEmitter {
    remoteParticipants = new Map();
    published = [];
    sent = [];
    unpublished = [];
    constructor() {
      super();
      rooms.push(this);
      this.localParticipant = {
        publishTrack: async (track, options) => {
          this.published.push({ track, options });
          return { sid: 'sid-1' };
        },
        unpublishTrack: async (sid) => this.unpublished.push(sid),
        publishData: async (bytes, options) => this.sent.push({ bytes, options }),
      };
    }
    async connect(url, token, opts) {
      this.connectedWith = { url, token, opts };
    }
    async disconnect() {
      this.disconnected = true;
    }
  }
  let captured = 0;
  return {
    rooms,
    captured: () => captured,
    rtc: {
      Room,
      RoomEvent: { TrackPublished: 'trackPublished', TrackSubscribed: 'trackSubscribed', Disconnected: 'disconnected', DataReceived: 'dataReceived' },
      IceTransportType: { TRANSPORT_RELAY: 0, TRANSPORT_ALL: 2 },
      ContinualGatheringPolicy: { GATHER_CONTINUALLY: 1 },
      DisconnectReason: { 1: 'CLIENT_INITIATED' },
      TrackKind: { KIND_AUDIO: 1 },
      TrackSource: { SOURCE_MICROPHONE: 2 },
      IceServer: class {
        constructor(o) {
          Object.assign(this, o);
        }
      },
      TrackPublishOptions: class {
        constructor(o) {
          Object.assign(this, o);
        }
      },
      AudioFrame: class {
        constructor(data) {
          this.data = data;
        }
      },
      AudioSource: class {
        async captureFrame() {
          captured += 1;
          await new Promise((r) => setTimeout(r, 1));
        }
        async close() {}
      },
      LocalAudioTrack: { createAudioTrack: () => ({ kind: 1 }) },
      AudioStream: class {
        constructor(track) {
          this.track = track;
        }
        async *[Symbol.asyncIterator]() {
          yield { data: new Int16Array(480).fill(4000) };
        }
      },
    },
  };
}

test('the LiveKit adapter: relay config, audio-only subscription, tone, data packets, disconnect notices (against a stub)', async () => {
  const { rtc, rooms, captured } = stubRtc();
  const media = createLiveKitMedia({ importer: async () => rtc, relay: true });
  const join = { serverUrl: 'wss://livekit.example.test', token: 'plenum-token', turnServers: [{ urls: ['turn:turn.example.test:3478'], username: 'u', credential: 'c' }] };
  const disconnects = [];
  const heard = [];
  media.onDisconnect((which, reason) => disconnects.push([which, reason]));
  media.onAudible((x) => heard.push(x.speakerIdentity));

  await media.connect(join);
  const plenum = rooms[0];
  assert.equal(plenum.connectedWith.opts.autoSubscribe, false, 'never auto-subscribe (no video)');
  assert.equal(plenum.connectedWith.opts.rtcConfig.iceTransportType, 0, 'relay share: TURN only');
  assert.deepEqual(plenum.connectedWith.opts.rtcConfig.iceServers[0].urls, ['turn:turn.example.test:3478']);
  assert.equal(plenum.connectedWith.opts.rtcConfig.iceServers[0].password, 'c');

  // only audio publications are subscribed
  const audio = { kind: 1, subscribed: false, setSubscribed(v) { this.subscribed = v; } };
  const video = { kind: 2, subscribed: false, setSubscribed(v) { this.subscribed = v; } };
  plenum.emit('trackPublished', audio);
  plenum.emit('trackPublished', video);
  assert.deepEqual([audio.subscribed, video.subscribed], [true, false]);

  await media.publishData('lapis-chat', new Uint8Array([1]));
  assert.equal(plenum.sent[0].options.topic, 'lapis-chat');
  assert.equal(plenum.sent[0].options.reliable, true, 'reliable is the default (reactions, chat)');
  // V1.9.161 (scenario F): not reliable on request, and incoming packets of the plenum connection reach onData with their topic
  await media.publishData('lapis-lt-map', new Uint8Array([2]), { reliable: false });
  assert.deepEqual([plenum.sent[1].options.topic, plenum.sent[1].options.reliable], ['lapis-lt-map', false]);
  const received = [];
  media.onData((x) => received.push([x.topic, [...x.bytes]]));
  plenum.emit('dataReceived', new Uint8Array([7, 8]), { identity: 'other' }, 0, 'lapis-lt-map');
  plenum.emit('dataReceived', new Uint8Array([9]), undefined, 0, undefined);
  assert.deepEqual(received, [['lapis-lt-map', [7, 8]], ['', [9]]]);

  await media.connectTable({ join: { ...join, token: 'table-token' } });
  const table = rooms[1];
  assert.equal(table.connectedWith.token, 'table-token');
  assert.ok(media.lastTableTimings.connectMs >= 0 && media.lastTableTimings.teardownMs >= 0, 'phase timings of the table connect are numbers');
  await media.setMic(true);
  assert.equal(table.published[0].options.source, 2);
  await new Promise((r) => setTimeout(r, 30));
  assert.ok(captured() > 3, 'tone frames are fed');
  await media.setMic(false);
  assert.deepEqual(table.unpublished, ['sid-1']);

  table.emit('trackSubscribed', { kind: 1 }, {}, { identity: 'identity-9' });
  await new Promise((r) => setTimeout(r, 5));
  assert.deepEqual(heard, ['identity-9']);

  table.emit('disconnected', 1);
  assert.deepEqual(disconnects, [['table', 'CLIENT_INITIATED']]);
  await media.close();
  assert.equal(plenum.disconnected, true);
});

test('startRun refuses a real LiveKit URL outside the media allowlist before connecting', async () => {
  const { createFakeServer } = await import('./fake-server.mjs');
  const { VirtualClock } = await import('../lib/scheduler.mjs');
  const clock = new VirtualClock();
  const fake = createFakeServer({ clock });
  const cfg = withOverrides(parseConfig(['run', '--dry-run', '--scenario', 'B', '--participants', '2', '--out', '/tmp/unused-lt'], {}), {
    target: 'http://127.0.0.1',
    confirmHost: '127.0.0.1',
    allowedHosts: ['127.0.0.1'],
    allowedLivekitHosts: ['livekit.other.test'], // the fake hands out livekit.example.test
    allowInsecureLocal: true,
    emailPattern: 'lt-{n}@example.test',
    password: fake.password,
    spaceId: fake.spaceId,
  });
  const lines = [];
  const out = { log: (l) => lines.push(l), warn: (l) => lines.push(l), redact: (s) => s };
  const { mkdtempSync } = await import('node:fs');
  const dir = mkdtempSync('/tmp/lt-media-');
  const run = startRun(withOverrides(cfg, { out: dir }), { fetchImpl: fake.fetch, clock, hub: fake.hub, out });
  const result = await run.done;
  assert.equal(result.summary.participantsAbortedByGuard, 2);
  assert.equal(result.exitCode, 3);
});

test('a media layer that is not installed stops the whole run with a clear reason, after clean-up', async () => {
  const notInstalled = () =>
    createLiveKitMedia({
      importer: async () => {
        throw new Error('Cannot find package');
      },
    });
  const { result, fake, lines } = await dryRun({ args: ['--scenario', 'B', '--participants', '4'], mediaFactory: notInstalled });
  assert.equal(result.abortReason, 'media-layer-unavailable');
  assert.equal(result.exitCode, 3);
  assert.match(lines.join(''), /not installed/);
  assert.equal(fake.presentCount(), 0, 'whoever had entered was taken out again');
  assert.equal(fake.openSessions(), 0);
});

test('a failing media connection is counted as a step error, does not end the run and does not leave people behind', async () => {
  const failing = () => ({
    isReal: true,
    connect: async () => {
      throw new Error('connect failed: secret-detail');
    },
    connectTable: async () => {},
    disconnectTable: async () => {},
    setMic: async () => {},
    publishData: async () => {},
    onAudible() {},
    onData() {},
    onDisconnect() {},
    close: async () => {},
  });
  const { result, fake, dir, lines } = await dryRun({ args: ['--scenario', 'B', '--participants', '4', '--abort-on-foreign-presence', 'false'], mediaFactory: failing });
  assert.ok(result.summary.stepErrors >= 4, `stepErrors ${result.summary.stepErrors}`);
  // the diagnosis names the failed step and the error class, never the message ('connect failed: secret-detail')
  assert.ok(result.summary.stepErrorsByOp.enter >= 4, JSON.stringify(result.summary.stepErrorsByOp));
  assert.ok(result.summary.errorTypes.Error >= 4, JSON.stringify(result.summary.errorTypes));
  assert.equal(fake.presentCount(), 0);
  const { allOutputs } = await import('./helpers.mjs');
  assert.equal(allOutputs(dir, lines).includes('secret-detail'), false, 'the text of the error is never kept');
});

// ---- review fixes -------------------------------------------------------------------------------------------------------

function spyOn(session, log, names) {
  return new Proxy(session, {
    get(target, prop) {
      const v = target[prop];
      if (typeof v !== 'function') return v;
      return names.includes(prop) ? (...args) => (log.push(args.length && typeof args[0] === 'boolean' ? `${prop}:${args[0]}` : prop), v.apply(target, args)) : v.bind(target);
    },
  });
}

test('a table reconnect after a rotation puts the microphone back on, so the speaker is heard again', async () => {
  const logs = new Map();
  const { result, fake } = await dryRun({
    args: ['--scenario', 'A', '--participants', '8'],
    wrapMedia: (session, idx) => {
      const log = [];
      logs.set(idx, log);
      return spyOn(session, log, ['connectTable', 'setMic']);
    },
  });
  assert.ok(fake.stats.rotations > 0 && result.summary.rotations > 0, 'tables rotated');
  const reapplied = [...logs.values()].filter((log) => {
    const on = log.indexOf('setMic:true');
    if (on < 0) return false;
    const reconnect = log.indexOf('connectTable', on);
    return reconnect >= 0 && log.indexOf('setMic:true', reconnect) > reconnect;
  });
  assert.ok(reapplied.length > 0, 'somebody who had the microphone on and was rotated publishes again after the reconnect');
});

test('the fake media keeps the tone going for the listener after a rotation (second "heard" at the media level)', async () => {
  const { FakeMediaHub, createFakeMedia } = await import('../lib/media/fake.mjs');
  const { VirtualClock } = await import('../lib/scheduler.mjs');
  const clock = new VirtualClock();
  const hub = new FakeMediaHub();
  const speaker = createFakeMedia({ hub, clock, audibleDelayMs: 0 });
  const listener = createFakeMedia({ hub, clock, audibleDelayMs: 0 });
  const heard = [];
  listener.onAudible((x) => heard.push(x.speakerIdentity));
  await speaker.connect({ identity: 's' });
  await listener.connect({ identity: 'l' });
  const dto = { join: { livekitRoomName: 'table-0-g1' } };
  await speaker.connectTable(dto);
  await listener.connectTable(dto);
  await speaker.setMic(true);
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(heard, ['s']);
  // rotation: both reconnect; the fake reconnect silences the speaker (like the real adapter), so it must be switched on again
  await speaker.connectTable(dto);
  assert.equal(speaker.micOn, false);
  await listener.connectTable(dto);
  await speaker.setMic(true);
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(heard, ['s', 's']);
});

test('a LiveKit connectTable that is overtaken by a newer one closes its own room instead of leaking it', async () => {
  const { rtc, rooms } = stubRtc();
  // the first Room.connect is slow, the second fast: the slow one finishes last
  let n = 0;
  const OriginalRoom = rtc.Room;
  rtc.Room = class extends OriginalRoom {
    async connect(url, token, opts) {
      const mine = n++;
      await new Promise((r) => setTimeout(r, mine === 1 ? 40 : 2));
      this.connectedWith = { url, token, opts };
    }
  };
  const media = createLiveKitMedia({ importer: async () => rtc });
  const join = { serverUrl: 'wss://livekit.example.test', token: 'p', turnServers: [] };
  await media.connect(join); // n = 0 (plenum)
  const slow = media.connectTable({ join: { ...join, token: 'slow' } }); // n = 1 (slow)
  await new Promise((r) => setTimeout(r, 5));
  const fast = media.connectTable({ join: { ...join, token: 'fast' } }); // n = 2 (fast)
  await Promise.all([slow, fast]);
  const tableRooms = rooms.slice(1);
  assert.equal(tableRooms.length, 2);
  const open = tableRooms.filter((r) => !r.disconnected);
  assert.equal(open.length, 1, 'exactly one table room stays open');
  assert.equal(open[0].connectedWith.token, 'fast', 'the newer call wins');
  await media.close();
  assert.ok(rooms.every((r) => r.disconnected), 'close() reaches every room');
});

test('connect() closes the previous plenum room instead of overwriting it, and a leaked table connection ends with presence loss', async () => {
  const { rtc, rooms } = stubRtc();
  const media = createLiveKitMedia({ importer: async () => rtc });
  const join = { serverUrl: 'wss://livekit.example.test', token: 'p', turnServers: [] };
  await media.connect(join);
  await media.connect(join);
  assert.equal(rooms[0].disconnected, true, 'the first plenum room was closed');
  assert.notEqual(rooms[1].disconnected, true);
  await media.close();
  assert.ok(rooms.every((r) => r.disconnected));
});

test('losing the presence ends the table media connection too', async () => {
  const logs = [];
  // media 'none' behind the spy: the fake server forgets people after about two minutes (presence_lost)
  const { result } = await dryRun({
    args: ['--scenario', 'C', '--participants', '6', '--accept-presence-loss'],
    mediaFactory: () => spyOn(createMediaFactory('none')(), logs, ['disconnectTable']),
  });
  assert.ok(result.summary.presenceLost > 0, 'presence loss happened');
  assert.ok(logs.filter((l) => l === 'disconnectTable').length >= result.summary.presenceLost, 'every loss closed the table connection');
});

test('a tableToken answer with a foreign LiveKit URL aborts the participant before the media layer connects', async () => {
  const { result, fake } = await dryRun({
    args: ['--scenario', 'A', '--participants', '8'],
    fake: { tableServerUrl: 'wss://livekit.evil.test/' },
    mediaFakeOptions: {},
  });
  assert.ok(fake.stats.rotations > 0);
  assert.ok(result.summary.participantsAbortedByGuard > 0, 'a participant was stopped by the guard');
  assert.equal(result.exitCode, 3);
});

import { createNativeErrorGuard, MAX_NATIVE_ERRORS } from '../lib/runner.mjs';
import { Recorder } from '../lib/metrics.mjs';

test('native errors from outside any promise chain are counted by class name only, and too many stop the run', () => {
  const recorder = new Recorder({ clock: { now: () => 0 } });
  const aborts = [];
  const guard = createNativeErrorGuard({ recorder, abort: (r) => aborts.push(r) });
  const err = new Error('trying to drop an invalid handle: secret-detail');
  guard.handler(err);
  guard.handler(Object.assign(new Error('x'), { name: 'weird name with spaces' }));
  assert.equal(guard.count, 2);
  assert.deepEqual(aborts, []);
  const summary = recorder.summary();
  assert.deepEqual(summary.nativeErrorTypes, { Error: 1, unknown: 1 });
  assert.ok(!JSON.stringify(summary).includes('secret-detail'), 'never the message');
  for (let i = 0; i < MAX_NATIVE_ERRORS; i++) guard.handler(err);
  assert.ok(aborts.includes('too-many-native-errors'));
});

test('a run installs the native error guard and removes it again, so nothing leaks into the next run', async () => {
  const before = [process.listenerCount('uncaughtException'), process.listenerCount('unhandledRejection')];
  const { result } = await dryRun({ args: ['--scenario', 'B', '--participants', '4', '--abort-on-foreign-presence', 'false'] });
  assert.equal(result.summary.nativeErrors, 0);
  assert.deepEqual([process.listenerCount('uncaughtException'), process.listenerCount('unhandledRejection')], before);
});

test('--subscribe audio-passive subscribes to audio (the server forwards it) but never reads a frame; audio reads them; none subscribes to nothing', async () => {
  const run = async (opts) => {
    const { rtc, rooms } = stubRtc();
    let streams = 0;
    const RealStream = rtc.AudioStream;
    rtc.AudioStream = class extends RealStream {
      constructor(track) {
        super(track);
        streams += 1;
      }
    };
    const media = createLiveKitMedia({ importer: async () => rtc, ...opts });
    await media.connect({ serverUrl: 'wss://livekit.example.test', token: 't' });
    const audio = { kind: 1, subscribed: false, setSubscribed(v) { this.subscribed = v; } };
    rooms[0].emit('trackPublished', audio);
    rooms[0].emit('trackSubscribed', { kind: 1 }, audio, { identity: 'speaker' });
    await new Promise((r) => setTimeout(r, 20));
    return { subscribed: audio.subscribed, streams };
  };
  assert.deepEqual(await run({ subscribeAudio: true, consumeAudio: true }), { subscribed: true, streams: 1 });
  assert.deepEqual(await run({ subscribeAudio: true, consumeAudio: false }), { subscribed: true, streams: 0 });
  assert.deepEqual(await run({ subscribeAudio: false, consumeAudio: false }), { subscribed: false, streams: 0 });
  const cfg = (v) => parseConfig(['plan', '--subscribe', v], {}).subscribe;
  assert.equal(cfg('audio-passive'), 'audio-passive');
  assert.throws(() => cfg('video'), GuardError);
});

test('--no-turn leaves the TURN servers of the join answer out of the ICE configuration, and is refused together with a relay share', async () => {
  const { rtc, rooms } = stubRtc();
  const join = { serverUrl: 'wss://livekit.example.test', token: 't', turnServers: [{ urls: ['turn:t.example.test:3478'], username: 'u', credential: 'c' }] };
  const withTurn = createLiveKitMedia({ importer: async () => rtc });
  await withTurn.connect(join);
  assert.equal(rooms[0].connectedWith.opts.rtcConfig.iceServers.length, 1);
  const without = createLiveKitMedia({ importer: async () => rtc, useTurn: false });
  await without.connect(join);
  assert.equal(rooms[1].connectedWith.opts.rtcConfig.iceServers.length, 0);
  assert.equal(parseConfig(['plan', '--no-turn', '--relay-share', '0'], {}).noTurn, true);
  assert.throws(() => parseConfig(['plan', '--no-turn'], {}), GuardError, 'default relay share is above 0');
  assert.throws(() => parseConfig(['plan', '--no-turn', '--relay-share', '0.15'], {}), GuardError);
});

import { parseTurnOverride, assertTurnOverrideAllowed } from '../lib/guard.mjs';

test('--turn-override swaps the TURN URLs but keeps the credentials, only for a host that is on the TURN allowlist', async () => {
  const { rtc, rooms } = stubRtc();
  const join = { serverUrl: 'wss://livekit.example.test', token: 't', turnServers: [{ urls: ['turn:orig.example.test:3478'], username: 'u', credential: 'c' }] };
  const media = createLiveKitMedia({ importer: async () => rtc, turnOverride: { host: '203.0.113.7', port: 3490 } });
  await media.connect(join);
  const ice = rooms[0].connectedWith.opts.rtcConfig.iceServers[0];
  assert.deepEqual(ice.urls, ['turn:203.0.113.7:3490?transport=udp', 'turn:203.0.113.7:3490?transport=tcp']);
  assert.equal(ice.username, 'u');
  assert.equal(ice.password, 'c');
  assert.deepEqual(parseTurnOverride('turn:203.0.113.7:3490'), { host: '203.0.113.7', port: 3490 });
  for (const bad of ['turns:h:3490', 'turn:h', 'turn:h:80', 'turn:h:99999', 'turn:h:3490?x=1', 'turn:us er@h:3490', '']) {
    assert.throws(() => parseTurnOverride(bad), GuardError, bad);
  }
  const cfg = (allow) => ({ turnOverride: 'turn:203.0.113.7:3490', allowedTurnHosts: allow });
  assert.throws(() => assertTurnOverrideAllowed(cfg([])), (e) => e instanceof GuardError && e.code === 'turn-host');
  assert.throws(() => assertTurnOverrideAllowed(cfg(['203.0.113.8'])), GuardError);
  assert.deepEqual(assertTurnOverrideAllowed(cfg(['203.0.113.7'])), { host: '203.0.113.7', port: 3490 });
  assert.equal(assertTurnOverrideAllowed({ allowedTurnHosts: [] }), null, 'no override, nothing to check');
  assert.throws(() => parseConfig(['plan', '--turn-override', 'turn:203.0.113.7:3490', '--no-turn', '--relay-share', '0'], {}), GuardError);
  const parsed = parseConfig(['plan', '--turn-override', 'turn:203.0.113.7:3490'], { LAPIS_LT_ALLOWED_TURN_HOSTS: '203.0.113.7' });
  assert.deepEqual([parsed.turnOverride, parsed.allowedTurnHosts], ['turn:203.0.113.7:3490', ['203.0.113.7']]);
});
