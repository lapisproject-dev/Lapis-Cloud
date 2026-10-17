// Safety rails. Everything here fails CLOSED: an empty allowlist allows nothing.
import net from 'node:net';

export const HARD_LIMITS = Object.freeze({ participants: 40, maxRps: 30, durationMin: 45 });

export class GuardError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'GuardError';
    this.code = code;
  }
}

const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]', '::1']);

export function isLoopbackHost(host) {
  return LOOPBACK.has(host);
}

function isIpLiteral(host) {
  return net.isIP(host.replace(/^\[|\]$/g, '')) !== 0;
}

function validateAllowlistEntry(entry, { allowLoopback }) {
  if (entry === '' || /[*?\s/@:]/.test(entry)) {
    throw new GuardError('allowlist-entry', 'allowlist entries must be exact host names (no wildcard, port, path or user info)');
  }
  if (isLoopbackHost(entry)) {
    if (!allowLoopback) throw new GuardError('allowlist-loopback', 'a loopback entry needs --allow-insecure-local');
    return;
  }
  if (isIpLiteral(entry)) {
    throw new GuardError('allowlist-ip', 'IP literals are not allowed in an allowlist');
  }
}

function parseUrl(value, what) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new GuardError('url', `${what} is not a valid URL`);
  }
  if (url.username !== '' || url.password !== '') throw new GuardError('userinfo', `${what} must not carry user info`);
  return url;
}

/** The target must be an exact host from LAPIS_LT_ALLOWED_HOSTS, confirmed by --confirm-host, https only. */
export function assertTargetAllowed(cfg) {
  if (!cfg.target) throw new GuardError('no-target', 'no --target given');
  const url = parseUrl(cfg.target, 'the target');
  if (url.pathname !== '/' || url.search !== '' || url.hash !== '') {
    throw new GuardError('target-path', 'the target must be a bare origin (no path, query or fragment)');
  }
  const host = url.hostname;
  const loopback = isLoopbackHost(host);
  if (url.protocol !== 'https:') {
    if (!(url.protocol === 'http:' && loopback && cfg.allowInsecureLocal)) {
      throw new GuardError('scheme', 'only https targets are allowed (http only for loopback with --allow-insecure-local)');
    }
  }
  if (loopback && !cfg.allowInsecureLocal) {
    throw new GuardError('loopback', 'a loopback target needs --allow-insecure-local');
  }
  if (!loopback && isIpLiteral(host)) throw new GuardError('ip-literal', 'IP literals are not allowed as a target');
  const allowed = cfg.allowedHosts ?? [];
  if (allowed.length === 0) throw new GuardError('empty-allowlist', 'LAPIS_LT_ALLOWED_HOSTS is empty: nothing is allowed');
  for (const entry of allowed) validateAllowlistEntry(entry, { allowLoopback: cfg.allowInsecureLocal });
  if (!allowed.includes(host)) throw new GuardError('host-not-allowed', 'the target host is not in LAPIS_LT_ALLOWED_HOSTS');
  if (cfg.confirmHost !== host) throw new GuardError('confirm-host', '--confirm-host must be exactly the target host');
  return { host, origin: url.origin };
}

/** The LiveKit server URL that the join answer hands out is checked before the media layer connects. */
export function assertLiveKitAllowed(serverUrl, cfg) {
  const url = parseUrl(serverUrl, 'the LiveKit URL');
  const host = url.hostname;
  const loopback = isLoopbackHost(host);
  if (url.protocol !== 'wss:' && !(url.protocol === 'ws:' && loopback && cfg.allowInsecureLocal)) {
    throw new GuardError('livekit-scheme', 'the LiveKit URL must be wss:');
  }
  const allowed = cfg.allowedLivekitHosts ?? [];
  if (allowed.length === 0) throw new GuardError('livekit-empty-allowlist', 'LAPIS_LT_ALLOWED_LIVEKIT_HOSTS is empty: nothing is allowed');
  for (const entry of allowed) validateAllowlistEntry(entry, { allowLoopback: cfg.allowInsecureLocal });
  if (!allowed.includes(host)) throw new GuardError('livekit-host', 'the LiveKit host is not in LAPIS_LT_ALLOWED_LIVEKIT_HOSTS');
  return host;
}

export function assertCaps(cfg) {
  if (!Number.isInteger(cfg.participants) || cfg.participants < 1 || cfg.participants > HARD_LIMITS.participants) {
    throw new GuardError('cap-participants', `--participants must be 1..${HARD_LIMITS.participants}`);
  }
  if (!(cfg.maxRps > 0) || cfg.maxRps > HARD_LIMITS.maxRps) {
    throw new GuardError('cap-rps', `--max-rps must be > 0 and at most ${HARD_LIMITS.maxRps}`);
  }
  if (cfg.durationMin != null && (!(cfg.durationMin > 0) || cfg.durationMin > HARD_LIMITS.durationMin)) {
    throw new GuardError('cap-duration', `--duration-min must be > 0 and at most ${HARD_LIMITS.durationMin}`);
  }
  if (!(cfg.relayShare >= 0 && cfg.relayShare <= 1)) throw new GuardError('relay-share', '--relay-share must be between 0 and 1');
}

/** A test identity is recognised by the display name the server hands out in the join answer. */
export function assertTestIdentity(displayName, prefix) {
  if (typeof prefix !== 'string' || prefix === '') throw new GuardError('no-prefix', 'a name prefix is required');
  if (typeof displayName !== 'string' || !displayName.startsWith(prefix)) {
    throw new GuardError('prefix', 'a logged-in account does not carry the test name prefix: refusing to act with it');
  }
}
