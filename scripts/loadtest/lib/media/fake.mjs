// An in-process stand-in for the media layer, used by the tests, the dry run and the selftest. It models just enough of LiveKit:
// who is "connected" (the fake server's poller looks at that), table rooms, rotation notices and "a microphone is heard" with an
// injectable delay. It opens no socket at all.

export class FakeMediaHub {
  connected = new Set(); // identities with a plenum connection
  rooms = new Map(); // table room name -> Set<session>
  sessions = new Map(); // identity -> session
  isConnected(identity) {
    return this.connected.has(identity);
  }
  /** The server rotated a table room: the member is thrown out of it and has to ask for a new token. */
  notifyRotation(identity) {
    this.sessions.get(identity)?._rotated();
  }
}

export function createFakeMedia({ hub, clock, connectDelayMs = 0, audibleDelayMs = 20, dataDelayMs = 5 } = {}) {
  const audible = [];
  const dataCallbacks = [];
  const disconnected = [];
  let identity = null;
  let tableRoom = null;
  let mic = false;
  // delays are in the milliseconds of the run's clock (virtual in a dry run)
  const delay = (ms) => (ms > 0 ? clock.sleep(ms) : Promise.resolve());

  const session = {
    isReal: false,
    async connect(join) {
      await delay(connectDelayMs);
      identity = join.identity;
      hub.connected.add(identity);
      hub.sessions.set(identity, session);
    },
    async connectTable(tokenDto) {
      await delay(connectDelayMs);
      await session.disconnectTable();
      tableRoom = tokenDto.join.livekitRoomName;
      if (!hub.rooms.has(tableRoom)) hub.rooms.set(tableRoom, new Set());
      hub.rooms.get(tableRoom).add(session);
    },
    async disconnectTable() {
      mic = false;
      if (tableRoom) hub.rooms.get(tableRoom)?.delete(session);
      tableRoom = null;
    },
    async setMic(on) {
      mic = on;
      if (!on || !tableRoom) return;
      for (const other of hub.rooms.get(tableRoom) ?? []) {
        if (other !== session) void delay(audibleDelayMs).then(() => other._heard(identity));
      }
    },
    /** Like LiveKit: a data packet goes to everybody else on the plenum connection, never back to the sender. */
    async publishData(topic, bytes) {
      for (const other of hub.sessions.values()) {
        if (other !== session) void delay(dataDelayMs).then(() => other._data({ topic, bytes }));
      }
    },
    onData(cb) {
      dataCallbacks.push(cb);
    },
    onAudible(cb) {
      audible.push(cb);
    },
    onDisconnect(cb) {
      disconnected.push(cb);
    },
    async close() {
      await session.disconnectTable();
      if (identity) {
        hub.connected.delete(identity);
        hub.sessions.delete(identity);
      }
    },
    _data(packet) {
      for (const cb of dataCallbacks) cb(packet);
    },
    _heard(speakerIdentity) {
      if (tableRoom) for (const cb of audible) cb({ speakerIdentity });
    },
    _rotated() {
      if (!tableRoom) return;
      hub.rooms.get(tableRoom)?.delete(session);
      tableRoom = null;
      for (const cb of disconnected) cb('table', 'rotation');
    },
    get micOn() {
      return mic;
    },
  };
  return session;
}
