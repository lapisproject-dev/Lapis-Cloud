import { createNoneMedia } from './none.mjs';
import { createFakeMedia } from './fake.mjs';
import { createLiveKitMedia } from './livekit.mjs';

/** One MediaSession per participant. `relay` = this participant forces TURN relay. */
export function createMediaFactory(kind, { hub, clock, subscribe = 'audio', noTurn = false, turnOverride = null, fakeOptions = {} } = {}) {
  switch (kind) {
    case 'none':
      return () => createNoneMedia();
    case 'fake':
      return () => createFakeMedia({ hub, clock, ...fakeOptions });
    case 'livekit':
      // audio: subscribe AND count audible frames (needed for the "heard" measurement; every frame passes through JavaScript, which saturates the
      // tool at a few thousand frames per second). audio-passive: subscribe so that the server forwards the audio, but consume nothing (no "heard").
      return ({ relay }) => createLiveKitMedia({ relay, subscribeAudio: subscribe !== 'none', consumeAudio: subscribe === 'audio', useTurn: !noTurn, turnOverride });
    default:
      throw new Error('unknown media kind');
  }
}
