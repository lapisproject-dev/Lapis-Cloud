// Command line + environment -> one frozen Config. Secrets come from the environment ONLY.
import { parseArgs } from 'node:util';
import { GuardError, parseTurnOverride } from './guard.mjs';
import { parseBasicAuth } from './basicauth.mjs';

export const COMMANDS = Object.freeze(['run', 'plan', 'cleanup', 'selftest']);
export const MAX_SHARDS = 8;
export const SCENARIO_NAMES = Object.freeze(['A', 'B', 'C', 'D', 'E', 'F']);

const OPTIONS = {
  target: { type: 'string' },
  'confirm-host': { type: 'string' },
  'space-id': { type: 'string' },
  scenario: { type: 'string' },
  participants: { type: 'string' },
  'max-rps': { type: 'string' },
  'duration-min': { type: 'string' },
  media: { type: 'string' },
  'relay-share': { type: 'string' },
  subscribe: { type: 'string' },
  out: { type: 'string' },
  'dry-run': { type: 'boolean' },
  seed: { type: 'string' },
  'name-prefix': { type: 'string' },
  'allow-insecure-local': { type: 'boolean' },
  'accept-presence-loss': { type: 'boolean' },
  'abort-on-foreign-presence': { type: 'string' },
  'unsafe-ignore-client-throttle': { type: 'boolean' },
  'time-scale': { type: 'string' },
  'move-rate': { type: 'string' },
  'tolerate-foreign': { type: 'string' },
  'no-turn': { type: 'boolean' },
  'turn-override': { type: 'string' },
  shard: { type: 'string' },
  processes: { type: 'string' },
  help: { type: 'boolean', short: 'h' },
};

function num(value, name, fallback) {
  if (value === undefined) return fallback;
  const n = Number(value);
  if (!Number.isFinite(n)) throw new GuardError('usage', `--${name} must be a number`);
  return n;
}

function list(value) {
  return (value ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '');
}

export function emailFor(cfg, n) {
  return cfg.emailPattern.replace('{n}', String(n).padStart(2, '0'));
}

export function parseConfig(argv, env = {}) {
  let parsed;
  try {
    parsed = parseArgs({ args: argv, options: OPTIONS, allowPositionals: true, strict: true });
  } catch (e) {
    throw new GuardError('usage', `usage error: ${e.message.split('\n')[0]}`);
  }
  const { values, positionals } = parsed;
  const command = positionals[0] ?? 'help';
  if (command !== 'help' && !COMMANDS.includes(command)) throw new GuardError('usage', `unknown command: ${command.slice(0, 20)}`);
  if (positionals.length > 1) throw new GuardError('usage', 'too many arguments');

  const scenario = (values.scenario ?? 'A').toUpperCase();
  if (!SCENARIO_NAMES.includes(scenario)) throw new GuardError('usage', '--scenario must be one of A, B, C, D, E, F');
  const dryRunRequested = values['dry-run'] === true || command === 'selftest';
  const media = values.media ?? (dryRunRequested ? 'fake' : 'none');
  if (!['none', 'livekit', 'fake'].includes(media)) throw new GuardError('usage', '--media must be none, livekit or fake');
  const subscribe = values.subscribe ?? 'audio';
  if (!['audio', 'audio-passive', 'none'].includes(subscribe)) {
    throw new GuardError('usage', '--subscribe must be audio, audio-passive or none (video is never subscribed)');
  }
  const abortRaw = values['abort-on-foreign-presence'] ?? 'true';
  if (!['true', 'false'].includes(abortRaw)) throw new GuardError('usage', '--abort-on-foreign-presence must be true or false');

  const dryRun = dryRunRequested;
  if (dryRun && media === 'livekit') throw new GuardError('usage', 'a dry run never connects to a real LiveKit: use --media=fake or --media=none');
  const allowInsecureLocal = values['allow-insecure-local'] === true;
  const unsafeIgnoreClientThrottle = values['unsafe-ignore-client-throttle'] === true;
  const moveRate = num(values['move-rate'], 'move-rate', 4);
  if (!(moveRate >= 1 && moveRate <= 10)) throw new GuardError('usage', '--move-rate must be between 1 and 10 (position packets per second of a walking participant)');
  const turnOverride = values['turn-override'] ?? '';
  if (turnOverride !== '') parseTurnOverride(turnOverride);
  const noTurn = values['no-turn'] === true;
  if (noTurn && turnOverride !== '') throw new GuardError('usage', '--no-turn and --turn-override exclude each other');
  if (noTurn && num(values['relay-share'], 'relay-share', 0.15) > 0) {
    throw new GuardError('usage', '--no-turn cannot be combined with a relay share above 0 (give --relay-share 0)');
  }
  const tolerateForeign = num(values['tolerate-foreign'], 'tolerate-foreign', 0);
  if (!Number.isInteger(tolerateForeign) || tolerateForeign < 0 || tolerateForeign > 2) {
    throw new GuardError('usage', '--tolerate-foreign must be 0, 1 or 2 (silent operators who keep an empty room open; every other foreign name still aborts the run)');
  }
  // Sharding: one Node process carries only so many LiveKit connections with constant table changes (the memory of dropped rooms is not freed fast
  // enough). --shard I/K runs only the participants p with (p - 1) % K = I - 1 of the same plan; --processes K starts K such processes and merges them.
  const processes = num(values.processes, 'processes', 1);
  if (!Number.isInteger(processes) || processes < 1 || processes > MAX_SHARDS) throw new GuardError('usage', `--processes must be a whole number between 1 and ${MAX_SHARDS}`);
  let shardIndex = 0;
  let shardCount = 1;
  if (values.shard !== undefined) {
    const m = /^(\d{1,2})\/(\d{1,2})$/.exec(values.shard);
    if (!m || Number(m[1]) < 1 || Number(m[2]) > MAX_SHARDS || Number(m[1]) > Number(m[2])) {
      throw new GuardError('usage', `--shard must have the form I/K with 1 <= I <= K <= ${MAX_SHARDS}`);
    }
    shardIndex = Number(m[1]) - 1;
    shardCount = Number(m[2]);
  }
  if (processes > 1 && values.shard !== undefined) throw new GuardError('usage', '--processes and --shard exclude each other (--processes starts the shards itself)');
  if ((processes > 1 || shardCount > 1) && scenario === 'F') {
    throw new GuardError('usage', 'scenario F cannot be sharded: the delivery ratio of the map packets needs every participant in one process');
  }
  if (processes > 1 && dryRunRequested) throw new GuardError('usage', '--processes needs a real target (a dry run has its fake server inside one process)');
  const timeScale = num(values['time-scale'], 'time-scale', 1);
  if (!(timeScale >= 1 && timeScale <= 1000)) throw new GuardError('usage', '--time-scale must be between 1 and 1000');
  if ((unsafeIgnoreClientThrottle || values['time-scale'] !== undefined) && !(dryRun || allowInsecureLocal)) {
    throw new GuardError('usage', '--unsafe-ignore-client-throttle and --time-scale are only allowed for a dry run or a loopback target');
  }
  if (media === 'fake' && !(dryRun || allowInsecureLocal)) throw new GuardError('usage', '--media=fake is only for dry runs and loopback tests');

  const namePrefix = values['name-prefix'] ?? 'LT-';
  const emailPattern = env.LAPIS_LT_EMAIL_PATTERN ?? '';
  const runStamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..*/, '').replace('T', '-');

  const cfg = {
    command,
    scenario,
    target: values.target ?? env.LAPIS_LT_TARGET ?? '',
    confirmHost: values['confirm-host'] ?? '',
    allowedHosts: list(env.LAPIS_LT_ALLOWED_HOSTS),
    allowedLivekitHosts: list(env.LAPIS_LT_ALLOWED_LIVEKIT_HOSTS),
    allowInsecureLocal,
    spaceId: values['space-id'] ?? '',
    participants: num(values.participants, 'participants', 20),
    maxRps: num(values['max-rps'], 'max-rps', 10),
    durationMin: values['duration-min'] === undefined ? null : num(values['duration-min'], 'duration-min', null),
    media,
    relayShare: num(values['relay-share'], 'relay-share', 0.15),
    subscribe,
    out: values.out ?? `results/${runStamp}-${scenario}`,
    dryRun,
    seed: num(values.seed, 'seed', 1),
    namePrefix,
    emailPattern,
    acceptPresenceLoss: values['accept-presence-loss'] === true,
    abortOnForeignPresence: abortRaw === 'true',
    unsafeIgnoreClientThrottle,
    timeScale,
    moveRate,
    tolerateForeign,
    noTurn,
    turnOverride,
    processes,
    shardIndex,
    shardCount,
    allowedTurnHosts: list(env.LAPIS_LT_ALLOWED_TURN_HOSTS),
    help: values.help === true || command === 'help',
  };
  // The password is never part of an enumerable property: no accidental JSON.stringify / log of the config.
  Object.defineProperty(cfg, 'password', { value: env.LAPIS_LT_PASSWORD ?? '', enumerable: false });
  // HTTP Basic credential for a target behind a guarding reverse proxy (staging): environment only, never part of an enumerable property.
  const basicRaw = env.LAPIS_LT_BASIC_AUTH ?? '';
  if (basicRaw !== '' && parseBasicAuth(basicRaw) === null) throw new GuardError('basic-auth', 'LAPIS_LT_BASIC_AUTH must have the form user:password');
  Object.defineProperty(cfg, 'basicAuth', { value: basicRaw, enumerable: false });
  return Object.freeze(cfg);
}

/** Validates what the account-based commands (run, cleanup) need. */
export function assertAccountConfig(cfg) {
  if (!cfg.emailPattern.includes('{n}')) throw new GuardError('email-pattern', 'LAPIS_LT_EMAIL_PATTERN must contain {n}');
  const local = cfg.emailPattern.split('@')[0].toLowerCase();
  if (!local.startsWith(cfg.namePrefix.toLowerCase())) {
    throw new GuardError('email-pattern', 'the local part of LAPIS_LT_EMAIL_PATTERN must start with the name prefix');
  }
  if (cfg.password === '') throw new GuardError('no-password', 'LAPIS_LT_PASSWORD is not set');
  if (cfg.spaceId === '') throw new GuardError('no-space', '--space-id is required');
}

/** The part of the config that may appear in result files. No host, no account pattern, no secret. */
export function publicConfig(cfg) {
  return {
    scenario: cfg.scenario,
    participants: cfg.participants,
    maxRps: cfg.maxRps,
    durationMin: cfg.durationMin,
    media: cfg.media,
    relayShare: cfg.relayShare,
    subscribe: cfg.subscribe,
    seed: cfg.seed,
    dryRun: cfg.dryRun,
    acceptPresenceLoss: cfg.acceptPresenceLoss,
    abortOnForeignPresence: cfg.abortOnForeignPresence,
    unsafeIgnoreClientThrottle: cfg.unsafeIgnoreClientThrottle,
    timeScale: cfg.timeScale,
    moveRate: cfg.moveRate,
    tolerateForeign: cfg.tolerateForeign,
    noTurn: cfg.noTurn,
    turnOverride: cfg.turnOverride !== '' ? 'set' : '',
    ...(cfg.shardCount > 1 ? { shard: `${cfg.shardIndex + 1}/${cfg.shardCount}` } : {}),
    ...(cfg.processes > 1 ? { processes: cfg.processes } : {}),
  };
}

export const USAGE = `encounter-loadtest (V1.9.161) -- load test for the tables of the encounter room

  encounter-loadtest plan     --scenario A..F [--participants N] [--duration-min M] [--move-rate HZ]   estimate calls per method and minute
  encounter-loadtest run      --target https://HOST --confirm-host HOST --space-id ID --scenario A..F [options]
  encounter-loadtest cleanup  --target https://HOST --confirm-host HOST --space-id ID [--participants N]
  encounter-loadtest selftest [--scenario A..F]       run against the built-in fake server (loopback only)

Secrets and allowlists come from the environment ONLY:
  LAPIS_LT_ALLOWED_HOSTS, LAPIS_LT_ALLOWED_LIVEKIT_HOSTS, LAPIS_LT_EMAIL_PATTERN (with {n}), LAPIS_LT_PASSWORD, LAPIS_LT_TARGET
  LAPIS_LT_BASIC_AUTH (user:password), only if a reverse proxy guards the target with HTTP Basic authentication; sent to the target only
Sharding (one process carries only so many LiveKit connections with constant table changes):
  --processes K   start K processes of this tool (K at most 8) that split the participants, and merge their results into --out
  --shard I/K     run only the I-th of K slices (for several machines; merge with the same K and one --out per machine)
Hard limits: 40 participants, 30 requests per second, 45 minutes. See scripts/loadtest/README.adoc.
`;

/** A copy of the config with some fields replaced (the password stays out of the enumerable properties). */
export function withOverrides(cfg, patch) {
  const copy = { ...cfg, ...patch };
  Object.defineProperty(copy, 'password', { value: patch.password ?? cfg.password, enumerable: false });
  Object.defineProperty(copy, 'basicAuth', { value: patch.basicAuth ?? cfg.basicAuth, enumerable: false });
  return Object.freeze(copy);
}
