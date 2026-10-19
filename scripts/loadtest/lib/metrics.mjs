// Measurements. Nothing here ever holds a member id, an e-mail, a token or a room name: participants are "p01".."p40".
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
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

const MAP_HIST_MS = 10_000; // delays of map packets are counted per millisecond up to 10 s; slower ones share the last bucket

export class Recorder {
  #clock;
  #expectThrottle;
  // Map packets (scenario F) are far too many for one event each (40 people, 4 per second, 39 receivers): counters and a histogram.
  #map = { sent: 0, expected: 0, received: 0, max: 0, hist: new Uint32Array(MAP_HIST_MS + 1) };
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
  /** A map packet left; `expectedRecipients` = how many other people were in the room at that moment. */
  mapSent(expectedRecipients) {
    this.#map.sent += 1;
    this.#map.expected += expectedRecipients;
  }
  /** A map packet arrived after `delayMs`. */
  mapReceived(delayMs) {
    const b = Math.min(MAP_HIST_MS, Math.max(0, Math.round(delayMs)));
    this.#map.hist[b] += 1;
    this.#map.received += 1;
    this.#map.max = Math.max(this.#map.max, delayMs);
  }
  #mapSummary() {
    const m = this.#map;
    if (m.sent === 0) return undefined;
    const at = (p) => {
      if (m.received === 0) return null;
      const rank = Math.ceil((p / 100) * m.received);
      let seen = 0;
      for (let i = 0; i < m.hist.length; i++) {
        seen += m.hist[i];
        if (seen >= rank) return i;
      }
      return MAP_HIST_MS;
    };
    return {
      sent: m.sent,
      expectedDeliveries: m.expected,
      received: m.received,
      deliveryRatio: m.expected ? Math.round((m.received / m.expected) * 10000) / 10000 : null,
      delayMs: { p50: at(50), p95: at(95), p99: at(99), max: m.received ? Math.round(m.max) : null },
    };
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
    // Which steps failed and with which error class (names only, see runner.mjs): a map name -> count.
    const countPrefix = (prefix) => {
      const out = {};
      for (const e of this.events) if (e.event.startsWith(prefix)) out[e.event.slice(prefix.length)] = (out[e.event.slice(prefix.length)] ?? 0) + 1;
      return Object.keys(out).length ? out : undefined;
    };
    const stepErrorsByOp = countPrefix('step_error:');
    const errorTypes = countPrefix('error_type:');
    const nativeErrorTypes = countPrefix('native_error_type:');
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
      ...(byEvent('table_connect').length
        ? { tableTeardownMs: stats(valuesOf('table_teardown')), tableConnectMs: stats(valuesOf('table_connect')) }
        : {}),
      stepErrors,
      ...(stepErrorsByOp ? { stepErrorsByOp, errorTypes } : {}),
      ...(nativeErrorTypes ? { nativeErrorTypes } : {}),
      rotations: byEvent('rotation').length,
      presenceLost: byEvent('presence_lost').length,
      tableLost: byEvent('table_lost').length,
      disconnects,
      ...(this.#mapSummary() ? { mapPackets: this.#mapSummary() } : {}),
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
 * Abort criteria of the run sheet, evaluated on the last minute of samples: more than 5 % unexpected errors (at least 20 calls and at least
 * 3 unexpected ones), p99 above 5 s (at least 100 calls), more than 10 answers with a 5xx status.
 * Minimums (2026-10-19): with the participants spread over several processes one process sees only a few dozen calls a minute; then one or two
 * unlucky calls were enough to end a healthy run (a single 7 s call is the p99 of 60 samples, two entry conflicts are 5 % of 40).
 */
export const ABORT_MIN_CALLS_RATE = 20;
export const ABORT_MIN_UNEXPECTED = 3;
export const ABORT_MIN_CALLS_P99 = 100;
export function evaluateAbort(recentSamples, { expectThrottle = false } = {}) {
  const n = recentSamples.length;
  const unexpected = recentSamples.filter((s) => s.outcome !== 'ok' && !isExpectedRejection(s.method, s.outcome, { expectThrottle })).length;
  const fiveXx = recentSamples.filter((s) => /^http_5\d\d$/.test(s.outcome)).length;
  if (fiveXx > 10) return 'more-than-10-server-errors-per-minute';
  if (n >= ABORT_MIN_CALLS_RATE && unexpected >= ABORT_MIN_UNEXPECTED && unexpected / n > 0.05) return 'unexpected-error-rate-above-5-percent';
  if (n >= ABORT_MIN_CALLS_P99) {
    const p99 = percentile(recentSamples.map((s) => s.latency).sort((a, b) => a - b), 99);
    if (p99 > 5000) return 'p99-above-5-seconds';
  }
  return null;
}

/**
 * "Nobody could enter": every participant of this process tried enterSpace and not one call was accepted. The usual reason is that the
 * room is closed (or full of other people); the run would otherwise idle to its end and look like a success (exit 0 with a handful of calls).
 * @param expectedEntries how many participants this process carries
 */
export function entryFailureReason(samples, expectedEntries) {
  const tries = samples.filter((s) => s.method === 'enterSpace');
  if (expectedEntries > 0 && tries.length >= expectedEntries && !tries.some((s) => s.outcome === 'ok')) return 'nobody-could-enter-the-room';
  return null;
}

function parseCsvLine(line) {
  const cells = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"' && line[i + 1] === '"') {
        cur += '"';
        i += 1;
      } else if (c === '"') quoted = false;
      else cur += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') {
      cells.push(cur);
      cur = '';
    } else cur += c;
  }
  cells.push(cur);
  return cells;
}

/** The rows (without the header) of a CSV written by [Recorder]; the cells never contain a line break. */
export function readCsvRows(text) {
  return text
    .split('\n')
    .slice(1)
    .filter((l) => l !== '')
    .map(parseCsvLine);
}

const labelToIdx = (label) => Number(String(label).replace(/^p/, ''));

/**
 * Merges the result directories of the shards of one run (same virtual start, disjoint participants) into one set of result files: the samples and
 * events are put together by time and the summary is computed again from them, so percentiles are real percentiles of the whole run.
 * Not merged: the counters of map packets (scenario F cannot be sharded) and "heard" across shards (a tone is only heard by listeners of the same shard).
 * @returns {{ summary: object, merged: number }} merged = number of shard directories that had results
 */
export function mergeShardResults(dirs, outDir, redact = (s) => s) {
  const recorder = new Recorder({ clock: null });
  const metas = [];
  for (const dir of dirs) {
    if (!existsSync(join(dir, 'summary.json'))) continue; // a shard that died before writing: its absence is visible in "merged"
    metas.push(JSON.parse(readFileSync(join(dir, 'summary.json'), 'utf8')));
    for (const [t, p, method, latency, outcome] of readCsvRows(readFileSync(join(dir, 'samples.csv'), 'utf8'))) {
      recorder.samples.push({ t: Number(t), p: labelToIdx(p), method, latency: Number(latency), outcome });
    }
    for (const [t, p, event, value] of readCsvRows(readFileSync(join(dir, 'events.csv'), 'utf8'))) {
      recorder.events.push({ t: Number(t), p: labelToIdx(p), event, value: value === '' ? null : Number(value) });
    }
  }
  if (metas.length === 0) return { summary: null, merged: 0 };
  recorder.samples.sort((a, b) => a.t - b.t);
  recorder.events.sort((a, b) => a.t - b.t);
  const first = metas[0];
  const abortReason = metas.map((m) => m.abortReason).find((r) => r && r !== 'signal') ?? metas.map((m) => m.abortReason).find(Boolean) ?? null;
  const config = { ...first.config, processes: dirs.length };
  delete config.shard;
  const summary = writeResults(
    outDir,
    {
      recorder,
      meta: {
        toolVersion: first.toolVersion,
        dryRun: first.dryRun,
        media: first.media,
        config,
        virtualDurationMs: Math.max(...metas.map((m) => m.virtualDurationMs ?? 0)),
        abortReason,
        participantsAbortedByGuard: metas.reduce((n, m) => n + (m.participantsAbortedByGuard ?? 0), 0),
        nativeErrors: metas.reduce((n, m) => n + (m.nativeErrors ?? 0), 0),
        shardsMerged: metas.length,
      },
    },
    redact,
  );
  return { summary, merged: metas.length };
}
