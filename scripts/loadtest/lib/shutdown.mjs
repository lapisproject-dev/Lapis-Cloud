// Not-Aus: the first SIGINT/SIGTERM starts the clean-up (leave table, leave room, logout) within a grace period;
// the second signal, or the end of the grace period, ends the process at once.
export const BASE_GRACE_MS = 15_000;

/**
 * Grace period that fits the clean-up volume. Per participant the final calls are leaveTable + leaveSpace + logout; they pass the shared
 * rate bucket (--max-rps) and the per-participant client throttle (leaveTable and leaveSpace are about 4.3 s apart), with
 * `concurrency` participants in parallel. A fixed 15 s ended the process before 40 participants at a low --max-rps were out of the room.
 */
export function cleanupGraceMs(cfg, { concurrency = 8 } = {}) {
  const n = cfg.participants ?? 0;
  const bucketMs = ((n * 3) / cfg.maxRps) * 1000;
  const throttleMs = cfg.unsafeIgnoreClientThrottle ? 0 : Math.ceil(n / concurrency) * 4_500;
  return Math.ceil(BASE_GRACE_MS + bucketMs + throttleMs);
}

export function installShutdown(onAbort, { graceMs = BASE_GRACE_MS, exit = (code) => process.exit(code), proc = process } = {}) {
  let signals = 0;
  let timer = null;
  const handler = (name) => {
    signals += 1;
    if (signals === 1) {
      timer = setTimeout(() => exit(130), graceMs);
      timer.unref?.();
      Promise.resolve(onAbort(name)).catch(() => {});
    } else {
      exit(130);
    }
  };
  const onInt = () => handler('SIGINT');
  const onTerm = () => handler('SIGTERM');
  proc.on('SIGINT', onInt);
  proc.on('SIGTERM', onTerm);
  return () => {
    clearTimeout(timer);
    proc.off('SIGINT', onInt);
    proc.off('SIGTERM', onTerm);
  };
}
