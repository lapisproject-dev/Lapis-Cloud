import './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseConfig } from '../lib/config.mjs';
import { ENV_STAGING } from './helpers.mjs';
import { assertCaps, assertLiveKitAllowed, assertTargetAllowed, assertTestIdentity, GuardError } from '../lib/guard.mjs';

const cfgOf = (args, env = ENV_STAGING) => parseConfig(['run', '--space-id', 'x', ...args], env);
const refused = (fn, code) => assert.throws(fn, (e) => e instanceof GuardError && e.code === code, `expected ${code}`);

test('a target that is allowlisted and confirmed passes', () => {
  const r = assertTargetAllowed(cfgOf(['--target', 'https://staging.example.org', '--confirm-host', 'staging.example.org']));
  assert.equal(r.host, 'staging.example.org');
});

test('an empty allowlist allows nothing', () => {
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://staging.example.org', '--confirm-host', 'staging.example.org'], {})), 'empty-allowlist');
});

test('a host that is not on the list is refused', () => {
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://other.example.org', '--confirm-host', 'other.example.org'])), 'host-not-allowed');
});

test('--confirm-host must be present and exactly equal', () => {
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://staging.example.org'])), 'confirm-host');
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://staging.example.org', '--confirm-host', 'Staging.example.org'])), 'confirm-host');
});

test('http, IP literals, user info, paths and wildcard entries are refused', () => {
  const confirm = (h) => ['--confirm-host', h];
  refused(() => assertTargetAllowed(cfgOf(['--target', 'http://staging.example.org', ...confirm('staging.example.org')])), 'scheme');
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://203.0.113.7', ...confirm('203.0.113.7')], { ...ENV_STAGING, LAPIS_LT_ALLOWED_HOSTS: '203.0.113.7' })), 'ip-literal');
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://u:p@staging.example.org', ...confirm('staging.example.org')])), 'userinfo');
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://staging.example.org/app', ...confirm('staging.example.org')])), 'target-path');
  refused(
    () => assertTargetAllowed(cfgOf(['--target', 'https://a.example.org', ...confirm('a.example.org')], { ...ENV_STAGING, LAPIS_LT_ALLOWED_HOSTS: '*.example.org' })),
    'allowlist-entry',
  );
});

test('loopback only works with --allow-insecure-local, and still needs allowlist and confirmation', () => {
  const env = { ...ENV_STAGING, LAPIS_LT_ALLOWED_HOSTS: '127.0.0.1' };
  const base = ['--target', 'http://127.0.0.1:8080', '--confirm-host', '127.0.0.1'];
  refused(() => assertTargetAllowed(cfgOf(base, env)), 'scheme');
  refused(() => assertTargetAllowed(cfgOf(['--target', 'https://127.0.0.1', '--confirm-host', '127.0.0.1'], env)), 'loopback');
  assert.equal(assertTargetAllowed(cfgOf([...base, '--allow-insecure-local'], env)).host, '127.0.0.1');
  refused(() => assertTargetAllowed(cfgOf([...base, '--allow-insecure-local', '--confirm-host', 'localhost'], env)), 'confirm-host');
});

test('hard caps: 41 participants, 31 requests per second, 46 minutes', () => {
  refused(() => assertCaps(cfgOf(['--participants', '41'])), 'cap-participants');
  refused(() => assertCaps(cfgOf(['--max-rps', '31'])), 'cap-rps');
  refused(() => assertCaps(cfgOf(['--duration-min', '46'])), 'cap-duration');
  assert.doesNotThrow(() => assertCaps(cfgOf(['--participants', '40', '--max-rps', '30', '--duration-min', '45'])));
});

test('the LiveKit URL of a join answer is checked against its own allowlist', () => {
  const cfg = cfgOf([]);
  assert.equal(assertLiveKitAllowed('wss://livekit.example.org/?x=1', cfg), 'livekit.example.org');
  refused(() => assertLiveKitAllowed('wss://evil.example.org', cfg), 'livekit-host');
  refused(() => assertLiveKitAllowed('ws://livekit.example.org', cfg), 'livekit-scheme');
  refused(() => assertLiveKitAllowed('wss://livekit.example.org', cfgOf([], { ...ENV_STAGING, LAPIS_LT_ALLOWED_LIVEKIT_HOSTS: '' })), 'livekit-empty-allowlist');
  refused(() => assertLiveKitAllowed('wss://u:p@livekit.example.org', cfg), 'userinfo');
});

test('only accounts with the test name prefix may act', () => {
  assert.doesNotThrow(() => assertTestIdentity('LT-07', 'LT-'));
  refused(() => assertTestIdentity('Maria Muster', 'LT-'), 'prefix');
  refused(() => assertTestIdentity(undefined, 'LT-'), 'prefix');
  refused(() => assertTestIdentity('LT-07', ''), 'no-prefix');
});

test('secrets are not enumerable on the config and unsafe options need loopback or a dry run', () => {
  const cfg = cfgOf([]);
  assert.equal(JSON.stringify(cfg).includes('secret-password'), false);
  assert.throws(() => cfgOf(['--unsafe-ignore-client-throttle']), (e) => e instanceof GuardError && e.code === 'usage');
  assert.throws(() => cfgOf(['--time-scale', '10']), (e) => e instanceof GuardError && e.code === 'usage');
  assert.throws(() => cfgOf(['--media', 'fake']), (e) => e instanceof GuardError && e.code === 'usage');
  assert.throws(() => cfgOf(['--bogus']), (e) => e instanceof GuardError && e.code === 'usage');
});
