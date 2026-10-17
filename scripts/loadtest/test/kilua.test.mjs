import { ENV_STAGING, capture } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { KiluaClient } from '../lib/kilua.mjs';
import { CookieJar, HttpError, postJson } from '../lib/http.mjs';
import { Recorder } from '../lib/metrics.mjs';
import { createFakeServer } from './fake-server.mjs';
import { Clock } from '../lib/scheduler.mjs';

const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
const S = '00000000-0000-4000-8000-0000000000a1';

test('requests are encoded exactly like the committed fixtures (which the Kotlin contract test posts to the real route)', () => {
  const cases = {
    enterSpace: [S, null],
    leaveSpace: [S],
    listPresent: [S],
    selectSeat: [S, 5],
    joinTable: [S, 2, 3],
    leaveTable: [S],
    tableToken: [S],
    listTables: [S],
  };
  for (const [method, params] of Object.entries(cases)) {
    assert.deepEqual(JSON.parse(KiluaClient.encodeRequest(method, params, 1)), fixture(`request-${method}`), method);
  }
  // null as a parameter is the JSON document "null", inside a string
  assert.equal(fixture('request-enterSpace').params[1], 'null');
});

test('answers of the real server decode: values, Unit, typed refusals', () => {
  const token = KiluaClient.decodeResponse(JSON.stringify(fixture('response-tableToken')));
  assert.equal(token.ok, true);
  assert.equal(token.value.token.table, 2);
  assert.equal(token.value.token.join.livekitRoomName, 'lk-room-1');

  const unit = KiluaClient.decodeResponse(JSON.stringify(fixture('response-leaveTable')));
  assert.deepEqual(unit, { ok: true, value: {} });

  assert.equal(KiluaClient.decodeResponse(JSON.stringify(fixture('response-enterSpace'))).value.join.displayName, 'LT-01');
  assert.equal(KiluaClient.decodeResponse(JSON.stringify(fixture('response-listTables'))).value[0].seats, 6);

  for (const type of ['Conflict', 'ServiceBusy', 'Forbidden', 'BadRequest']) {
    const r = KiluaClient.decodeResponse(JSON.stringify(fixture(`response-exception-${type}`)));
    assert.deepEqual(r, { ok: false, kind: 'exception', exceptionType: type });
  }
});

test('garbage decodes to "decode", never throws', () => {
  assert.equal(KiluaClient.decodeResponse('<html>').kind, 'decode');
  assert.equal(KiluaClient.decodeResponse('null').kind, 'decode');
  assert.equal(KiluaClient.decodeResponse('{"result":"not json"}').kind, 'decode');
});

function client(fetchImpl, recorder = new Recorder({ clock: new Clock() }), timeoutMs = 10_000) {
  return { recorder, kilua: new KiluaClient({ baseUrl: 'http://127.0.0.1', jar: new CookieJar(), fetchImpl, recorder, participantIdx: 1, timeoutMs }) };
}

test('call(): HTTP 500, a redirect, non-JSON and an oversized body are outcomes, not exceptions', async () => {
  const cases = [
    [() => new Response('boom', { status: 500 }), 'http_500'],
    [() => new Response(null, { status: 302, headers: { location: 'https://elsewhere.example.org/' } }), 'http_302'],
    [() => new Response('<html>', { status: 200 }), 'decode'],
    [() => new Response('x'.repeat(1024 * 1024 + 1), { status: 200 }), 'decode'],
  ];
  for (const [make, outcome] of cases) {
    const { kilua, recorder } = client(async () => make());
    const r = await kilua.call('tableToken', [S]);
    assert.equal(r.ok, false);
    assert.equal(recorder.samples.at(-1).outcome, outcome);
  }
});

test('call(): a hanging server ends in a timeout outcome', async () => {
  const hang = (_url, init) => new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason)));
  const { kilua, recorder } = client(hang, undefined, 30);
  const r = await kilua.call('listTables', [S]);
  assert.equal(r.kind, 'timeout');
  assert.equal(recorder.samples.at(-1).outcome, 'timeout');
});

test('redirects are never followed', async () => {
  let calls = 0;
  const fetchImpl = async (_url, init) => {
    calls += 1;
    assert.equal(init.redirect, 'manual');
    return new Response(null, { status: 307, headers: { location: 'http://127.0.0.1/other' } });
  };
  await assert.rejects(postJson('http://127.0.0.1/x', '{}', { fetchImpl }), (e) => e instanceof HttpError && e.kind === 'http' && e.status === 307);
  assert.equal(calls, 1);
});

test('over real loopback HTTP: login, cookie, enterSpace, a typed refusal and logout', async () => {
  const clock = new Clock();
  const fake = createFakeServer({ clock });
  const server = await fake.listen();
  try {
    const { login, logout } = await import('../lib/auth.mjs');
    const jar = new CookieJar();
    const recorder = new Recorder({ clock });
    const ok = await login({ baseUrl: server.url, jar, recorder, idx: 1, email: 'lt-01@example.test', password: fake.password });
    assert.equal(ok.ok, true);
    const kilua = new KiluaClient({ baseUrl: server.url, jar, recorder, participantIdx: 1 });
    const entered = await kilua.call('enterSpace', [fake.spaceId, null]);
    assert.equal(entered.ok, true);
    assert.equal(entered.value.join.displayName, 'LT-01');
    const bad = await kilua.call('selectSeat', [fake.spaceId, 9999]);
    assert.deepEqual([bad.kind, bad.exceptionType], ['exception', 'BadRequest']);
    await logout({ baseUrl: server.url, jar, recorder, idx: 1 });
    assert.equal(fake.openSessions(), 0);
    const anon = await kilua.call('listTables', [fake.spaceId]);
    assert.equal(anon.exceptionType, 'Unauthenticated');
    const wrong = await login({ baseUrl: server.url, jar: new CookieJar(), recorder, idx: 2, email: 'lt-02@example.test', password: 'nope' });
    assert.deepEqual(wrong, { ok: false, kind: 'http', status: 401 });
  } finally {
    await server.close();
  }
});

test('unused: staging env fixture stays valid', () => {
  assert.ok(ENV_STAGING.LAPIS_LT_PASSWORD);
  assert.ok(capture().lines);
});
