// The six scenarios of the run sheet. A scenario is a deterministic PLAN (seeded): a list of steps
// {p, at, op} with `at` in virtual milliseconds from the start of the run. Throttles are applied when the steps are executed.
import { createRng } from './scheduler.mjs';
import { GuardError } from './guard.mjs';

export const SCENARIOS = Object.freeze({
  A: { name: 'gentle', nominalMin: 10, title: 'Gentle: one person every 6 s sits down, takes a table, speaks, reacts, leaves in a staggered way' },
  B: { name: 'burst', nominalMin: 5, title: 'Burst: everybody enters within 10 s, sits at tables in the next 10 s, leaves within 10 s' },
  C: { name: 'table changes', nominalMin: 10, title: 'Table changes every 10.5 to 15 s per person (the server allows one departure from a shared table per 10 s and person): rotation and tableToken storms' },
  D: { name: 'waves', nominalMin: 15, title: 'Waves: half of the room leaves and comes back every 2 minutes' },
  F: { name: 'map traffic', nominalMin: 10, title: 'Map traffic: everybody walks in 15 s segments and sends position packets on the plenum data channel; measures fan-out delay and delivery' },
  E: { name: 'endurance', nominalMin: 30, title: 'Endurance: mixed behaviour on the level of A, token renewal after rotations' },
});

/** How an op turns into RPC calls, for the estimate. [method, count] */
export const OP_CALLS = Object.freeze({
  enter: [['enterSpace', 1]],
  seat: [['selectSeat', 1.3]],
  table: [
    ['listTables', 1],
    ['joinTable', 1.2],
  ],
  leaveTable: [['leaveTable', 1]],
  leave: [
    ['leaveTable', 1],
    ['leaveSpace', 1],
  ],
  watch: [['listPresent', 1]],
  walk: [],
});

/** Length of one walking segment of scenario F and the pause between two segments (a person stops to talk). */
export const MAP_WALK_MS = 15_000;

const MIN = 60_000;

export function durationMs(name, cfg) {
  return (cfg.durationMin ?? SCENARIOS[name].nominalMin) * MIN;
}

function every(steps, p, op, from, to, base, jitter, rng) {
  for (let t = from + rng.between(0, base); t < to; t += base + rng.between(-jitter, jitter)) steps.push({ p, at: Math.round(t), op });
}

export function buildPlan(name, cfg) {
  if (!SCENARIOS[name]) throw new GuardError('usage', 'unknown scenario');
  const rng = createRng(cfg.seed + name.charCodeAt(0));
  const D = durationMs(name, cfg);
  const N = cfg.participants;
  const steps = [];
  const add = (p, at, op) => steps.push({ p, at: Math.round(at), op });
  const need = (ms) => {
    if (D < ms) throw new GuardError('duration', `scenario ${name} with ${N} participants needs at least ${Math.ceil(ms / MIN)} minutes`);
  };

  if (name === 'A') {
    need(N * 6000 + 150_000);
    for (let p = 1; p <= N; p++) {
      const enter = (p - 1) * 6000 + rng.between(0, 500);
      const table = enter + rng.between(20_000, 40_000);
      const leave = Math.max(D - 20_000 - (N - p) * 1500, table + 40_000);
      add(p, enter, 'enter');
      add(p, enter + rng.between(3000, 6000), 'seat');
      add(p, table, 'table');
      add(p, table + 2000, 'micOn');
      add(p, table + 22_000, 'micOff');
      every(steps, p, 'react', table + 5000, leave - 5000, 60_000, 10_000, rng);
      every(steps, p, 'chat', table + 5000, leave - 5000, 120_000, 20_000, rng);
      add(p, leave, 'leave');
    }
  } else if (name === 'B') {
    need(60_000);
    for (let p = 1; p <= N; p++) {
      const enter = rng.between(0, 10_000);
      // The bench seat (enter + 2 to 4 s, up to three tries one second apart) must be taken BEFORE the table: the server keeps one place per
      // person and a bench seat frees the table seat, so a late entrant who sat down at a table first lost it again (found in the first run B).
      const table = Math.max(10_000 + rng.between(0, 10_000), enter + 9000);
      add(p, enter, 'enter');
      add(p, enter + rng.between(2000, 4000), 'seat');
      add(p, table, 'table');
      add(p, table + 2000, 'micOn');
      add(p, table + 17_000, 'micOff');
      add(p, D - 10_000 + rng.between(0, 9000), 'leave');
    }
  } else if (name === 'C') {
    need(N * 2000 + 120_000);
    for (let p = 1; p <= N; p++) {
      const enter = (p - 1) * 2000;
      const first = 30_000 + N * 2000 + rng.between(0, 5000);
      add(p, enter, 'enter');
      add(p, enter + 3000, 'seat');
      add(p, first, 'table');
      add(p, first + 3000, 'micOn');
      const last = D - 60_000;
      // 10.5 s at the least: the server lets one person leave a shared table once per ROTATION_COOLDOWN (10 s); faster changes only measure that cooldown.
      for (let t = first + rng.between(10_500, 15_000); t < last; t += rng.between(10_500, 15_000)) add(p, t, 'table');
      add(p, D - 30_000 + (p - 1) * 500, 'leave');
    }
  } else if (name === 'D') {
    need(4 * MIN + N * 3000);
    for (let p = 1; p <= N; p++) {
      const enter = (p - 1) * 3000;
      const table = enter + rng.between(10_000, 20_000);
      add(p, enter, 'enter');
      add(p, enter + 3000, 'seat');
      add(p, table, 'table');
      add(p, table + 2000, 'micOn');
      add(p, table + 12_000, 'micOff');
      for (let k = 1; 2 * MIN * k < D - 2 * MIN; k++) {
        if ((p + k) % 2 !== 0) continue; // this wave: half of the room
        const out = 2 * MIN * k + p * 200;
        add(p, out, 'leave');
        add(p, out + 30_000, 'enter');
        add(p, out + 33_000, 'seat');
        add(p, out + 45_000 + rng.between(0, 10_000), 'table');
      }
      add(p, D - 30_000 + p * 300, 'leave');
    }
  } else if (name === 'F') {
    need(N * 2000 + 150_000);
    const hz = cfg.moveRate ?? 4;
    for (let p = 1; p <= N; p++) {
      const enter = (p - 1) * 2000;
      add(p, enter, 'enter');
      add(p, enter + 3000, 'seat');
      const stop = D - 60_000;
      for (let t = enter + 10_000; t + MAP_WALK_MS <= stop; t += MAP_WALK_MS + rng.between(3000, 9000)) {
        steps.push({ p, at: Math.round(t), op: 'walk', ms: MAP_WALK_MS, hz });
      }
      const table = D - 50_000 + (p - 1) * 300;
      add(p, table, 'table');
      add(p, table + 2000, 'micOn');
      add(p, table + 12_000, 'micOff');
      add(p, D - 30_000 + (p - 1) * 500, 'leave');
    }
  } else {
    need(N * 2000 + 4 * MIN);
    for (let p = 1; p <= N; p++) {
      const enter = (p - 1) * 2000;
      const first = enter + rng.between(20_000, 40_000);
      const leave = D - 30_000 + (p - 1) * 500;
      add(p, enter, 'enter');
      add(p, enter + 4000, 'seat');
      add(p, first, 'table');
      every(steps, p, 'table', first + 60_000, leave - 20_000, 135_000, 45_000, rng);
      every(steps, p, 'micOn', first + 30_000, leave - 30_000, 150_000, 20_000, rng);
      for (const s of steps.filter((x) => x.p === p && x.op === 'micOn')) add(p, s.at + 20_000, 'micOff');
      every(steps, p, 'react', first + 5000, leave - 5000, 60_000, 10_000, rng);
      every(steps, p, 'chat', first + 5000, leave - 5000, 120_000, 20_000, rng);
      add(p, leave, 'leave');
    }
  }
  // Participant 1 doubles as the watcher that looks for people who are not test accounts.
  const enter1 = steps.find((s) => s.p === 1 && s.op === 'enter').at;
  const lastAt = Math.max(...steps.filter((s) => s.p === 1).map((s) => s.at));
  for (let t = enter1 + 8000; t < lastAt - 1000; t += 30_000) add(1, t, 'watch');
  return steps.sort((a, b) => a.at - b.at || a.p - b.p);
}

// Limits of the server per member and minute (Application.kt / EncounterSpaceService) -- the plan must not exceed them by design.
export const SERVER_LIMITS_PER_MIN = Object.freeze({
  list: { limit: 60, methods: ['listTables', 'listPresent', 'listSpaces', 'getSpace', 'getEntryInfo'] },
  enter: { limit: 30, methods: ['enterSpace'] },
  leave: { limit: 30, methods: ['leaveSpace'] },
  seat: { limit: 60, methods: ['selectSeat'] },
  table: { limit: 30, methods: ['joinTable', 'leaveTable'] },
});

/** The planned calls per method and per member, against the server limits. */
export function estimatePlan(name, cfg) {
  const steps = buildPlan(name, cfg);
  const calls = [];
  for (const s of steps) for (const [method, n] of OP_CALLS[s.op] ?? []) calls.push({ p: s.p, at: s.at, method, n });
  const perMethodTotal = {};
  for (const c of calls) perMethodTotal[c.method] = (perMethodTotal[c.method] ?? 0) + c.n;
  const D = durationMs(name, cfg);
  const groups = {};
  for (const [group, { limit, methods }] of Object.entries(SERVER_LIMITS_PER_MIN)) {
    let peak = 0;
    for (let p = 1; p <= cfg.participants; p++) {
      const mine = calls.filter((c) => c.p === p && methods.includes(c.method)).sort((a, b) => a.at - b.at);
      let lo = 0;
      let sum = 0;
      for (let hi = 0; hi < mine.length; hi++) {
        sum += mine[hi].n;
        while (mine[hi].at - mine[lo].at >= MIN) sum -= mine[lo++].n;
        peak = Math.max(peak, sum);
      }
    }
    groups[group] = { limit, planned: Math.round(peak * 10) / 10, exceeds: peak > limit };
  }
  const switches = steps.filter((s) => s.op === 'table').length;
  // Scenario F: position packets per second at the busiest moment, and the deliveries they cause (every packet reaches everybody else).
  const walks = steps.filter((s) => s.op === 'walk');
  let peakWalkers = 0;
  for (const w of walks) peakWalkers = Math.max(peakWalkers, walks.filter((x) => x.at <= w.at && w.at < x.at + x.ms).length);
  const hz = cfg.moveRate ?? 4;
  const map = walks.length
    ? { peakWalkers, packetsPerSecondPeak: peakWalkers * hz, deliveriesPerSecondPeak: peakWalkers * hz * (cfg.participants - 1) }
    : undefined;
  return {
    scenario: name,
    participants: cfg.participants,
    durationMin: D / MIN,
    steps: steps.length,
    perMethodPerMinute: Object.fromEntries(Object.entries(perMethodTotal).map(([m, n]) => [m, Math.round((n / (D / MIN)) * 10) / 10])),
    perMemberPeakPerMinute: groups,
    // event driven, not planned: every change of a table makes everybody at that table ask for a new token
    expectedRotationsPerMinute: Math.round(((switches * 2) / (D / MIN)) * 10) / 10,
    exceedsServerLimit: Object.values(groups).some((g) => g.exceeds),
    ...(map ? { map } : {}),
  };
}
