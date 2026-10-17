// Minimal HTTP layer: JSON POST with timeout, response size cap, NO redirects, cookie jar kept in memory only.

export class HttpError extends Error {
  constructor(kind, status) {
    super(`http ${kind}`); // no URL, no body, no cause text: nothing that could carry a secret
    this.name = 'HttpError';
    this.kind = kind; // 'http' | 'timeout' | 'decode' | 'network'
    if (status !== undefined) this.status = status;
  }
}

export class CookieJar {
  #cookies = new Map();
  #onSet;
  constructor({ onSet } = {}) {
    this.#onSet = onSet;
  }
  /** Stores the name=value pair of every Set-Cookie header. Attributes (Path, Expires...) are ignored. */
  store(response) {
    const headers = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
    for (const line of headers) {
      const pair = line.split(';', 1)[0];
      const eq = pair.indexOf('=');
      if (eq <= 0) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (value === '') this.#cookies.delete(name);
      else {
        this.#cookies.set(name, value);
        this.#onSet?.(value);
      }
    }
  }
  header() {
    return [...this.#cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  has(name) {
    return this.#cookies.has(name);
  }
  clear() {
    this.#cookies.clear();
  }
}

async function readCapped(response, maxBytes) {
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maxBytes) throw new HttpError('decode', response.status);
  if (!response.body) return '';
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
      throw new HttpError('decode', response.status);
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/** POSTs JSON. Resolves with {status, text} for 2xx; throws HttpError otherwise. Redirects are an error. */
export async function postJson(url, body, { jar, timeoutMs = 10_000, maxBytes = 1024 * 1024, fetchImpl = globalThis.fetch } = {}) {
  const headers = { 'content-type': 'application/json', accept: 'application/json' };
  const cookie = jar?.header();
  if (cookie) headers.cookie = cookie;
  let response;
  // An explicit, REFERENCED timer instead of AbortSignal.timeout(): that timer is unreferenced, so a hanging request with nothing else
  // alive let the event loop end (Node 22 in CI cancelled the test file with "Promise resolution is still pending").
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new DOMException('The operation timed out.', 'TimeoutError')), timeoutMs);
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers,
      body,
      redirect: 'manual',
      signal: controller.signal,
    });
  } catch (e) {
    clearTimeout(timer);
    if (e?.name === 'TimeoutError' || e?.name === 'AbortError') throw new HttpError('timeout');
    throw new HttpError('network');
  }
  try {
    jar?.store(response);
    if (response.status >= 300) {
      await response.body?.cancel().catch(() => {});
      throw new HttpError('http', response.status);
    }
    const text = await readCapped(response, maxBytes);
    return { status: response.status, text };
  } catch (e) {
    if (e instanceof HttpError) throw e;
    if (e?.name === 'TimeoutError' || e?.name === 'AbortError') throw new HttpError('timeout');
    throw new HttpError('network');
  } finally {
    clearTimeout(timer);
  }
}
