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
