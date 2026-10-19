// --processes K: starts K processes of this tool (each with --shard I/K and its own result directory) and merges their results.
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { mergeShardResults } from './metrics.mjs';

const OWN_FLAGS = new Set(['--processes', '--out', '--shard']);

/** The command line of one shard: the parent's arguments without --processes/--out/--shard, plus the shard and its own output directory. */
export function shardArgv(argv, index, count, outDir) {
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (OWN_FLAGS.has(a)) {
      i += 1; // its value
      continue;
    }
    if ([...OWN_FLAGS].some((f) => a.startsWith(`${f}=`))) continue;
    rest.push(a);
  }
  return [...rest, '--shard', `${index + 1}/${count}`, '--out', join(outDir, `shard-${index + 1}`)];
}

/** One exit code for the whole run: a failure of the tool (2) over a refusal (1) over an abort (3) over a signal (130) over success. */
export function combineExitCodes(codes) {
  for (const c of [2, 1, 3, 130]) if (codes.includes(c)) return c;
  return codes.some((c) => c !== 0) ? 2 : 0;
}

function pipeLines(stream, write) {
  let buffer = '';
  stream.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let nl;
    while ((nl = buffer.indexOf('\n')) >= 0) {
      write(buffer.slice(0, nl));
      buffer = buffer.slice(nl + 1);
    }
  });
  stream.on('end', () => {
    if (buffer !== '') write(buffer);
  });
}

/**
 * Starts the shards, waits for all of them and merges. `onSpawned(children)` lets the caller wire the signals to the children.
 * One shard that ends with a non-zero code stops the others with SIGINT (they clean up their accounts), because the abort criteria
 * look at one shard only and the others hit the same server.
 * @returns {Promise<number>} exit code
 */
export async function launchShards({ argv, cfg, out, script, spawnImpl = spawn, execPath = process.execPath, env = process.env, onSpawned = () => {} }) {
  const count = cfg.processes;
  const children = [];
  const exits = [];
  let stopping = false;
  const stopAll = () => {
    if (stopping) return;
    stopping = true;
    for (const c of children) c.kill('SIGINT');
  };
  out.log(`run: ${count} processes, ${cfg.participants} participants in total, results of the shards in ${cfg.out}/shard-N, merged into ${cfg.out}`);
  for (let i = 0; i < count; i++) {
    const child = spawnImpl(execPath, [script, ...shardArgv(argv, i, count, cfg.out)], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    const tag = `[s${i + 1}] `;
    pipeLines(child.stdout, (l) => out.log(tag + l));
    pipeLines(child.stderr, (l) => out.warn(tag + l));
    exits.push(
      new Promise((resolve) => {
        child.on('error', () => resolve(2));
        child.on('close', (code, signal) => {
          const result = code ?? (signal ? 130 : 2);
          if (result !== 0) stopAll();
          resolve(result);
        });
      }),
    );
    children.push(child);
  }
  onSpawned({ stopAll, children });
  const codes = await Promise.all(exits);
  const dirs = Array.from({ length: count }, (_, i) => join(cfg.out, `shard-${i + 1}`));
  const { summary, merged } = mergeShardResults(dirs, cfg.out, out.redact);
  if (summary === null) out.warn('no shard wrote results: nothing to merge');
  else {
    if (merged < count) out.warn(`only ${merged} of ${count} shards wrote results: the merged numbers are incomplete`);
    out.log(`merged: ${summary.calls} calls, ${summary.unexpectedErrors} unexpected, results in ${cfg.out}`);
  }
  return combineExitCodes(codes);
}
