// Kilua RPC 0.0.45 by hand. Wire (verified by LoadTestWireContractTest against the real route):
//   POST /rpc/route<Manager><index>   {"id":N,"method":"<name>","params":[<JSON document as a STRING>...],"jsonrpc":"2.0"}
//   answer {"id":N,"result":"<JSON document as a string>"|null,"error":...,"exceptionType":"<class>"|null,...}
// The server ignores "method" and uses only the route and the params; it is sent anyway like the real client does.
import { routePath } from './routes.mjs';
import { shortExceptionName } from './exceptions.mjs';
import { postJson, HttpError } from './http.mjs';

export class KiluaClient {
  #baseUrl;
  #jar;
  #fetchImpl;
  #recorder;
  #participantIdx;
  #timeoutMs;
  #nextId = 1;
  #secrets;

  constructor({ baseUrl, jar, fetchImpl, recorder, participantIdx, timeoutMs = 10_000, secrets } = {}) {
    this.#baseUrl = baseUrl.replace(/\/$/, '');
    this.#jar = jar;
    this.#fetchImpl = fetchImpl;
    this.#recorder = recorder;
    this.#participantIdx = participantIdx;
    this.#timeoutMs = timeoutMs;
    this.#secrets = secrets;
  }

  static encodeRequest(method, params, id = 1) {
    return JSON.stringify({ id, method, params: params.map((p) => JSON.stringify(p === undefined ? null : p)), jsonrpc: '2.0' });
  }

  /** @returns {{ok:true,value:any}|{ok:false,kind:'exception',exceptionType:string}|{ok:false,kind:'decode'}} */
  static decodeResponse(text) {
    let answer;
    try {
      answer = JSON.parse(text);
    } catch {
      return { ok: false, kind: 'decode' };
    }
    if (answer === null || typeof answer !== 'object') return { ok: false, kind: 'decode' };
    if (answer.exceptionType != null || answer.error != null) {
      return { ok: false, kind: 'exception', exceptionType: shortExceptionName(answer.exceptionType) };
    }
    if (answer.result == null) return { ok: true, value: null };
    try {
      return { ok: true, value: JSON.parse(answer.result) };
    } catch {
      return { ok: false, kind: 'decode' };
    }
  }

  /** One RPC. Never throws for a remote failure; the result says what happened. Latency is real time. */
  async call(method, params) {
    const body = KiluaClient.encodeRequest(method, params, this.#nextId++);
    const started = performance.now();
    let result;
    try {
      const { text } = await postJson(`${this.#baseUrl}${routePath(method)}`, body, {
        jar: this.#jar,
        timeoutMs: this.#timeoutMs,
        fetchImpl: this.#fetchImpl,
      });
      result = KiluaClient.decodeResponse(text);
    } catch (e) {
      if (!(e instanceof HttpError)) throw e;
      result = { ok: false, kind: e.kind, ...(e.status !== undefined ? { status: e.status } : {}) };
    }
    const latency = performance.now() - started;
    let outcome = 'ok';
    if (!result.ok) {
      outcome = result.kind === 'exception' ? result.exceptionType : result.kind === 'http' ? `http_${result.status}` : result.kind;
    }
    this.#recorder?.sample(method, latency, outcome, this.#participantIdx);
    if (result.ok) this.#registerSecrets(result.value);
    return { ...result, latencyMs: latency };
  }

  // join tokens, TURN credentials and so on must never reach an output
  #registerSecrets(value) {
    if (!this.#secrets || value === null || typeof value !== 'object') return;
    const stack = [value];
    while (stack.length) {
      const v = stack.pop();
      if (Array.isArray(v)) stack.push(...v);
      else if (v && typeof v === 'object') {
        for (const [k, x] of Object.entries(v)) {
          if (typeof x === 'string' && ['token', 'credential', 'username'].includes(k)) this.#secrets.add(x);
          else if (typeof x === 'string' && k === 'serverUrl') {
            try {
              const u = new URL(x);
              if (u.search) this.#secrets.add(u.search);
            } catch {
              /* not a URL: nothing to register */
            }
          } else if (x && typeof x === 'object') stack.push(x);
        }
      }
    }
  }
}
