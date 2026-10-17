// Shared test helpers. Importing this file installs the network guard: any connection to something other than loopback throws.
import net from 'node:net';
import { mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseConfig, withOverrides } from '../lib/config.mjs';
import { SecretSet, createOutput } from '../lib/redact.mjs';
import { VirtualClock } from '../lib/scheduler.mjs';
import { startRun } from '../lib/runner.mjs';
import { createMediaFactory } from '../lib/media/index.mjs';
import { createFakeServer } from './fake-server.mjs';

const LOOPBACK = new Set(['127.0.0.1', '::1', 'localhost', '[::1]']);

function assertLoopback(host, what) {
  if (host !== undefined && host !== null && !LOOPBACK.has(String(host))) {
    throw new Error(`network guard: ${what} to a non-loopback host is forbidden in tests`);
  }
}

if (!globalThis.__ltNetworkGuard) {
  globalThis.__ltNetworkGuard = true;
  const realFetch = globalThis.fetch;
  globalThis.fetch = (input, init) => {
    const url = new URL(typeof input === 'string' ? input : (input.url ?? String(input)));
    assertLoopback(url.hostname, 'fetch');
    return realFetch(input, init);
  };
  const realConnect = net.Socket.prototype.connect;
  net.Socket.prototype.connect = function guarded(...args) {
    const first = args[0];
    if (typeof first === 'object' && first !== null) {
      if (first.path === undefined) assertLoopback(first.host ?? 'localhost', 'socket');
    } else if (typeof first === 'number' || (typeof first === 'string' && /^\d+$/.test(first))) {
      assertLoopback(typeof args[1] === 'string' ? args[1] : 'localhost', 'socket');
    }
    return realConnect.apply(this, args);
  };
}

export const tmp = (name) => mkdtempSync(join(tmpdir(), `lt-${name}-`));

export function capture(secrets = new SecretSet()) {
  const lines = [];
  const out = createOutput({ secrets, stdout: (s) => lines.push(s), stderr: (s) => lines.push(s) });
  return { out, lines, secrets };
}

/** Every text that left the tool: captured console lines plus every file of the result directory. */
export function allOutputs(dir, lines) {
  const files = readdirSync(dir).map((f) => readFileSync(join(dir, f), 'utf8'));
  return [...lines, ...files].join('\n');
}

export const FAKE_ENV = { LAPIS_LT_PASSWORD: 'unused' };

/** A run against the in-process fake with a virtual clock. Returns everything a test wants to look at. */
export async function dryRun({ args = [], fake: fakeOptions = {}, patch = {}, mediaFakeOptions, mediaFactory: customMediaFactory, wrapMedia } = {}) {
  const dir = tmp('run');
  const clock = new VirtualClock();
  const fake = createFakeServer({ clock, presenceRequiresMedia: true, ...fakeOptions });
  const base = parseConfig(['run', '--dry-run', '--out', dir, ...args], FAKE_ENV);
  const cfg = withOverrides(base, {
    target: 'http://127.0.0.1',
    confirmHost: '127.0.0.1',
    allowedHosts: ['127.0.0.1'],
    allowedLivekitHosts: ['livekit.example.test'],
    allowInsecureLocal: true,
    emailPattern: `lt-{n}@${fake.emailDomain}`,
    password: fake.password,
    spaceId: fake.spaceId,
    ...patch,
  });
  const { out, lines, secrets } = capture();
  // wrapMedia(session, idx) lets a test observe the calls the participants make on their fake media session
  const fakeFactory = wrapMedia ? createMediaFactory('fake', { hub: fake.hub, clock, fakeOptions: mediaFakeOptions }) : null;
  let created = 0;
  const mediaFactory = customMediaFactory ?? (fakeFactory ? (o) => wrapMedia(fakeFactory(o), ++created) : mediaFakeOptions ? createMediaFactory('fake', { hub: fake.hub, clock, fakeOptions: mediaFakeOptions }) : undefined);
  const run = startRun(cfg, { fetchImpl: fake.fetch, clock, hub: fake.hub, out, secrets, mediaFactory });
  const result = await run.done;
  return { result, fake, dir, lines, secrets, cfg, clock };
}

export const ENV_STAGING = {
  LAPIS_LT_ALLOWED_HOSTS: 'staging.example.org',
  LAPIS_LT_ALLOWED_LIVEKIT_HOSTS: 'livekit.example.org',
  LAPIS_LT_EMAIL_PATTERN: 'lt-{n}@example.org',
  LAPIS_LT_PASSWORD: 'secret-password',
};
