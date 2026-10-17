import './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { tmp } from './helpers.mjs';
import { createFakeServer } from './fake-server.mjs';
import { Clock } from '../lib/scheduler.mjs';
import { installShutdown, cleanupGraceMs, BASE_GRACE_MS } from '../lib/shutdown.mjs';
import { EventEmitter } from 'node:events';

const BIN = new URL('../bin/encounter-loadtest.mjs', import.meta.url).pathname;
const SCALE = 10;

test('SIGINT in the middle of a run: everybody leaves table and room, sessions end, summary is written, exit code 130', async () => {
  const fake = createFakeServer({ clock: new Clock({ scale: SCALE }) });
  const server = await fake.listen();
  const out = tmp('shutdown');
  const child = spawn(
    process.execPath,
    [BIN, 'run', '--target', server.url, '--confirm-host', '127.0.0.1', '--allow-insecure-local', '--space-id', fake.spaceId, '--scenario', 'A', '--participants', '5', '--media', 'none', '--time-scale', String(SCALE), '--out', out],
    {
      env: {
        PATH: process.env.PATH,
        LAPIS_LT_ALLOWED_HOSTS: '127.0.0.1',
        LAPIS_LT_EMAIL_PATTERN: `lt-{n}@${fake.emailDomain}`,
        LAPIS_LT_PASSWORD: fake.password,
      },
    },
  );
  let stderr = '';
  child.stderr.on('data', (d) => (stderr += d));
  const exited = new Promise((resolve) => child.on('exit', (code, signal) => resolve({ code, signal })));
  try {
    const deadline = Date.now() + 20_000;
    while (fake.tableOccupants() < 2 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50));
    assert.ok(fake.presentCount() >= 2 && fake.tableOccupants() >= 2, 'people sit at tables when the signal arrives');
    child.kill('SIGINT');
    const { code } = await exited;
    assert.equal(code, 130, stderr);
    assert.equal(fake.presentCount(), 0, 'nobody left in the room');
    assert.equal(fake.tableOccupants(), 0, 'nobody left at a table');
    assert.equal(fake.openSessions(), 0, 'everybody logged out');
    assert.ok(fake.stats.logouts >= 2);
    assert.ok(existsSync(`${out}/summary.json`));
    assert.equal(JSON.parse(readFileSync(`${out}/summary.json`, 'utf8')).abortReason, 'signal');
  } finally {
    child.kill('SIGKILL');
    await server.close();
  }
});

test('the second signal and the end of the grace period end the process at once', async () => {
  const proc = new EventEmitter();
  const exits = [];
  let aborts = 0;
  const stop = installShutdown(() => (aborts += 1), { graceMs: 30, exit: (c) => exits.push(c), proc });
  proc.emit('SIGINT');
  assert.equal(aborts, 1);
  assert.deepEqual(exits, []);
  proc.emit('SIGTERM');
  assert.deepEqual(exits, [130]);
  stop();
  const proc2 = new EventEmitter();
  const exits2 = [];
  const stop2 = installShutdown(() => {}, { graceMs: 20, exit: (c) => exits2.push(c), proc: proc2 });
  proc2.emit('SIGINT');
  await new Promise((r) => setTimeout(r, 60));
  assert.deepEqual(exits2, [130]);
  stop2();
});

test('the grace period grows with the clean-up volume (participants, --max-rps, client throttle)', () => {
  const base = { participants: 40, maxRps: 2, unsafeIgnoreClientThrottle: false };
  // 40 x (leaveTable + leaveSpace + logout) at 2 rps = 60 s through the bucket alone, far above the old fixed 15 s
  assert.ok(cleanupGraceMs(base) >= BASE_GRACE_MS + 60_000);
  assert.ok(cleanupGraceMs({ ...base, maxRps: 10 }) < cleanupGraceMs(base));
  assert.ok(cleanupGraceMs({ ...base, unsafeIgnoreClientThrottle: true }) < cleanupGraceMs(base));
  assert.equal(cleanupGraceMs({ participants: 0, maxRps: 10, unsafeIgnoreClientThrottle: true }), BASE_GRACE_MS);
});
