// HTTP Basic authentication in front of a target (a reverse proxy that guards a staging system: Caddy `basic_auth`). The credential comes from the
// environment ONLY (LAPIS_LT_BASIC_AUTH = "user:password"), is sent to the one confirmed target origin and to nothing else (not to LiveKit, not to
// a redirect target: redirects are never followed), and is registered as a secret so that no output can contain it.

/** Parses "user:password" (the password may contain further colons). @returns {{user:string, password:string, header:string}|null} */
export function parseBasicAuth(value) {
  if (value === undefined || value === null || value === '') return null;
  const at = String(value).indexOf(':');
  if (at < 1 || at === String(value).length - 1) return null;
  const user = String(value).slice(0, at);
  const password = String(value).slice(at + 1);
  return { user, password, header: `Basic ${Buffer.from(`${user}:${password}`, 'utf8').toString('base64')}` };
}

/** Every secret form the credential can take on the wire or in a message; for the SecretSet. */
export function basicAuthSecrets(credential) {
  const parsed = parseBasicAuth(credential);
  if (!parsed) return [];
  return [credential, parsed.header, parsed.header.slice('Basic '.length), parsed.password, parsed.user];
}

/** A fetch that adds the Authorization header for requests to [origin] (exact match of scheme, host and port) and to nothing else. */
export function withBasicAuth(fetchImpl, { origin, credential }) {
  const parsed = parseBasicAuth(credential);
  if (!parsed) return fetchImpl;
  return (input, init = {}) => {
    const url = new URL(typeof input === 'string' ? input : (input.url ?? String(input)));
    if (url.origin !== origin) return fetchImpl(input, init);
    const headers = new Headers(init.headers ?? {});
    headers.set('authorization', parsed.header);
    return fetchImpl(input, { ...init, headers });
  };
}
