// Session login/logout of the NORMAL web login (POST /api/auth/login, cookie "lapis_session"). The answer body carries
// the member id and is therefore never read, parsed or logged -- only the status and the cookie matter.
import { postJson, HttpError } from './http.mjs';

export const SESSION_COOKIE = 'lapis_session';

async function timed(recorder, name, idx, fn) {
  const started = performance.now();
  let outcome = 'ok';
  try {
    return await fn();
  } catch (e) {
    outcome = e instanceof HttpError ? (e.kind === 'http' ? `http_${e.status}` : e.kind) : 'error';
    throw e;
  } finally {
    recorder?.sample(name, performance.now() - started, outcome, idx);
  }
}

/** @returns {Promise<{ok:boolean, kind?:string, status?:number}>} */
export async function login({ baseUrl, jar, fetchImpl, recorder, idx, email, password, timeoutMs }) {
  try {
    await timed(recorder, 'login', idx, () =>
      postJson(`${baseUrl}/api/auth/login`, JSON.stringify({ email, password }), { jar, fetchImpl, timeoutMs }),
    );
  } catch (e) {
    if (e instanceof HttpError) return { ok: false, kind: e.kind, ...(e.status !== undefined ? { status: e.status } : {}) };
    throw e;
  }
  if (!jar.has(SESSION_COOKIE)) return { ok: false, kind: 'no-session' };
  return { ok: true };
}

export async function logout({ baseUrl, jar, fetchImpl, recorder, idx, timeoutMs }) {
  try {
    await timed(recorder, 'logout', idx, () => postJson(`${baseUrl}/api/auth/logout`, '{}', { jar, fetchImpl, timeoutMs }));
    return { ok: true };
  } catch (e) {
    if (e instanceof HttpError) return { ok: false, kind: e.kind };
    throw e;
  } finally {
    jar.clear();
  }
}
