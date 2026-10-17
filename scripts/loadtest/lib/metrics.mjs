// Measurements. Nothing here ever holds a member id, an e-mail, a token or a room name: participants are "p01".."p40".
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

export function percentile(sortedAsc, p) {
  if (sortedAsc.length === 0) return null;
  const rank = Math.ceil((p / 100) * sortedAsc.length);
  return sortedAsc[Math.min(sortedAsc.length, Math.max(1, rank)) - 1];
}

export function stats(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return {
    count: sorted.length,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted.length ? sorted[sorted.length - 1] : null,
  };
}

export function csvCell(value) {
  const s = String(value ?? '');
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function pLabel(idx) {
  return `p${String(idx).padStart(2, '0')}`;
}

// Rejections that are part of normal play: somebody else took the seat first. Anything else is unexpected.
const EXPECTED_ALWAYS = new Set(['selectSeat:Conflict', 'joinTable:Conflict', 'tableToken:Conflict']);
const SEAT_TABLE_METHODS = new Set(['selectSeat', 'joinTable', 'leaveTable', 'tableToken']);
const RATE_CONFLICT_METHODS = new Set(['enterSpace', 'leaveSpace', 'listPresent', 'listTables']);

export function isRejection(outcome) {
  return outcome !== 'ok' && !outcome.startsWith('http_') && outcome !== 'timeout' && outcome !== 'decode' && outcome !== 'network';
}

/** @param expectThrottle true only when the client throttle is deliberately off: then a throttle answer is the point of the run. */
export function isExpectedRejection(method, outcome, { expectThrottle = false } = {}) {
  if (!isRejection(outcome)) return false;
  if (EXPECTED_ALWAYS.has(`${method}:${outcome}`)) return true;
  if (!expectThrottle) return false;
  if (outcome === 'ServiceBusy' && SEAT_TABLE_METHODS.has(method)) return true;
  return outcome === 'Conflict' && RATE_CONFLICT_METHODS.has(method);
}

export class Recorder {
  #clock;
  #expectThrottle;
  samples = [];
  events = [];
  constructor({ clock, expectThrottle = false } = {}) {
    this.#clock = clock;
    this.#expectThrottle = expectThrottle;
  }
  #t() {
    return Math.round(this.#clock ? this.#clock.now() : 0);
  }
  sample(method, latencyMs, outcome, idx) {
    this.samples.push({ t: this.#t(), p: idx, method, latency: latencyMs, outcome });
  }
  mark(event, idx, valueMs = null) {
    this.events.push({ t: this.#t(), p: idx, event, value: valueMs });
  }
  /** Samples and events of the last `windowMs` (virtual) milliseconds. */
  recent(windowMs) {
    const from = this.#t() - windowMs;
    return this.samples.filter((s) => s.t >= from);
  }
  summary() {
    const perMethod = {};
    let rejectedExpected = 0;
    let rejectedUnexpected = 0;
    let httpErrors = 0;
    let timeouts = 0;
    let calls = 0;
    for (const s of this.samples) {
      const m = (perMethod[s.method] ??= { count: 0, ok: 0, rejected: {}, errors: {}, _lat: [] });
      m.count += 1;
      calls += 1;
      m._lat.push(s.latency);
      if (s.outcome === 'ok') m.ok += 1;
      else if (isRejection(s.outcome)) {
        m.rejected[s.outcome] = (m.rejected[s.outcome] ?? 0) + 1;
        if (isExpectedRejection(s.method, s.outcome, { expectThrottle: this.#expectThrottle })) rejectedExpected += 1;
        else rejectedUnexpected += 1;
      } else {
        m.errors[s.outcome] = (m.errors[s.outcome] ?? 0) + 1;
        if (s.outcome.startsWith('http_')) httpErrors += 1;
        if (s.outcome === 'timeout') timeouts += 1;
      }
    }
    for (const m of Object.values(perMethod)) {
      const st = stats(m._lat);
      m.p50 = st.p50;
      m.p95 = st.p95;
      m.p99 = st.p99;
      delete m._lat;
    }
    const byEvent = (name) => this.events.filter((e) => e.event === name);
    const valuesOf = (name) => byEvent(name).map((e) => e.value).filter((v) => typeof v === 'number');
    const disconnects = {};
    for (const e of this.events) if (e.event.startsWith('disconnect:')) disconnects[e.event.slice(11)] = (disconnects[e.event.slice(11)] ?? 0) + 1;
    const stepErrors = byEvent('step_error').length;
    const unexpected = rejectedUnexpected + stepErrors + this.samples.filter((s) => !isRejection(s.outcome) && s.outcome !== 'ok').length;
    return {
      calls,
      perMethod,
      rejectedExpected,
      rejectedUnexpected,
      httpErrors,
      timeouts,
      unexpectedErrors: unexpected,
      unexpectedErrorRate: calls ? unexpected / calls : 0,
      tableAssignMs: stats(valuesOf('table_assigned')),
      hearMs: stats(valuesOf('heard')),
      rotationTokenMs: stats(valuesOf('rotation_token')),
      stepErrors,
      rotations: byEvent('rotation').length,
      presenceLost: byEvent('presence_lost').length,
      tableLost: byEvent('table_lost').length,
      disconnects,
    };
  }
  samplesCsv() {
    const rows = ['t_ms,p,method,latency_ms,outcome'];
    for (const s of this.samples) rows.push([s.t, pLabel(s.p), s.method, s.latency.toFixed(1), s.outcome].map(csvCell).join(','));
    return rows.join('\n') + '\n';
  }
  eventsCsv() {
    const rows = ['t_ms,p,event,value_ms'];
    for (const e of this.events) rows.push([e.t, pLabel(e.p), e.event, e.value === null ? '' : Math.round(e.value)].map(csvCell).join(','));
    return rows.join('\n') + '\n';
  }
}

function flatten(prefix, value, out) {
  if (value !== null && typeof value === 'object') for (const [k, v] of Object.entries(value)) flatten(prefix ? `${prefix}.${k}` : k, v, out);
  else out.push([prefix, value]);
}

export function summaryCsv(summary) {
  const rows = [];
  flatten('', summary, rows);
  return ['key,value', ...rows.map(([k, v]) => `${csvCell(k)},${csvCell(v)}`)].join('\n') + '\n';
}

/** `redact` is applied to every file body. */
export function writeResults(dir, { recorder, meta }, redact = (s) => s) {
  mkdirSync(dir, { recursive: true });
  const summary = { ...meta, ...recorder.summary() };
  writeFileSync(join(dir, 'samples.csv'), redact(recorder.samplesCsv()));
  writeFileSync(join(dir, 'events.csv'), redact(recorder.eventsCsv()));
  writeFileSync(join(dir, 'summary.json'), redact(JSON.stringify(summary, null, 2)) + '\n');
  writeFileSync(join(dir, 'summary.csv'), redact(summaryCsv(summary)));
  return summary;
}

/**
 * Abort criteria of the run sheet, evaluated on the last minute of samples: more than 5 % unexpected errors (at least 20 calls),
 * p99 above 5 s (at least 20 calls), more than 10 answers with a 5xx status.
 */
export function evaluateAbort(recentSamples, { expectThrottle = false } = {}) {
  const n = recentSamples.length;
  const unexpected = recentSamples.filter((s) => s.outcome !== 'ok' && !isExpectedRejection(s.method, s.outcome, { expectThrottle })).length;
  const fiveXx = recentSamples.filter((s) => /^http_5\d\d$/.test(s.outcome)).length;
  if (fiveXx > 10) return 'more-than-10-server-errors-per-minute';
  if (n >= 20 && unexpected / n > 0.05) return 'unexpected-error-rate-above-5-percent';
  if (n >= 20) {
    const p99 = percentile(recentSamples.map((s) => s.latency).sort((a, b) => a - b), 99);
    if (p99 > 5000) return 'p99-above-5-seconds';
  }
  return null;
}
