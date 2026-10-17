// Output redaction. Every byte that leaves the tool (stdout, stderr, CSV, JSON) goes through redact().

const PLACEHOLDER = '[redacted]';
// Fallbacks for things that look like secrets even if they were never registered.
const GENERIC = [/eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*/g, /lapis_session=[^;\s"]+/gi, /Bearer\s+[A-Za-z0-9._~+/=-]{8,}/gi];

export class SecretSet {
  #values = new Set();
  add(value) {
    if (typeof value === 'string' && value.length >= 4) this.#values.add(value);
  }
  values() {
    return this.#values;
  }
}

export function redact(text, secrets) {
  let out = String(text);
  const list = secrets instanceof SecretSet ? secrets.values() : (secrets ?? []);
  // longest first so a secret that contains another one is removed as a whole
  for (const secret of [...list].sort((a, b) => b.length - a.length)) {
    if (secret && out.includes(secret)) out = out.split(secret).join(PLACEHOLDER);
  }
  for (const re of GENERIC) out = out.replace(re, PLACEHOLDER);
  return out;
}

/** Only the kind of a failure is ever reported: never a body, a message or a URL. */
export function safeError(e) {
  if (e && typeof e === 'object') {
    const out = { kind: typeof e.kind === 'string' ? e.kind : e.name === 'GuardError' ? 'guard' : 'error' };
    if (Number.isInteger(e.status)) out.status = e.status;
    if (typeof e.exceptionType === 'string') out.exceptionType = e.exceptionType;
    if (typeof e.code === 'string' && e.name === 'GuardError') out.code = e.code;
    return out;
  }
  return { kind: 'error' };
}

/** Writes to a stream, redacted. `write` is injectable for tests. */
export function createOutput({ secrets, stdout = (s) => process.stdout.write(s), stderr = (s) => process.stderr.write(s) }) {
  return {
    log: (line) => stdout(redact(line, secrets) + '\n'),
    warn: (line) => stderr(redact(line, secrets) + '\n'),
    redact: (text) => redact(text, secrets),
  };
}
