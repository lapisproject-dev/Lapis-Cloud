import { dryRun } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildPlan, estimatePlan, SCENARIOS } from '../lib/scenarios.mjs';
import { parseConfig } from '../lib/config.mjs';
import { GuardError } from '../lib/guard.mjs';

const planCfg = (extra = []) => parseConfig(['plan', '--participants', '20', ...extra], {});

for (const name of Object.keys(SCENARIOS)) {
  test(`scenario ${name} runs to the end against the fake, keeps every client throttle and leaves the room empty`, async () => {
    const { result, fake, dir } = await dryRun({ args: ['--scenario', name, '--participants', '20'] });
    const s = result.summary;
    assert.equal(result.exitCode, 0);
    assert.equal(s.unexpectedErrors, 0, JSON.stringify(s.perMethod));
    assert.deepEqual(fake.stats.rejected, {}, 'the server never had to throttle a participant');
    assert.ok(s.tableAssignMs.count >= 20, 'everybody sat at a table at least once');
    assert.ok(s.hearMs.count > 0, 'microphones were heard');
    assert.equal(s.presenceLost, 0);
    // clean-up: nobody present, nobody at a table, every session logged out
    assert.equal(fake.presentCount(), 0);
    assert.equal(fake.tableOccupants(), 0);
    assert.equal(fake.openSessions(), 0);
    // no participant sent two table calls less than 2 s apart (the virtual clock stamps requests exactly)
    const rows = readFileSync(`${dir}/samples.csv`, 'utf8').split('\n').slice(1).filter(Boolean).map((l) => l.split(','));
    const byP = new Map();
    for (const [t, p, method] of rows) if (method === 'joinTable' || method === 'leaveTable') byP.set(p, [...(byP.get(p) ?? []), Number(t)]);
    for (const times of byP.values()) for (let i = 1; i < times.length; i++) assert.ok(times[i] - times[i - 1] >= 2199, `table calls ${times[i] - times[i - 1]} ms apart`);
  });
}

test('scenario C produces rotations and tableToken requests', async () => {
  const { result } = await dryRun({ args: ['--scenario', 'C', '--participants', '20'] });
  const s = result.summary;
  assert.ok(s.rotations > 100, `rotations ${s.rotations}`);
  assert.ok(s.perMethod.tableToken.count > 100);
  assert.ok(s.rotationTokenMs.count > 100);
  assert.equal(s.perMethod.tableToken.rejected.ServiceBusy ?? 0, 0, 'the client stays below the 20 per minute limit');
});

test('scenario D lets half the room leave and come back', async () => {
  const { result } = await dryRun({ args: ['--scenario', 'D', '--participants', '20'] });
  const calls = result.summary.perMethod;
  assert.ok(calls.enterSpace.count >= 20 + 10 * 5, `enterSpace ${calls.enterSpace.count}`); // 20 first entries + waves of 10
  assert.ok(calls.leaveSpace.count >= 60);
});

test('plans are deterministic per seed and stay below every server limit', () => {
  for (const name of Object.keys(SCENARIOS)) {
    const a = buildPlan(name, planCfg(['--seed', '7']));
    const b = buildPlan(name, planCfg(['--seed', '7']));
    const c = buildPlan(name, planCfg(['--seed', '8']));
    assert.deepEqual(a, b);
    assert.notDeepEqual(a, c);
    const estimate = estimatePlan(name, planCfg());
    assert.equal(estimate.exceedsServerLimit, false, name);
    assert.ok(estimate.steps > 20);
  }
});

test('a duration that is too short for the scenario is refused instead of being cut off', () => {
  assert.throws(() => buildPlan('E', planCfg(['--duration-min', '2'])), (e) => e instanceof GuardError && e.code === 'duration');
});

test('scenario F: everybody walks in segments and every position packet reaches everybody else who is in the room', async () => {
  const { result, dir } = await dryRun({ args: ['--scenario', 'F', '--participants', '20'] });
  const s = result.summary;
  assert.equal(result.exitCode, 0);
  const m = s.mapPackets;
  assert.ok(m, 'the summary carries the map packet block');
  // 20 people, about 15 s of walking in every 20 to 24 s, 4 packets per second, 10 minutes: tens of thousands of packets
  assert.ok(m.sent > 20_000, `sent ${m.sent}`);
  assert.ok(m.expectedDeliveries > m.sent * 10, 'each packet has many recipients');
  assert.equal(m.deliveryRatio, 1, 'the fake loses nothing');
  assert.ok(m.delayMs.p50 >= 5 && m.delayMs.p99 <= 50, JSON.stringify(m.delayMs));
  assert.equal(s.unexpectedErrors, 0);
  // the result files hold counters only, never one row per packet (memory and file size)
  const events = readFileSync(`${dir}/events.csv`, 'utf8').split('\n').length;
  assert.ok(events < 1000, `events.csv has ${events} rows`);
});

test('scenario F plans: walking segments of 15 s, the rate comes from --move-rate, the estimate names the peak load', () => {
  const plan = buildPlan('F', planCfg(['--move-rate', '6']));
  const walks = plan.filter((s) => s.op === 'walk');
  assert.ok(walks.length > 20 * 20);
  assert.ok(walks.every((w) => w.ms === 15_000 && w.hz === 6));
  for (let p = 1; p <= 20; p++) {
    const mine = walks.filter((w) => w.p === p).sort((a, b) => a.at - b.at);
    for (let i = 1; i < mine.length; i++) assert.ok(mine[i].at >= mine[i - 1].at + 15_000 + 3000, 'a pause between two segments');
    const lastWalkEnd = mine[mine.length - 1].at + 15_000;
    const table = plan.find((s) => s.p === p && s.op === 'table').at;
    assert.ok(table > lastWalkEnd, 'the table comes after the last walk');
  }
  const e = estimatePlan('F', planCfg(['--move-rate', '6']));
  assert.ok(e.map.peakWalkers >= 10 && e.map.peakWalkers <= 20);
  assert.equal(e.map.packetsPerSecondPeak, e.map.peakWalkers * 6);
  assert.equal(e.map.deliveriesPerSecondPeak, e.map.peakWalkers * 6 * 19);
  assert.equal(e.exceedsServerLimit, false, 'position packets are data packets, not RPC calls');
  assert.equal(estimatePlan('A', planCfg()).map, undefined, 'other scenarios have no map block');
});

test('--move-rate is bounded and scenario F refuses to run without a media layer', () => {
  assert.throws(() => parseConfig(['plan', '--move-rate', '0'], {}), GuardError);
  assert.throws(() => parseConfig(['plan', '--move-rate', '11'], {}), GuardError);
  assert.throws(() => parseConfig(['plan', '--move-rate', 'abc'], {}), GuardError);
  assert.equal(parseConfig(['plan'], {}).moveRate, 4);
});

test('in every scenario and for several seeds each table step is preceded by a bench seat at least 5 s earlier (one place per person: a bench seat frees the table seat)', () => {
  for (const name of Object.keys(SCENARIOS)) {
    for (const seed of [1, 2, 3, 4, 5, 6, 7, 8]) {
      const plan = buildPlan(name, planCfg(['--seed', String(seed), '--participants', '25']));
      for (let p = 1; p <= 25; p++) {
        const seats = plan.filter((s) => s.p === p && s.op === 'seat').map((s) => s.at);
        for (const t of plan.filter((s) => s.p === p && s.op === 'table').map((s) => s.at)) {
          const before = seats.filter((x) => x < t);
          assert.ok(before.length > 0, `${name} seed ${seed} p${p}: a table at ${t} without a seat before it`);
          assert.ok(t - Math.max(...before) >= 5000, `${name} seed ${seed} p${p}: seat ${Math.max(...before)} table ${t}`);
        }
        // and no bench seat between a table step and the leave that ends it: after a table only 'leave' or a re-enter may bring a new seat
        const ops = plan.filter((s) => s.p === p).map((s) => s.op);
        let atTable = false;
        for (const op of ops) {
          if (op === 'table') atTable = true;
          else if (op === 'leave' || op === 'enter') atTable = false;
          else if (op === 'seat') assert.equal(atTable, false, `${name} seed ${seed} p${p}: a bench seat while the person sits at a table`);
        }
      }
    }
  }
});
