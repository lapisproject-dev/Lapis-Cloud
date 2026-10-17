// Command line + environment -> one frozen Config. Secrets come from the environment ONLY.
import { parseArgs } from 'node:util';
import { GuardError } from './guard.mjs';

export const COMMANDS = Object.freeze(['run', 'plan', 'cleanup', 'selftest']);
export const SCENARIO_NAMES = Object.freeze(['A', 'B', 'C', 'D', 'E']);

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
  if (!SCENARIO_NAMES.includes(scenario)) throw new GuardError('usage', '--scenario must be one of A, B, C, D, E');
  const dryRunRequested = values['dry-run'] === true || command === 'selftest';
  const media = values.media ?? (dryRunRequested ? 'fake' : 'none');
  if (!['none', 'livekit', 'fake'].includes(media)) throw new GuardError('usage', '--media must be none, livekit or fake');
  const subscribe = values.subscribe ?? 'audio';
  if (!['audio', 'none'].includes(subscribe)) throw new GuardError('usage', '--subscribe must be audio or none (video is never subscribed)');
  const abortRaw = values['abort-on-foreign-presence'] ?? 'true';
  if (!['true', 'false'].includes(abortRaw)) throw new GuardError('usage', '--abort-on-foreign-presence must be true or false');

  const dryRun = dryRunRequested;
  if (dryRun && media === 'livekit') throw new GuardError('usage', 'a dry run never connects to a real LiveKit: use --media=fake or --media=none');
  const allowInsecureLocal = values['allow-insecure-local'] === true;
  const unsafeIgnoreClientThrottle = values['unsafe-ignore-client-throttle'] === true;
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
    help: values.help === true || command === 'help',
  };
  // The password is never part of an enumerable property: no accidental JSON.stringify / log of the config.
  Object.defineProperty(cfg, 'password', { value: env.LAPIS_LT_PASSWORD ?? '', enumerable: false });
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
  };
}

export const USAGE = `encounter-loadtest (V1.9.149) -- load test for the tables of the encounter room

  encounter-loadtest plan     --scenario A..E [--participants N] [--duration-min M]   estimate calls per method and minute
  encounter-loadtest run      --target https://HOST --confirm-host HOST --space-id ID --scenario A..E [options]
  encounter-loadtest cleanup  --target https://HOST --confirm-host HOST --space-id ID [--participants N]
  encounter-loadtest selftest [--scenario A..E]       run against the built-in fake server (loopback only)

Secrets and allowlists come from the environment ONLY:
  LAPIS_LT_ALLOWED_HOSTS, LAPIS_LT_ALLOWED_LIVEKIT_HOSTS, LAPIS_LT_EMAIL_PATTERN (with {n}), LAPIS_LT_PASSWORD, LAPIS_LT_TARGET
Hard limits: 40 participants, 30 requests per second, 45 minutes. See scripts/loadtest/README.adoc.
`;

/** A copy of the config with some fields replaced (the password stays out of the enumerable properties). */
export function withOverrides(cfg, patch) {
  const copy = { ...cfg, ...patch };
  Object.defineProperty(copy, 'password', { value: patch.password ?? cfg.password, enumerable: false });
  return Object.freeze(copy);
}
