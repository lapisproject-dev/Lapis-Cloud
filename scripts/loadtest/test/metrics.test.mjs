import './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { Recorder, csvCell, evaluateAbort, isExpectedRejection, percentile, stats, summaryCsv } from '../lib/metrics.mjs';

test('nearest-rank percentiles on known series', () => {
  const ten = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  assert.equal(percentile(ten, 50), 5);
  assert.equal(percentile(ten, 95), 10);
  assert.equal(percentile(ten, 99), 10);
  const five = [15, 20, 35, 40, 50];
  assert.equal(percentile(five, 30), 20);
  assert.equal(percentile(five, 40), 20);
  assert.equal(percentile(five, 50), 35);
  assert.equal(percentile(five, 100), 50);
  assert.equal(percentile([], 50), null);
  assert.deepEqual(stats([3, 1, 2]), { count: 3, p50: 2, p95: 3, p99: 3, max: 3 });
});

test('CSV cells are escaped', () => {
  assert.equal(csvCell('plain'), 'plain');
  assert.equal(csvCell('a,b'), '"a,b"');
  assert.equal(csvCell('say "hi"'), '"say ""hi"""');
  assert.equal(csvCell('line\nbreak'), '"line\nbreak"');
  assert.equal(csvCell(null), '');
});

test('summary.json has the documented keys', () => {
  const r = new Recorder();
  r.sample('joinTable', 12, 'ok', 1);
  r.sample('joinTable', 30, 'Conflict', 2);
  r.sample('joinTable', 20, 'ServiceBusy', 2);
  r.sample('tableToken', 8, 'http_500', 3);
  r.sample('listTables', 8, 'timeout', 3);
  r.mark('table_assigned', 1, 120);
  r.mark('heard', 2, 900);
  r.mark('rotation', 2);
  r.mark('rotation_token', 2, 450);
  r.mark('disconnect:ROOM_DELETED', 3);
  r.mark('presence_lost', 3);
  const s = r.summary();
  for (const key of ['calls', 'perMethod', 'rejectedExpected', 'rejectedUnexpected', 'httpErrors', 'timeouts', 'unexpectedErrors', 'unexpectedErrorRate', 'tableAssignMs', 'hearMs', 'rotationTokenMs', 'rotations', 'presenceLost', 'tableLost', 'disconnects']) {
    assert.ok(key in s, key);
  }
  assert.deepEqual(s.perMethod.joinTable.rejected, { Conflict: 1, ServiceBusy: 1 });
  assert.equal(s.perMethod.joinTable.ok, 1);
  assert.equal(s.rejectedExpected, 1); // somebody took the seat first
  assert.equal(s.rejectedUnexpected, 1); // a throttle answer while the client throttle is on
  assert.equal(s.httpErrors, 1);
  assert.equal(s.timeouts, 1);
  assert.equal(s.unexpectedErrors, 3);
  assert.equal(s.tableAssignMs.p50, 120);
  assert.equal(s.hearMs.p50, 900);
  assert.deepEqual(s.disconnects, { ROOM_DELETED: 1 });
  assert.match(summaryCsv(s), /^key,value\ncalls,5\n/);
});

test('with the client throttle off, throttle answers are the expected result', () => {
  assert.equal(isExpectedRejection('selectSeat', 'ServiceBusy'), false);
  assert.equal(isExpectedRejection('selectSeat', 'ServiceBusy', { expectThrottle: true }), true);
  assert.equal(isExpectedRejection('listTables', 'Conflict', { expectThrottle: true }), true);
  assert.equal(isExpectedRejection('selectSeat', 'Forbidden', { expectThrottle: true }), false);
  assert.equal(isExpectedRejection('joinTable', 'http_500', { expectThrottle: true }), false);
});

test('abort criteria of the run sheet', () => {
  const ok = (n, latency = 10) => Array.from({ length: n }, () => ({ method: 'joinTable', outcome: 'ok', latency }));
  const bad = (n, outcome) => Array.from({ length: n }, () => ({ method: 'joinTable', outcome, latency: 10 }));
  assert.equal(evaluateAbort(ok(100)), null);
  assert.equal(evaluateAbort([...ok(94), ...bad(6, 'Forbidden')]), 'unexpected-error-rate-above-5-percent');
  assert.equal(evaluateAbort([...ok(96), ...bad(4, 'Forbidden')]), null);
  assert.equal(evaluateAbort(bad(5, 'Forbidden')), null, 'too few calls to judge');
  assert.equal(evaluateAbort([...ok(50), ...bad(11, 'http_503')]), 'more-than-10-server-errors-per-minute');
  assert.equal(evaluateAbort(ok(100, 6000)), 'p99-above-5-seconds');
  assert.equal(evaluateAbort([...ok(94), ...bad(6, 'Conflict')]), null, 'a taken seat is part of normal play');
});
