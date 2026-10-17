// The real media layer on @livekit/rtc-node (Apache-2.0, native prebuilds). OPTIONAL: loaded with import() only when
// --media=livekit is used; CI never installs it. WRITTEN AGAINST THE TYPE DEFINITIONS OF 1.1.0 AND NEVER RUN AGAINST A REAL LIVEKIT.
//
// Per participant: one plenum connection (listen only: the plenum token cannot publish) and, while seated at a table, one
// table connection (the table token may publish the microphone). Only AUDIO is subscribed, never video.
import { sineFrames, rms, AUDIBLE_RMS } from '../tone.mjs';

export class MediaUnavailableError extends Error {
  constructor() {
    super('The LiveKit media layer is not installed. Run "npm install" in scripts/loadtest (optional dependency @livekit/rtc-node) or use --media=none.');
    this.name = 'MediaUnavailableError';
  }
}

const SAMPLE_RATE = 48000;

export function createLiveKitMedia({ importer = () => import('@livekit/rtc-node'), relay = false, subscribeAudio = true } = {}) {
  let rtc = null;
  let plenum = null;
  let table = null;
  let closing = false;
  let tableGen = 0; // every connectTable/disconnectTable bumps it: a connect that finds a newer generation after its await closes its own room
  let plenumGen = 0;
  let pump = null;
  let micTrackSid = null;
  const audible = [];
  const disconnected = [];

  async function load() {
    if (rtc) return rtc;
    try {
      rtc = await importer();
    } catch {
      throw new MediaUnavailableError();
    }
    return rtc;
  }

  function listen(track, participant) {
    if (!subscribeAudio || track.kind !== rtc.TrackKind.KIND_AUDIO) return;
    const speakerIdentity = participant?.identity ?? '';
    (async () => {
      const stream = new rtc.AudioStream(track);
      for await (const frame of stream) {
        if (rms(frame.data) > AUDIBLE_RMS) for (const cb of audible) cb({ speakerIdentity });
      }
    })().catch(() => {});
  }

  function subscribeAudioOnly(publication) {
    if (subscribeAudio && publication.kind === rtc.TrackKind.KIND_AUDIO) publication.setSubscribed(true);
  }

  async function open(join, which) {
    await load();
    const room = new rtc.Room();
    room.on(rtc.RoomEvent.TrackPublished, (publication) => subscribeAudioOnly(publication));
    room.on(rtc.RoomEvent.TrackSubscribed, (track, _publication, participant) => listen(track, participant));
    room.on(rtc.RoomEvent.Disconnected, (reason) => {
      if (closing || (which === 'table' && table !== room) || (which === 'plenum' && plenum !== room)) return;
      for (const cb of disconnected) cb(which, String(rtc.DisconnectReason?.[reason] ?? reason));
    });
    const rtcConfig = {
      iceTransportType: relay ? rtc.IceTransportType.TRANSPORT_RELAY : rtc.IceTransportType.TRANSPORT_ALL,
      continualGatheringPolicy: rtc.ContinualGatheringPolicy.GATHER_CONTINUALLY,
      iceServers: (join.turnServers ?? []).map((t) => new rtc.IceServer({ urls: t.urls, username: t.username, password: t.credential })),
    };
    // autoSubscribe is off on purpose: only audio publications are subscribed, one by one.
    await room.connect(join.serverUrl, join.token, { autoSubscribe: false, dynacast: false, rtcConfig });
    for (const participant of room.remoteParticipants.values()) {
      for (const publication of participant.trackPublications.values()) subscribeAudioOnly(publication);
    }
    return room;
  }

  async function stopPump() {
    const p = pump;
    pump = null;
    if (p) {
      p.running = false;
      await p.done.catch(() => {});
    }
  }

  async function teardownTable() {
    await stopPump();
    const room = table;
    table = null;
    micTrackSid = null;
    if (room) await room.disconnect().catch(() => {});
  }

  return {
    isReal: true,
    async connect(join) {
      closing = false; // a participant that left (close) and enters again gets rotation notices again
      const gen = ++plenumGen;
      const old = plenum;
      plenum = null;
      if (old) await old.disconnect().catch(() => {});
      const room = await open(join, 'plenum');
      if (gen !== plenumGen) {
        await room.disconnect().catch(() => {}); // a newer connect (or close) overtook this one: nobody would ever close this room
        return;
      }
      plenum = room;
    },
    async connectTable(tokenDto) {
      const gen = ++tableGen;
      await teardownTable();
      const room = await open(tokenDto.join, 'table');
      if (gen !== tableGen) {
        await room.disconnect().catch(() => {});
        return;
      }
      table = room;
    },
    async disconnectTable() {
      tableGen += 1;
      await teardownTable();
    },
    async setMic(on) {
      if (!table?.localParticipant) return;
      if (!on) {
        await stopPump();
        if (micTrackSid) await table.localParticipant.unpublishTrack(micTrackSid).catch(() => {});
        micTrackSid = null;
        return;
      }
      if (pump) return;
      const source = new rtc.AudioSource(SAMPLE_RATE, 1);
      const track = rtc.LocalAudioTrack.createAudioTrack('lt-tone', source);
      const publication = await table.localParticipant.publishTrack(track, new rtc.TrackPublishOptions({ source: rtc.TrackSource.SOURCE_MICROPHONE }));
      micTrackSid = publication.sid;
      const state = { running: true, done: null };
      state.done = (async () => {
        for (const frame of sineFrames(440, SAMPLE_RATE, 10)) {
          if (!state.running) break;
          await source.captureFrame(new rtc.AudioFrame(frame, SAMPLE_RATE, 1, frame.length));
        }
        await source.close().catch(() => {});
      })();
      pump = state;
    },
    async publishData(topic, bytes) {
      if (!plenum?.localParticipant) return;
      await plenum.localParticipant.publishData(bytes, { reliable: true, topic });
    },
    onAudible(cb) {
      audible.push(cb);
    },
    onDisconnect(cb) {
      disconnected.push(cb);
    },
    async close() {
      closing = true;
      plenumGen += 1;
      tableGen += 1;
      await stopPump();
      const rooms = [table, plenum];
      table = null;
      plenum = null;
      await Promise.all(rooms.map((r) => r?.disconnect().catch(() => {})));
    },
  };
}
