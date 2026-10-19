import './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { parseBasicAuth, basicAuthSecrets, withBasicAuth } from '../lib/basicauth.mjs';
import { parseConfig } from '../lib/config.mjs';
import { GuardError } from '../lib/guard.mjs';
import { SecretSet, createOutput } from '../lib/redact.mjs';

test('the Basic credential is user:password, the password may contain colons, anything else is refused', () => {
  const ok = parseBasicAuth('tester:pa:ss');
  assert.equal(ok.user, 'tester');
  assert.equal(ok.password, 'pa:ss');
  assert.equal(ok.header, `Basic ${Buffer.from('tester:pa:ss').toString('base64')}`);
  for (const bad of ['', undefined, 'nocolon', ':nopassword'.replace('nopassword', 'x'), 'user:']) assert.equal(parseBasicAuth(bad), null, String(bad));
});

test('the header goes to the confirmed origin only: not to another host, not to another port, and existing headers survive', async () => {
  const seen = [];
  const fake = async (input, init) => {
    seen.push([typeof input === 'string' ? input : input.url, new Headers(init?.headers).get('authorization'), new Headers(init?.headers).get('content-type')]);
    return new Response('{}');
  };
  const f = withBasicAuth(fake, { origin: 'https://staging.example.test', credential: 'u:secretpw' });
  await f('https://staging.example.test/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
  await f('https://staging.example.test:8443/x');
  await f('https://video-staging.example.test/x');
  await f('http://staging.example.test/x');
  assert.equal(seen[0][1], parseBasicAuth('u:secretpw').header);
  assert.equal(seen[0][2], 'application/json', 'content-type is kept');
  assert.deepEqual(seen.slice(1).map((s) => s[1]), [null, null, null]);
  assert.equal(withBasicAuth(fake, { origin: 'https://staging.example.test', credential: '' }), fake, 'no credential, no wrapper');
});

test('a real request against a server that wants Basic authentication gets through, without the credential it does not', async () => {
  const want = parseBasicAuth('lt:pw-123456').header;
  const server = http.createServer((req, res) => {
    res.statusCode = req.headers.authorization === want ? 200 : 401;
    res.end('{}');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal((await fetch(`${origin}/x`)).status, 401);
    const authed = withBasicAuth(fetch, { origin, credential: 'lt:pw-123456' });
    assert.equal((await authed(`${origin}/x`)).status, 200);
    assert.equal((await withBasicAuth(fetch, { origin, credential: 'lt:wrong-password' })(`${origin}/x`)).status, 401);
  } finally {
    await new Promise((r) => server.close(r));
  }
});

test('the credential never appears in any output: not as typed, not as the header, not as the base64 part, and a stray Basic header is cut', () => {
  const credential = 'lt:Zs4-top-secret';
  const secrets = new SecretSet();
  for (const s of basicAuthSecrets(credential)) secrets.add(s);
  const lines = [];
  const out = createOutput({ secrets, stdout: (s) => lines.push(s), stderr: (s) => lines.push(s) });
  const header = parseBasicAuth(credential).header;
  out.log(`credential ${credential} header ${header} base ${header.slice(6)} password Zs4-top-secret`);
  out.warn('Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==');
  const all = lines.join('');
  for (const leak of [credential, header.slice(6), 'Zs4-top-secret', 'QWxhZGRpbjpvcGVuIHNlc2FtZQ']) assert.ok(!all.includes(leak), `leaked: ${leak}`);
});

test('LAPIS_LT_BASIC_AUTH is read from the environment, validated, and is not an enumerable property of the config', () => {
  const cfg = parseConfig(['plan'], { LAPIS_LT_BASIC_AUTH: 'lt:secretpw' });
  assert.equal(cfg.basicAuth, 'lt:secretpw');
  assert.ok(!JSON.stringify(cfg).includes('secretpw'));
  assert.equal(parseConfig(['plan'], {}).basicAuth, '');
  assert.throws(() => parseConfig(['plan'], { LAPIS_LT_BASIC_AUTH: 'nocolon' }), (e) => e instanceof GuardError && e.code === 'basic-auth');
});
