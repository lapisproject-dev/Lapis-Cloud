import './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { Recorder, csvCell, entryFailureReason, evaluateAbort, isExpectedRejection, percentile, stats, summaryCsv } from '../lib/metrics.mjs';

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
  assert.equal(evaluateAbort([...ok(38), ...bad(2, 'Forbidden')]), null, 'two unexpected calls in 40 are 5 % but not a trend');
  assert.equal(evaluateAbort([...ok(37), ...bad(3, 'Forbidden')]), 'unexpected-error-rate-above-5-percent', 'three in 40 are');
  assert.equal(evaluateAbort([...ok(59), ...ok(1, 7000)]), null, 'one slow call among 60 is the p99 of 60 samples, not a trend');
  assert.equal(evaluateAbort([...ok(98), ...ok(2, 7000)]), 'p99-above-5-seconds', 'two slow calls in 100 are');
});


test('map packet statistics: counters and a histogram, exact percentiles to the millisecond, no block without packets', () => {
  const none = new Recorder({ clock: { now: () => 0 } });
  assert.equal(none.summary().mapPackets, undefined);

  const r = new Recorder({ clock: { now: () => 0 } });
  for (let i = 0; i < 100; i++) r.mapSent(3);
  for (let i = 1; i <= 100; i++) r.mapReceived(i); // delays 1..100 ms, 100 deliveries of the 300 expected
  r.mapReceived(25_000); // far beyond the histogram: shares the last bucket, but max stays exact
  const m = r.summary().mapPackets;
  assert.equal(m.sent, 100);
  assert.equal(m.expectedDeliveries, 300);
  assert.equal(m.received, 101);
  assert.equal(m.deliveryRatio, 0.3367);
  assert.equal(m.delayMs.p50, 51);
  assert.equal(m.delayMs.p95, 96);
  assert.equal(m.delayMs.p99, 100);
  assert.equal(m.delayMs.max, 25_000);
});

import { AudibleBoard } from '../lib/participant.mjs';

test('"heard" is measured from the later of the microphone going on and the listener being connected', () => {
  let now = 0;
  const board = new AudibleBoard({ now: () => now });
  board.joined('early'); // connected at 0
  board.micOn('speaker'); // microphone on at 0
  now = 300;
  assert.equal(board.heard('speaker', 'early'), 300, 'a listener who was there: from the microphone');
  now = 10_000;
  board.joined('late'); // sits down 10 s after the speaker started
  now = 10_250;
  assert.equal(board.heard('speaker', 'late'), 250, 'a late listener: from his own connection, not 10 s late');
  assert.equal(board.heard('speaker', 'late'), null, 'once per listener and episode');
  now = 20_000;
  board.micOn('speaker'); // a new episode
  now = 20_400;
  assert.equal(board.heard('speaker', 'late'), 400);
  board.left('late');
  board.micOn('speaker');
  now = 20_900;
  assert.equal(board.heard('speaker', 'late'), 500, 'no connection time known: from the microphone');
});

test('nobody could enter: only when every participant tried and none was accepted', () => {
  const enter = (outcome) => ({ method: 'enterSpace', outcome, latency: 70 });
  assert.equal(entryFailureReason([enter('Conflict'), enter('Conflict'), enter('Conflict')], 3), 'nobody-could-enter-the-room');
  assert.equal(entryFailureReason([enter('Conflict'), enter('ok'), enter('Conflict')], 3), null, 'one entry is enough to go on');
  assert.equal(entryFailureReason([enter('Conflict'), enter('Conflict')], 3), null, 'not everybody has tried yet');
  assert.equal(entryFailureReason([], 0), null);
  assert.equal(entryFailureReason([{ method: 'login', outcome: 'ok', latency: 1 }], 1), null);
});
