import { dryRun } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { createFakeServer } from './fake-server.mjs';
import { VirtualClock } from '../lib/scheduler.mjs';
import { KiluaClient } from '../lib/kilua.mjs';
import { CookieJar } from '../lib/http.mjs';
import { login } from '../lib/auth.mjs';

test('the fake enforces the server limits: seat 1/s, table 1/2 s, tableToken 20/min, list 60/min', async () => {
  const clock = new VirtualClock();
  const fake = createFakeServer({ clock });
  const jar = new CookieJar();
  await login({ baseUrl: 'http://127.0.0.1', jar, fetchImpl: fake.fetch, idx: 1, email: 'lt-01@example.test', password: fake.password });
  const kilua = new KiluaClient({ baseUrl: 'http://127.0.0.1', jar, fetchImpl: fake.fetch, participantIdx: 1 });
  assert.equal((await kilua.call('enterSpace', [fake.spaceId, null])).ok, true);

  assert.equal((await kilua.call('selectSeat', [fake.spaceId, 1])).ok, true);
  const tooFast = await kilua.call('selectSeat', [fake.spaceId, 2]);
  assert.deepEqual([tooFast.kind, tooFast.exceptionType], ['exception', 'ServiceBusy']);
  await clock.sleep(1001);
  assert.equal((await kilua.call('selectSeat', [fake.spaceId, 2])).ok, true);

  assert.equal((await kilua.call('joinTable', [fake.spaceId, 0, 0])).ok, true);
  assert.equal((await kilua.call('joinTable', [fake.spaceId, 1, 0])).exceptionType, 'ServiceBusy');
  await clock.sleep(2001);
  assert.equal((await kilua.call('joinTable', [fake.spaceId, 1, 0])).ok, true);

  await clock.sleep(60_000);
  let busy = 0;
  for (let i = 0; i < 25; i++) if ((await kilua.call('tableToken', [fake.spaceId])).exceptionType === 'ServiceBusy') busy += 1;
  assert.equal(busy, 5);

  await clock.sleep(60_000);
  let conflicts = 0;
  for (let i = 0; i < 65; i++) if ((await kilua.call('listTables', [fake.spaceId])).exceptionType === 'Conflict') conflicts += 1;
  assert.equal(conflicts, 5, 'a list rate limit is a ConflictException (requireWithinRate), not ServiceBusy');
});

test('with the client throttle off (loopback/dry run only) throttle answers are counted as the expected result', async () => {
  const { result, fake } = await dryRun({ args: ['--scenario', 'C', '--participants', '20', '--unsafe-ignore-client-throttle'] });
  const s = result.summary;
  const busy = Object.values(s.perMethod).reduce((n, m) => n + (m.rejected.ServiceBusy ?? 0), 0);
  assert.ok(busy > 0, 'the server throttled somebody');
  assert.ok(Object.keys(fake.stats.rejected).length > 0);
  assert.ok(s.rejectedExpected > 0);
  assert.equal(s.unexpectedErrors, 0);
});
