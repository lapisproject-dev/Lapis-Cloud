import { allOutputs, dryRun } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('an account without the test name prefix is refused and that participant sends nothing more', async () => {
  const { result, fake, dir, lines } = await dryRun({
    args: ['--scenario', 'A', '--participants', '6', '--abort-on-foreign-presence', 'false'], // that account is in the room, so the watcher would stop the run too
    fake: { wrongNameFor: new Set([3]) },
  });
  assert.equal(result.exitCode, 3);
  assert.equal(result.summary.participantsAbortedByGuard, 1);
  const rows = readFileSync(`${dir}/samples.csv`, 'utf8').split('\n').slice(1).filter(Boolean).map((l) => l.split(','));
  const p3 = rows.filter((r) => r[1] === 'p03').map((r) => r[2]);
  assert.deepEqual(p3, ['login', 'enterSpace'], 'login and the one enterSpace that revealed the name; no RPC afterwards, not even a leave');
  assert.equal(rows.filter((r) => r[1] === 'p02').length > 5, true, 'the others carry on');
  // the foreign display name is never printed or stored
  assert.equal(allOutputs(dir, lines).includes('Fremd'), false);
  assert.match(lines.join(''), /participant-prefix: p03/);
  assert.equal(fake.openSessions(), 1, 'the tool does not touch that account any more (no logout either); the operator removes it');
});

test('a foreign person in listPresent stops the run, cleans up and keeps the name out of every output', async () => {
  const { result, fake, dir, lines } = await dryRun({ args: ['--scenario', 'A', '--participants', '6'], fake: { foreignPresent: true } });
  assert.equal(result.exitCode, 3);
  assert.equal(result.abortReason, 'foreign-presence');
  assert.equal(fake.presentCount(), 0, 'our own test accounts left');
  assert.equal(fake.tableOccupants(), 0);
  assert.equal(fake.openSessions(), 0);
  const text = allOutputs(dir, lines);
  assert.equal(text.includes('Fremde'), false);
  assert.equal(text.includes('PULPIT'), false);
  assert.ok(result.summary.abortReason === 'foreign-presence');
});

test('--abort-on-foreign-presence=false only counts', async () => {
  const { result } = await dryRun({ args: ['--scenario', 'B', '--participants', '4', '--abort-on-foreign-presence', 'false'], fake: { foreignPresent: true } });
  assert.equal(result.exitCode, 0);
});
