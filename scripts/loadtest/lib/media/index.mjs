import { createNoneMedia } from './none.mjs';
import { createFakeMedia } from './fake.mjs';
import { createLiveKitMedia } from './livekit.mjs';

/** One MediaSession per participant. `relay` = this participant forces TURN relay. */
export function createMediaFactory(kind, { hub, clock, subscribe = 'audio', fakeOptions = {} } = {}) {
  switch (kind) {
    case 'none':
      return () => createNoneMedia();
    case 'fake':
      return () => createFakeMedia({ hub, clock, ...fakeOptions });
    case 'livekit':
      return ({ relay }) => createLiveKitMedia({ relay, subscribeAudio: subscribe === 'audio' });
    default:
      throw new Error('unknown media kind');
  }
}
