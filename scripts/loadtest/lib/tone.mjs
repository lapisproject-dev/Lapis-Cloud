// Synthetic test tone: a pure sine, never a voice and never a recording.

/** Yields phase-continuous Int16Array frames of `frameMs` milliseconds, endlessly. */
export function* sineFrames(hz = 440, sampleRate = 48000, frameMs = 10, amplitude = 0.25) {
  const samples = Math.round((sampleRate * frameMs) / 1000);
  let n = 0;
  for (;;) {
    const frame = new Int16Array(samples);
    for (let i = 0; i < samples; i++, n++) frame[i] = Math.round(Math.sin((2 * Math.PI * hz * n) / sampleRate) * amplitude * 32767);
    yield frame;
  }
}

export function rms(int16) {
  if (!int16 || int16.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < int16.length; i++) sum += int16[i] * int16[i];
  return Math.sqrt(sum / int16.length);
}

/** A frame counts as "audible" above this RMS (full scale is 32767). */
export const AUDIBLE_RMS = 300;
