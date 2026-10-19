import { ENV_STAGING, dryRun, tmp } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const BIN = new URL('../bin/encounter-loadtest.mjs', import.meta.url).pathname;
const run = (args, env = {}) => spawnSync(process.execPath, [BIN, ...args], { env: { PATH: process.env.PATH, ...env }, encoding: 'utf8', timeout: 60_000 });

test('--dry-run opens no socket at all and marks the results as dry-run', async () => {
  let connects = 0;
  const original = net.Socket.prototype.connect;
  net.Socket.prototype.connect = function counting(...a) {
    connects += 1;
    return original.apply(this, a);
  };
  try {
    const { dir } = await dryRun({ args: ['--scenario', 'B', '--participants', '5'] });
    assert.equal(connects, 0);
    const summary = JSON.parse(readFileSync(`${dir}/summary.json`, 'utf8'));
    assert.equal(summary.dryRun, true);
    assert.equal(summary.config.dryRun, true);
    assert.equal(summary.toolVersion, '1.9.161');
    assert.equal(JSON.stringify(summary).includes('127.0.0.1'), false, 'no host in the results');
  } finally {
    net.Socket.prototype.connect = original;
  }
});

test('selftest runs end to end in a child process', () => {
  const out = tmp('selftest');
  const r = run(['selftest', '--scenario', 'B', '--participants', '6', '--out', out]);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /DRY RUN/);
  assert.equal(JSON.parse(readFileSync(`${out}/summary.json`, 'utf8')).dryRun, true);
});

test('plan prints the estimate and needs no target', () => {
  const r = run(['plan', '--scenario', 'C', '--participants', '20']);
  assert.equal(r.status, 0, r.stderr);
  const plan = JSON.parse(r.stdout);
  assert.equal(plan.scenario, 'C');
  assert.equal(plan.exceedsServerLimit, false);
  assert.ok(plan.expectedRotationsPerMinute > 0);
});

test('run is refused with exit 1 and a fixed message when the allowlist is empty, the confirmation missing or the media layer absent', () => {
  const base = ['run', '--target', 'https://staging.example.org', '--space-id', 'x', '--scenario', 'A'];
  let r = run(base, { LAPIS_LT_PASSWORD: 'secret-password', LAPIS_LT_EMAIL_PATTERN: 'lt-{n}@example.org' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /empty-allowlist/);
  r = run(base, ENV_STAGING);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /confirm-host/);
  r = run([...base.slice(0, -1), 'C', '--confirm-host', 'staging.example.org'], ENV_STAGING);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /presence-loss/);
  for (const text of [r.stdout, r.stderr]) assert.equal(text.includes('secret-password'), false);
});

test('help and unknown options', () => {
  assert.match(run(['--help']).stdout, /encounter-loadtest/);
  const r = run(['run', '--bogus']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /usage/);
});
