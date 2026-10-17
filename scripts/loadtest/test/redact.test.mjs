import { allOutputs, dryRun } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { SecretSet, redact, safeError } from '../lib/redact.mjs';
import { HttpError } from '../lib/http.mjs';
import { GuardError } from '../lib/guard.mjs';

test('redact() removes registered secrets (longest first) and token-shaped strings', () => {
  const secrets = new SecretSet();
  secrets.add('hunter2-hunter2');
  secrets.add('hunter2');
  assert.equal(redact('pw=hunter2-hunter2 and hunter2', secrets), 'pw=[redacted] and [redacted]');
  assert.equal(redact('lapis_session=abc123def456; Path=/', secrets), '[redacted]; Path=/');
  assert.equal(redact('token eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.c2lnbmF0dXJl x', secrets), 'token [redacted] x');
  assert.equal(redact('Authorization: Bearer abcdefghijkl', secrets), 'Authorization: [redacted]');
});

test('safeError() only ever returns the kind, never a message', () => {
  assert.deepEqual(safeError(new HttpError('http', 503)), { kind: 'http', status: 503 });
  assert.deepEqual(safeError(new GuardError('prefix', 'Maria Muster logged in')), { kind: 'guard', code: 'prefix' });
  assert.deepEqual(safeError(new Error('contains SECRET')), { kind: 'error' });
  assert.deepEqual(safeError(null), { kind: 'error' });
});

for (const [label, fake, expectedExit] of [
  ['a normal run', {}, 0],
  ['a failed login and HTTP 500 answers (the run aborts on its error-rate criterion)', { failLoginFor: new Set([3]), serverErrorMethods: new Set(['listPresent']) }, 3],
]) {
  test(`canaries survive ${label} -- in no output at all`, async () => {
    const { result, fake: server, dir, lines, cfg } = await dryRun({ args: ['--scenario', 'A', '--participants', '8'], fake });
    assert.equal(result.exitCode, expectedExit);
    const text = allOutputs(dir, lines);
    assert.ok(text.length > 500, 'there is output to scan');
    const c = server.canaries;
    const forbidden = [
      server.password, // the shared test password
      c.cookie, // session cookie values
      c.token, // plenum join tokens
      c.tableToken, // table tokens
      'TURN-CREDENTIAL', // TURN credentials
      c.memberId, // member ids
      c.errorText, // text of refusals: never reported
      c.body, // bodies of failed responses
      '@example.test', // account e-mail addresses
      'livekit.example.test', // the LiveKit host
      'auth=canary',
    ];
    for (const needle of forbidden) assert.equal(text.includes(needle), false, `leaked: ${needle}`);
    if (expectedExit === 3) {
      assert.ok(result.summary.perMethod.login.errors.http_401 >= 1, 'the failed login happened');
      assert.ok(result.summary.perMethod.listPresent.errors.http_500 >= 1, 'the HTTP 500 happened');
    }
    assert.equal(JSON.stringify(cfg).includes(server.password), false);
  });
}

test('the result files name participants p01..p40 only', async () => {
  const { dir } = await dryRun({ args: ['--scenario', 'B', '--participants', '6'] });
  const { readFileSync } = await import('node:fs');
  const samples = readFileSync(`${dir}/samples.csv`, 'utf8').split('\n').slice(1).filter(Boolean);
  assert.ok(samples.length > 20);
  for (const row of samples) assert.match(row.split(',')[1], /^p\d\d$/);
});
