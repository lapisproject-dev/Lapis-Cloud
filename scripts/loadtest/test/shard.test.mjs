import { FAKE_ENV, dryRun, tmp, capture } from './helpers.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseConfig } from '../lib/config.mjs';
import { GuardError } from '../lib/guard.mjs';
import { combineExitCodes, launchShards, shardArgv } from '../lib/launcher.mjs';
import { mergeShardResults, readCsvRows } from '../lib/metrics.mjs';

const parse = (args) => parseConfig(['run', ...args], FAKE_ENV);
const refused = (args, re) => assert.throws(() => parse(args), (e) => e instanceof GuardError && re.test(e.message));

test('--shard I/K and --processes K are parsed and checked', () => {
  const c = parse(['--shard', '2/3']);
  assert.equal(c.shardIndex, 1);
  assert.equal(c.shardCount, 3);
  assert.equal(parse([]).shardCount, 1);
  assert.equal(parse(['--processes', '4']).processes, 4);
  for (const bad of ['0/3', '4/3', '1/9', '2', 'a/b', '1/0']) refused(['--shard', bad], /--shard must have the form/);
  refused(['--processes', '9'], /--processes must be/);
  refused(['--processes', '2', '--shard', '1/2'], /exclude each other/);
  refused(['--scenario', 'F', '--shard', '1/2'], /cannot be sharded/);
  refused(['--scenario', 'F', '--processes', '2'], /cannot be sharded/);
  refused(['--processes', '2', '--dry-run'], /needs a real target/);
});

test('a shard carries only its own participants and keeps their numbers', async () => {
  const { dir, result } = await dryRun({ args: ['--scenario', 'A', '--participants', '7', '--shard', '2/3'] });
  const events = readCsvRows(readFileSync(join(dir, 'samples.csv'), 'utf8'));
  const who = [...new Set(events.map((r) => r[1]))].sort();
  assert.deepEqual(who, ['p02', 'p05']); // (p - 1) % 3 === 1
  assert.equal(result.summary.perMethod.login.count, 2);
  assert.equal(result.summary.config.shard, '2/3');
});

test('the shards together do what one process does: same participants, same relay draw', async () => {
  const args = ['--scenario', 'A', '--participants', '6', '--relay-share', '0.5'];
  const whole = await dryRun({ args });
  const a = await dryRun({ args: [...args, '--shard', '1/2'] });
  const b = await dryRun({ args: [...args, '--shard', '2/2'] });
  assert.equal(a.result.summary.perMethod.login.count + b.result.summary.perMethod.login.count, whole.result.summary.perMethod.login.count);
  const merged = tmp('merged');
  const { summary, merged: n } = mergeShardResults([a.dir, b.dir], merged);
  assert.equal(n, 2);
  assert.equal(summary.perMethod.login.count, whole.result.summary.perMethod.login.count);
  assert.equal(summary.calls, a.result.summary.calls + b.result.summary.calls);
  assert.equal(summary.shardsMerged, 2);
  assert.equal(summary.config.shard, undefined);
  assert.equal(summary.config.processes, 2);
  for (const f of ['samples.csv', 'events.csv', 'summary.json', 'summary.csv']) assert.ok(existsSync(join(merged, f)), f);
});

test('the merge recomputes percentiles from all samples and sums the counters', async () => {
  const { mkdirSync, writeFileSync } = await import('node:fs');
  const dirs = [];
  const latencies = [[10, 20], [30, 1000]];
  latencies.forEach((ls, i) => {
    const dir = tmp('shard');
    dirs.push(dir);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'samples.csv'), 't_ms,p,method,latency_ms,outcome\n' + ls.map((l, k) => `${k * 100 + i},p0${i + 1},joinTable,${l}.0,ok`).join('\n') + '\n');
    writeFileSync(join(dir, 'events.csv'), 't_ms,p,event,value_ms\n' + `${i},p0${i + 1},table_assigned,${100 * (i + 1)}\n`);
    writeFileSync(join(dir, 'summary.json'), JSON.stringify({ toolVersion: 't', dryRun: false, media: 'livekit', config: { shard: `${i + 1}/2` }, virtualDurationMs: 5000 + i, abortReason: i === 1 ? 'p99-above-5-seconds' : 'signal', participantsAbortedByGuard: i, nativeErrors: 2 }));
  });
  const { summary } = mergeShardResults(dirs, tmp('m'));
  assert.equal(summary.calls, 4);
  assert.equal(summary.perMethod.joinTable.p99, 1000);
  assert.equal(summary.perMethod.joinTable.p50, 20);
  assert.equal(summary.tableAssignMs.count, 2);
  assert.equal(summary.virtualDurationMs, 5001);
  assert.equal(summary.abortReason, 'p99-above-5-seconds', 'a real abort reason wins over a signal');
  assert.equal(summary.participantsAbortedByGuard, 1);
  assert.equal(summary.nativeErrors, 4);
});

test('a missing shard result is reported, a present one still merges', async () => {
  const a = await dryRun({ args: ['--scenario', 'A', '--participants', '4', '--shard', '1/2'] });
  const { merged, summary } = mergeShardResults([a.dir, join(tmp('nothing'), 'shard-2')], tmp('out'));
  assert.equal(merged, 1);
  assert.ok(summary.calls > 0);
  assert.deepEqual(mergeShardResults([join(tmp('x'), 'a')], tmp('out2')), { summary: null, merged: 0 });
});

test('shardArgv drops the parent options and adds the shard and its directory', () => {
  const argv = ['run', '--target', 'https://t.example', '--processes', '3', '--out=results/x', '--scenario', 'C', '--shard', '9/9'];
  assert.deepEqual(shardArgv(argv, 1, 3, 'results/x'), ['run', '--target', 'https://t.example', '--scenario', 'C', '--shard', '2/3', '--out', 'results/x/shard-2']);
});

test('combineExitCodes: failure over refusal over abort over signal', () => {
  assert.equal(combineExitCodes([0, 0]), 0);
  assert.equal(combineExitCodes([0, 130]), 130);
  assert.equal(combineExitCodes([3, 130]), 3);
  assert.equal(combineExitCodes([3, 1]), 1);
  assert.equal(combineExitCodes([3, 2, 1]), 2);
  assert.equal(combineExitCodes([0, 7]), 2);
});

test('launchShards starts K children, prefixes their output, stops the others when one fails and merges', async () => {
  const shards = [];
  for (let i = 1; i <= 2; i++) shards.push(await dryRun({ args: ['--scenario', 'A', '--participants', '4', '--shard', `${i}/2`] }));
  const outDir = tmp('launch');
  const cfg = parseConfig(['run', '--processes', '2', '--participants', '4', '--out', outDir], FAKE_ENV);
  const { out, lines } = capture();
  const spawned = [];
  const spawnImpl = (exe, args) => {
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.killed = [];
    child.kill = (sig) => {
      child.killed.push(sig);
      setImmediate(() => child.emit('close', null, sig));
    };
    const index = spawned.length;
    spawned.push({ exe, args, child });
    // the "child" already has its results in place (copied below) and ends: the first with 3, the second only after it was told to stop
    child.stdout.write(`hello ${index + 1}\n`);
    if (index === 0) setImmediate(() => child.emit('close', 3, null));
    return child;
  };
  // put the prepared shard results where the launcher looks for them
  const { cpSync } = await import('node:fs');
  shards.forEach((s, i) => cpSync(s.dir, join(outDir, `shard-${i + 1}`), { recursive: true }));
  const code = await launchShards({ argv: ['run', '--processes', '2', '--participants', '4', '--out', outDir], cfg, out, script: '/x/cli.mjs', spawnImpl, execPath: 'node' });
  assert.equal(spawned.length, 2);
  assert.deepEqual(spawned[1].args.slice(-4), ['--shard', '2/2', '--out', join(outDir, 'shard-2')]);
  assert.deepEqual(spawned[1].child.killed, ['SIGINT'], 'the second shard was told to stop');
  assert.equal(code, 3);
  assert.ok(lines.some((l) => l.includes('[s1] hello 1')) && lines.some((l) => l.includes('[s2] hello 2')));
  assert.ok(lines.some((l) => l.includes('merged:')));
  assert.ok(existsSync(join(outDir, 'summary.json')));
});

test('scenario C never moves one person to another table sooner than the server cooldown allows', async () => {
  const { dir } = await dryRun({ args: ['--scenario', 'C', '--participants', '4'] });
  const rows = readCsvRows(readFileSync(join(dir, 'samples.csv'), 'utf8')).filter((r) => r[2] === 'joinTable' && r[4] === 'ok');
  const last = new Map();
  let moves = 0;
  for (const [t, p, , latency] of rows) {
    const startedAt = Number(t) - Number(latency); // a sample is written when the call ends
    if (last.has(p)) {
      moves += 1;
      assert.ok(startedAt - last.get(p) >= 10_000, `${p} moved after ${startedAt - last.get(p)} ms`);
    }
    last.set(p, startedAt);
  }
  assert.ok(moves >= 4, `only ${moves} moves seen`);
});
