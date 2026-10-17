// Orchestration of one run: guards -> plan -> participants -> abort monitor -> clean-up -> results.
import { assertCaps, assertTargetAllowed, GuardError } from './guard.mjs';
import { assertAccountConfig, publicConfig } from './config.mjs';
import { buildPlan, durationMs } from './scenarios.mjs';
import { Clock, TokenBucket, createRng } from './scheduler.mjs';
import { Recorder, evaluateAbort, writeResults } from './metrics.mjs';
import { AudibleBoard, SimParticipant } from './participant.mjs';
import { createMediaFactory } from './media/index.mjs';
import { FakeMediaHub } from './media/fake.mjs';
import { CookieJar } from './http.mjs';
import { KiluaClient } from './kilua.mjs';
import { login, logout } from './auth.mjs';
import { emailFor } from './config.mjs';
import { SecretSet } from './redact.mjs';

export const TOOL_VERSION = '1.9.149';
export const CLEANUP_CONCURRENCY = 8; // shutdown.mjs sizes the grace period with this

/** Refuses what cannot give a meaningful result. */
export function assertRunnable(cfg) {
  assertCaps(cfg);
  assertAccountConfig(cfg);
  if (cfg.media === 'none' && ['C', 'D', 'E'].includes(cfg.scenario) && !cfg.acceptPresenceLoss) {
    throw new GuardError(
      'presence-loss',
      'without a media layer the server deletes the presence of people who are not connected to LiveKit after about 2 to 3 minutes: scenarios C, D and E need --media=livekit (or --accept-presence-loss)',
    );
  }
}

async function pool(items, limit, fn) {
  const queue = [...items];
  await Promise.all(
    Array.from({ length: Math.min(limit, queue.length) }, async () => {
      for (let item = queue.shift(); item !== undefined; item = queue.shift()) await fn(item);
    }),
  );
}

/**
 * Starts a run. deps: { fetchImpl, clock?, hub?, out, secrets?, mediaFactory? }
 * @returns {{ done: Promise<{exitCode:number, summary:object, abortReason:string|null}>, abort: (reason:string)=>void }}
 */
export function startRun(cfg, deps) {
  assertRunnable(cfg);
  const { origin } = assertTargetAllowed(cfg);
  const secrets = deps.secrets ?? new SecretSet();
  secrets.add(cfg.password);
  const clock = deps.clock ?? new Clock({ scale: cfg.timeScale });
  const recorder = new Recorder({ clock, expectThrottle: cfg.unsafeIgnoreClientThrottle });
  const bucket = new TokenBucket(clock, cfg.maxRps);
  const board = new AudibleBoard(clock);
  const controller = new AbortController();
  const rng = createRng(cfg.seed);
  const hub = deps.hub ?? new FakeMediaHub();
  const mediaFactory = deps.mediaFactory ?? createMediaFactory(cfg.media, { hub, subscribe: cfg.subscribe, clock });
  const plan = buildPlan(cfg.scenario, cfg);
  const total = durationMs(cfg.scenario, cfg);
  let abortReason = null;
  let guardAborts = 0;

  const abort = (reason) => {
    if (abortReason === null) {
      abortReason = reason;
      deps.out.warn(`aborting: ${reason}`);
      controller.abort();
    }
  };
  const onFatal = (reason, info) => {
    if (reason.startsWith('participant-')) guardAborts += 1; // only this participant stops
    else abort(reason);
    if (info?.p) deps.out.warn(`${reason}: p${String(info.p).padStart(2, '0')}`);
  };

  // The ones who force TURN relay: a fixed share, chosen by the seeded generator.
  const relaySet = new Set();
  for (let p = 1; p <= cfg.participants; p++) if (rng.next() < cfg.relayShare) relaySet.add(p);

  const participants = [];
  for (let p = 1; p <= cfg.participants; p++) {
    participants.push(
      new SimParticipant({
        idx: p,
        cfg,
        deps: {
          clock,
          recorder,
          bucket,
          fetchImpl: deps.fetchImpl,
          secrets,
          board,
          rng: createRng(cfg.seed * 1000 + p),
          signal: controller.signal,
          onFatal,
          baseUrl: origin,
          media: mediaFactory({ relay: relaySet.has(p) }),
        },
      }),
    );
  }

  const monitor = (async () => {
    for (let t = 10_000; ; t += 10_000) {
      if (!(await clock.sleepUntil(t, controller.signal))) return;
      const reason = evaluateAbort(recorder.recent(60_000), { expectThrottle: cfg.unsafeIgnoreClientThrottle });
      if (reason) return abort(reason);
      if (t > total + 120_000) return;
    }
  })();

  const loops = participants.map(async (participant) => {
    for (const step of plan.filter((s) => s.p === participant.idx)) {
      if (!(await clock.sleepUntil(step.at, controller.signal))) return;
      try {
        await participant.exec(step);
      } catch (e) {
        if (e?.name === 'MediaUnavailableError') {
          deps.out.warn(e.message);
          return abort('media-layer-unavailable');
        }
        recorder.mark('step_error', participant.idx); // e.g. a connection to LiveKit that failed; nothing of the error is kept
      }
    }
  });

  const done = (async () => {
    deps.out.log(`run: scenario ${cfg.scenario}, ${cfg.participants} participants, media ${cfg.media}${cfg.dryRun ? ', DRY RUN (fake server)' : ''}`);
    if (cfg.media === 'none') deps.out.warn('no media layer: presence-dependent values are invalid after about 120 s');
    await Promise.all(loops);
    controller.abort(); // stops the monitor; clean-up calls ignore the signal
    await monitor;
    await pool(participants, CLEANUP_CONCURRENCY, (p) => p.cleanup());
    const summary = writeResults(
      cfg.out,
      {
        recorder,
        meta: {
          toolVersion: TOOL_VERSION,
          dryRun: cfg.dryRun,
          media: cfg.media,
          config: publicConfig(cfg),
          virtualDurationMs: Math.round(clock.now()),
          abortReason,
          participantsAbortedByGuard: guardAborts,
        },
      },
      deps.out.redact,
    );
    const exitCode = abortReason === 'signal' ? 130 : abortReason !== null || guardAborts > 0 ? 3 : 0;
    deps.out.log(`done: ${summary.calls} calls, ${summary.unexpectedErrors} unexpected, results in ${cfg.out}`);
    return { exitCode, summary, abortReason };
  })();

  return { done, abort };
}

/** Logs every test account in and takes it out of the room again. Prints the e-mails of the accounts to delete. */
export async function runCleanup(cfg, deps) {
  assertCaps(cfg);
  assertAccountConfig(cfg);
  const { origin } = assertTargetAllowed(cfg);
  const secrets = deps.secrets ?? new SecretSet();
  secrets.add(cfg.password);
  const clock = deps.clock ?? new Clock({ scale: 1 });
  const recorder = new Recorder({ clock });
  const emails = [];
  const idxs = Array.from({ length: cfg.participants }, (_, i) => i + 1);
  await pool(idxs, 4, async (idx) => {
    const jar = new CookieJar({ onSet: (v) => secrets.add(v) });
    const email = emailFor(cfg, idx);
    emails.push(email);
    const r = await login({ baseUrl: origin, jar, fetchImpl: deps.fetchImpl, recorder, idx, email, password: cfg.password });
    if (!r.ok) return;
    const kilua = new KiluaClient({ baseUrl: origin, jar, fetchImpl: deps.fetchImpl, recorder, participantIdx: idx, secrets });
    await kilua.call('leaveTable', [cfg.spaceId]);
    await kilua.call('leaveSpace', [cfg.spaceId]);
    await logout({ baseUrl: origin, jar, fetchImpl: deps.fetchImpl, recorder, idx });
  });
  deps.out.log('clean-up done. Delete these test accounts in the administration (the tool never deletes accounts):');
  for (const email of emails.sort()) deps.out.log(`  ${email}`);
  return { emails, summary: recorder.summary() };
}
