#!/usr/bin/env node
// CLI of the encounter-room load-test tool (V1.9.161). Never run against a real instance when this was written.
import { parseConfig, assertAccountConfig, withOverrides, USAGE } from '../lib/config.mjs';
import { GuardError, assertCaps, assertTargetAllowed } from '../lib/guard.mjs';
import { basicAuthSecrets, withBasicAuth } from '../lib/basicauth.mjs';
import { SecretSet, createOutput, safeError } from '../lib/redact.mjs';
import { estimatePlan } from '../lib/scenarios.mjs';
import { startRun, runCleanup, CLEANUP_CONCURRENCY } from '../lib/runner.mjs';
import { installShutdown, cleanupGraceMs } from '../lib/shutdown.mjs';
import { VirtualClock } from '../lib/scheduler.mjs';
import { launchShards } from '../lib/launcher.mjs';

const secrets = new SecretSet();
const out = createOutput({ secrets });

async function withFake(cfg, body) {
  // The built-in fake lives with the tests; it is only loaded for selftest and --dry-run.
  const { createFakeServer } = await import('../test/fake-server.mjs');
  const clock = new VirtualClock();
  const fake = createFakeServer({ clock, presenceRequiresMedia: true });
  const dry = withOverrides(cfg, {
    target: 'http://127.0.0.1',
    confirmHost: '127.0.0.1',
    allowedHosts: ['127.0.0.1'],
    allowedLivekitHosts: ['livekit.example.test'],
    allowInsecureLocal: true,
    emailPattern: `lt-{n}@${fake.emailDomain}`,
    password: fake.password,
    spaceId: fake.spaceId,
  });
  return body(dry, { fetchImpl: fake.fetch, clock, hub: fake.hub, fake });
}

/** The fetch for a real target: with the Basic credential for the confirmed origin, if one is configured. */
function targetFetch(cfg) {
  for (const s of basicAuthSecrets(cfg.basicAuth)) secrets.add(s);
  if (!cfg.basicAuth) return globalThis.fetch;
  const { origin } = assertTargetAllowed(cfg);
  return withBasicAuth(globalThis.fetch, { origin, credential: cfg.basicAuth });
}

async function main() {
  const cfg = parseConfig(process.argv.slice(2), process.env);
  if (cfg.help) {
    out.log(USAGE);
    return 0;
  }
  if (cfg.command === 'plan') {
    assertCaps(cfg);
    const estimate = estimatePlan(cfg.scenario, cfg);
    out.log(JSON.stringify(estimate, null, 2));
    if (estimate.exceedsServerLimit) {
      out.warn('the plan would exceed a server limit per member by design: refusing');
      return 3;
    }
    return 0;
  }
  if (cfg.command === 'selftest' || (cfg.command === 'run' && cfg.dryRun)) {
    return withFake(cfg, async (dry, deps) => {
      const run = startRun(dry, { ...deps, secrets, out });
      const stop = installShutdown(() => run.abort('signal'), { graceMs: cleanupGraceMs(cfg, { concurrency: CLEANUP_CONCURRENCY }) });
      try {
        return (await run.done).exitCode;
      } finally {
        stop();
      }
    });
  }
  if (cfg.command === 'run' && cfg.processes > 1) {
    assertAccountConfig(cfg);
    assertTargetAllowed(cfg);
    // The parent starts no run of its own: it forwards the signals to the shards (each cleans up its own accounts) and merges.
    let children = null;
    const stop = installShutdown(() => children?.stopAll(), { graceMs: cleanupGraceMs(cfg, { concurrency: CLEANUP_CONCURRENCY }) + 15_000 });
    try {
      return await launchShards({ argv: process.argv.slice(2), cfg, out, script: process.argv[1], onSpawned: (c) => (children = c) });
    } finally {
      stop();
    }
  }
  if (cfg.command === 'run') {
    assertAccountConfig(cfg);
    const run = startRun(cfg, { fetchImpl: targetFetch(cfg), secrets, out });
    const stop = installShutdown(() => run.abort('signal'), { graceMs: cleanupGraceMs(cfg, { concurrency: CLEANUP_CONCURRENCY }) });
    try {
      return (await run.done).exitCode;
    } finally {
      stop();
    }
  }
  if (cfg.command === 'cleanup') {
    await runCleanup(cfg, { fetchImpl: targetFetch(cfg), secrets, out });
    return 0;
  }
  return 1;
}

main().then(
  (code) => process.exit(code),
  (e) => {
    // Only the kind of failure and, for a guard, its code and fixed message: never a body, a URL or a stack with values.
    if (e instanceof GuardError) out.warn(`refused: ${e.message} [${e.code}]`);
    else out.warn(`failed: ${JSON.stringify(safeError(e))}`);
    process.exit(e instanceof GuardError ? 1 : 2);
  },
);
