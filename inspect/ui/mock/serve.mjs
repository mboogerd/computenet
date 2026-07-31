// Fixture server for manual/offline UI development (M0-FE ticket Implement
// §1: "add a tiny `npm run mock` static server for the fixture"; extended by
// M1-FE for the cell-detail/state/observe endpoints so the detail panel,
// state live-update, and observe-lifecycle can be checked manually against
// `npm run dev` without a real `:inspect` server; extended again by M2-FE
// for /errors + error.* events, needed here specifically because M2-BE runs
// in parallel and may not be merged yet — this mock is the only way to
// screenshot the Errors toggle/subsection without it; extended again by
// M3-FE for flow.rates, same reasoning — M3-BE (the real tap-based flow
// feed) runs in parallel and does not exist in this worktree either);
// extended again by M4-FE for /graphs, /search, and the `?graph=` topology
// filter, same reasoning — M4-BE runs in parallel and does not exist in
// this worktree either); extended again by V2-FE for /activity + `activity`
// events, same reasoning — V2-BE runs in parallel and does not exist in this
// worktree either.
//
// No deps — Node's built-in http module only. NOT a stand-in for the real
// server's semantics (single global observation slot, one fake "live"
// dataset) — just enough for a human to see the M0+M1+M2+M4+V2 UI work end
// to end.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const fixturePath = fileURLToPath(new URL('../fixtures/topology.json', import.meta.url));
const detailFixturePath = fileURLToPath(new URL('../fixtures/cell-detail.json', import.meta.url));
const errorsFixturePath = fileURLToPath(new URL('../fixtures/errors.json', import.meta.url));
const activityFixturePath = fileURLToPath(new URL('../fixtures/activity.json', import.meta.url));
const port = Number(process.env.PORT ?? 7071);

const snapshot = JSON.parse(readFileSync(fixturePath, 'utf8'));
const detailFixture = JSON.parse(readFileSync(detailFixturePath, 'utf8'));
const errorsSnapshot = JSON.parse(readFileSync(errorsFixturePath, 'utf8'));
const activityFixture = JSON.parse(readFileSync(activityFixturePath, 'utf8'));

// --- M4: multi-graph simulation ------------------------------------------
// M4-BE runs in parallel and does not exist in this worktree; this mock
// synthesizes the multi-graph scenario 20-api-contract.md's GraphList/search
// describe so Home/Navigator (M4-FE ticket) can be checked by hand. The real
// skillmatch capture is already one real connected pipeline, so it becomes
// ONE (named) component; a second, small, UNNAMED component is fabricated
// and spliced into the served snapshot only — never into the checked-in
// fixtures/topology.json (same "served copy only" discipline the M3-FE
// fused-edge simulation below already established).
function minUuid(nodes) {
  return [...nodes].map((n) => n.ref.split(':')[0]).sort()[0];
}

const SKILLMATCH_GRAPH = `g-${minUuid(snapshot.nodes)}`;
for (const n of snapshot.nodes) n.graph = SKILLMATCH_GRAPH;

const BATCH_NODES = [
  {
    ref: 'aaaaaaaa-0000-0000-0000-000000000001:0',
    name: 'batchIngest',
    typeFqn: 'civictech.cell.data.SetCell',
    color: 'PURE',
    manifests: [],
    ports: [{ name: 'outlet', dir: 'OUT', contractFqn: 'civictech.cell.Propagate' }],
    host: 'batch-host',
    net: 'local',
    lifecycle: 'HOT',
    generation: 0,
    graph: null,
  },
  {
    ref: 'aaaaaaaa-0000-0000-0000-000000000002:0',
    name: null,
    typeFqn: 'civictech.cell.observe.ObserveCell',
    color: 'PURE',
    manifests: [],
    ports: [{ name: 'inlet', dir: 'IN', contractFqn: 'civictech.cell.Propagate' }],
    host: 'batch-host',
    net: 'local',
    lifecycle: 'HOT',
    generation: 0,
    graph: null,
  },
];
const BATCH_GRAPH = `g-${minUuid(BATCH_NODES)}`;
for (const n of BATCH_NODES) n.graph = BATCH_GRAPH;
snapshot.nodes.push(...BATCH_NODES);
snapshot.edges.push({
  id: 'e-batch-1',
  from: { ref: BATCH_NODES[0].ref, port: 'outlet' },
  to: { ref: BATCH_NODES[1].ref, port: 'inlet' },
  role: 'CONSUME',
  fused: null,
});

// --- V1C-FE: paged/provenance/unavailable state simulation --------------
// The real V1C-BE server does not exist in this worktree; this is the only
// way to exercise the paged view, the three `provenance` values, elided
// exclusives, and each `unreadable` reason by hand ahead of it merging
// (V1C-FE ticket Solution direction §5). Five synthetic nodes, spliced into
// the served snapshot only (never `fixtures/topology.json`), one dedicated
// small graph so they are easy to find and click.
//
// C10 correction: the big cell's page value is the shape the MERGED backend
// actually serves for a `SetCell` — the kernel pages
// `SetStateEntry(element, addTags, delTags)` records, so the encoder renders
// stored **tag algebra**, not membership: three columns, tag sets as nested
// `$table`s, and a tombstoned element present as an ordinary row (its
// del-tag covers its add-tag). Every third element here is tombstoned so a
// manual pass sees that case. The same cell under an open observation would
// render as a flat member list instead; the inspector forwards the kernel's
// page entries rather than re-interpreting them.
const BIG_ROWS = Array.from({ length: 7 }, (_, i) => `element-${i + 1}`);
const PAGE_SIZE = 3;

/** One OR-set tag, as the encoder renders a collection of same-shaped records. */
function tagSet(...tags) {
  if (tags.length === 0) return [];
  return { $table: { columns: ['source', 'counter'], rows: tags.map((t) => ['a3f2c1d0', t]) } };
}

/** `SetStateEntry(element, addTags, delTags)` — tombstoned on every third. */
function setEntryRow(element, index) {
  const tombstoned = (index + 1) % 3 === 0;
  return [element, tagSet(index + 1), tombstoned ? tagSet(index + 1) : tagSet()];
}

/** `null` return = an unknown/expired cursor (410). */
function bigCellPage(cursorParam, limitParam) {
  if (cursorParam === null) return { rows: BIG_ROWS.slice(0, PAGE_SIZE), start: 0, limit: PAGE_SIZE };
  const start = Number(cursorParam.replace(/^p-/, ''));
  if (!Number.isInteger(start) || start < 0 || start >= BIG_ROWS.length) return null;
  const limit = Math.min(Math.max(Number(limitParam) || PAGE_SIZE, 1), 1000);
  return { rows: BIG_ROWS.slice(start, start + limit), start, limit };
}

const V1C_NODES = [
  { ref: 'bbbbbbbb-0000-0000-0000-00000000b16c:0', name: 'bigLedger (paged demo)' },
  { ref: 'cccccccc-0000-0000-0000-0000000c4ec4:0', name: 'checkpointedLedger (drained demo)' },
  { ref: 'dddddddd-0000-0000-0000-000000005d0e:0', name: 'suspendedLedger (parked demo)' },
  { ref: 'eeeeeeee-0000-0000-0000-00000000e1e5:0', name: 'excludedLedger (exclusivesElided demo)' },
  { ref: 'ffffffff-0000-0000-0000-00000000f01d:0', name: 'migratingLedger (unavailable demo)' },
].map((n) => ({
  ...n,
  typeFqn: 'civictech.cell.data.SetCell',
  color: 'PURE',
  manifests: [],
  ports: [{ name: 'deltaInlet', dir: 'IN', contractFqn: 'civictech.cell.Propagate' }],
  host: 'v1c-demo-host',
  net: 'local',
  lifecycle: 'HOT',
  generation: 0,
  graph: null,
}));
const V1C_GRAPH = `g-${minUuid(V1C_NODES)}`;
for (const n of V1C_NODES) n.graph = V1C_GRAPH;
snapshot.nodes.push(...V1C_NODES);

const [BIG_REF, CHECKPOINT_REF, SUSPENDED_REF, ELIDED_REF, MIGRATING_REF] = V1C_NODES.map((n) => n.ref);

function v1cStateFor(ref, cursorParam, limitParam) {
  if (ref === BIG_REF) {
    const paged = bigCellPage(cursorParam, limitParam);
    if (!paged) return { status: 410, body: { error: 'unknown or expired cursor' } };
    const next = paged.start + paged.rows.length;
    const last = next >= BIG_ROWS.length;
    return {
      status: 200,
      body: {
        ref,
        frontier: null,
        kind: 'page',
        value: {
          $table: {
            columns: ['element', 'addTags', 'delTags'],
            rows: paged.rows.map((r, i) => setEntryRow(r, paged.start + i)),
          },
        },
        staleMs: 0,
        provenance: 'live',
        page: {
          cursor: last ? null : `p-${next}`,
          limit: paged.limit,
          entries: paged.rows.length,
          exclusivesElided: 0,
          // C10: the shipped server answers `true` on page 1, `null` on every
          // INTERMEDIATE page (which carries only the walk's opening frontier,
          // and says so via the `staleFrontier` caveat), and a real verdict
          // only when the walk closes.
          walkStable: paged.start === 0 || last ? true : null,
          caveats: paged.start === 0 || last ? [] : ['staleFrontier'],
          // C10: cell-level state that rides every page — a `SetCell`'s tag
          // counter.
          attributes: { counter: BIG_ROWS.length },
        },
        unreadable: null,
      },
    };
  }
  if (ref === CHECKPOINT_REF) {
    return {
      status: 200,
      body: {
        ref,
        frontier: null,
        kind: 'snapshot',
        value: { $table: { columns: ['row'], rows: [['as-of-drain-1'], ['as-of-drain-2']] } },
        staleMs: 0,
        provenance: 'checkpoint',
        page: null,
        unreadable: null,
      },
    };
  }
  if (ref === SUSPENDED_REF) {
    return {
      status: 200,
      body: {
        ref,
        frontier: null,
        kind: 'snapshot',
        value: { $table: { columns: ['row'], rows: [['parked-but-readable']] } },
        staleMs: 0,
        provenance: 'liveSuspended',
        page: null,
        unreadable: null,
      },
    };
  }
  if (ref === ELIDED_REF) {
    return {
      status: 200,
      body: {
        ref,
        frontier: null,
        kind: 'page',
        value: { $table: { columns: ['row'], rows: [['visible-1']] } },
        staleMs: 0,
        provenance: 'live',
        page: { cursor: null, limit: 1, entries: 1, exclusivesElided: 2, walkStable: true },
        unreadable: null,
      },
    };
  }
  if (ref === MIGRATING_REF) {
    return {
      status: 200,
      body: { ref, frontier: null, kind: 'unavailable', value: null, staleMs: 0, unreadable: 'migrating' },
    };
  }
  return null;
}

function graphIdsInSnapshot() {
  return [...new Set(snapshot.nodes.map((n) => n.graph).filter(Boolean))];
}

function graphSummary(graphId) {
  const members = snapshot.nodes.filter((n) => n.graph === graphId);
  const refs = new Set(members.map((n) => n.ref));
  const hosts = new Set(members.map((n) => n.host).filter(Boolean));
  const nets = new Set(members.map((n) => n.net).filter(Boolean));
  const health = {
    deadLetters: errorsState.deadLetters.filter((d) => refs.has(d.ref)).length,
    parked: errorsState.parked.filter((p) => refs.has(p.ref)).reduce((sum, p) => sum + p.count, 0),
    restarts: errorsState.restarts.filter((r) => refs.has(r.ref)).length,
  };
  return {
    id: graphId,
    name: graphId === SKILLMATCH_GRAPH ? 'skillmatch' : null,
    cells: members.length,
    hosts: hosts.size,
    nets: nets.size,
    health,
    lifecycle: 'hot',
  };
}

function graphsList() {
  return { graphs: graphIdsInSnapshot().map(graphSummary) };
}

function searchName(q) {
  const needle = q.trim().toLowerCase();
  if (!needle) return [];
  const hits = [];
  for (const g of graphIdsInSnapshot()) {
    const label = g === SKILLMATCH_GRAPH ? 'skillmatch' : null;
    if (label && label.toLowerCase().includes(needle)) hits.push({ graph: g, ref: null, label, detail: 'graph' });
  }
  for (const n of snapshot.nodes) {
    const name = (n.name ?? '').toLowerCase();
    const type = n.typeFqn.toLowerCase();
    if (name.includes(needle) || type.includes(needle)) {
      hits.push({ graph: n.graph, ref: n.ref, label: n.name ?? n.ref, detail: n.typeFqn });
    }
  }
  return hits;
}

function searchProblems() {
  return graphIdsInSnapshot()
    .map(graphSummary)
    .filter((g) => g.health.deadLetters > 0 || g.health.parked > 0 || g.health.restarts > 0)
    .sort((a, b) => b.health.deadLetters - a.health.deadLetters || b.health.parked - a.health.parked)
    .map((g) => ({
      graph: g.id,
      ref: null,
      label: g.name ?? g.id,
      detail: `${g.health.deadLetters} dead · ${g.health.parked} parked · ${g.health.restarts} restart${g.health.restarts === 1 ? '' : 's'}`,
    }));
}

/** Shared across every connected /events client — the contract's `seq` is a
 *  single monotonic counter, not per-connection. */
let seq = snapshot.seq;
/** @type {Set<import('node:http').ServerResponse>} */
const clients = new Set();

function broadcast(kind, payload) {
  seq += 1;
  const frame = `data: ${JSON.stringify({ seq, kind, payload })}\n\n`;
  for (const res of clients) res.write(frame);
}

// --- observe state -------------------------------------------------------
// Mirrors the real server's P6 rule this mock exists to help verify: at most
// one cell is ever observed at a time (the selected one), and only observed
// cells get `state.summary` events.
let observedRef = null;
let liveRows = 3;
let observeTimer = null;
let observeWindowTick = 0;
let observeStaleMs = 0;

function stateFor(ref) {
  return {
    ref,
    frontier: { source: 'mock0000ab', counter: liveRows },
    kind: 'view',
    value: {
      $table: {
        columns: ['n'],
        rows: [
          ...Array.from({ length: liveRows }, (_, i) => [String(i + 1)]),
          { cells: ['deleted-earlier'], tombstoned: true },
        ],
      },
    },
    staleMs: observeStaleMs,
  };
}

function summaryFor(ref) {
  return {
    ref,
    cardinality: `${liveRows} rows`,
    frontier: { source: 'mock0000ab', counter: liveRows },
    staleMs: observeStaleMs,
  };
}

// V1A-FE ticket Implement §4: "offline dev exercises the real path" — the
// real V1A-BE coalesced state.summary publishes a 1 Hz window per observed
// cell on change *and* even when quiet, with staleMs computed at publish time
// (resets ~0 on a change window, grows by ~1000 across quiet ones). This mock
// mirrors that shape (mutating on roughly every 4th window, not every one) so
// the FE's change-gated refetch, row-flash, and change-log panel all have
// something real to run against under `npm run dev` — a mock that always
// sent `staleMs: 0` (as before this ticket) would silently mask a bug in the
// FE's `indicatesChange` predicate, which depends on staleMs actually
// shrinking/growing.
function tickObserveWindow() {
  observeWindowTick += 1;
  if (observeWindowTick % 4 === 0) {
    liveRows += 1;
    observeStaleMs = 0;
  } else {
    observeStaleMs += 1000;
  }
  broadcast('state.summary', summaryFor(observedRef));
}

function startObserving(ref) {
  observedRef = ref;
  stopTimer();
  observeWindowTick = 0;
  observeStaleMs = 0;
  observeTimer = setInterval(tickObserveWindow, 1000);
}

function stopTimer() {
  if (observeTimer) clearInterval(observeTimer);
  observeTimer = null;
}

function stopObserving(ref) {
  if (observedRef !== ref) return; // DELETE for a ref that isn't the active one — no-op
  stopTimer();
  // "one trailing summary" (ticket Implement §4): one more quiet window's
  // worth of staleness, same as the real coalesced feed's trailing window
  // after release.
  observeStaleMs += 1000;
  broadcast('state.summary', summaryFor(ref));
  observedRef = null;
}

// --- errors state ----------------------------------------------------------
// Simulates a live error feed so the Errors toggle + subsection (M2-FE
// ticket) can be checked manually against `npm run dev` — M2-BE runs in
// parallel and may not be merged into this worktree yet. A mutable working
// copy of fixtures/errors.json, evolved in place so GET /errors always
// reflects exactly what the SSE stream has already announced (never further
// ahead — the same "the snapshot and the deltas agree" property the real
// server must hold).
const errorsState = JSON.parse(JSON.stringify(errorsSnapshot));

function bumpParked() {
  const row = errorsState.parked[0];
  if (!row) return;
  row.count += 1;
  row.oldestMs += 4000;
  errorsState.counters.parked = errorsState.parked.reduce((sum, p) => sum + p.count, 0);
  broadcast('error.parked', row);
}

const DEAD_LETTER_CAUSES = ['OwnershipViolation', 'SerializationFailure', 'PortTypeMismatch'];
const INVOCATION_PORTS = ['left', 'right', 'inlet'];
let deadLetterTick = 0;
// V3: a plain in-memory "last dead letter seen" note, consulted by
// `bumpRestart` below to fill `cause`/`causeAtMs` — a mock stand-in for the
// real server's time-window correlation (V3-BE), not a reimplementation of
// it (this mock is explicitly "NOT a stand-in for the real server's
// semantics", per the file header).
let lastDeadLetterCause = null;
let lastDeadLetterAtMs = null;

function emitDeadLetter() {
  const parkedRef = errorsState.parked[0]?.ref;
  const ref = snapshot.nodes.find((n) => n.ref !== parkedRef)?.ref;
  if (!ref) return;
  deadLetterTick += 1;
  const cause = DEAD_LETTER_CAUSES[deadLetterTick % DEAD_LETTER_CAUSES.length];
  const atMs = Date.now();
  const entry = {
    ref,
    cause,
    description: `mock ${cause} at tick ${deadLetterTick}`,
    wave: { source: 'mock0000ab', counter: deadLetterTick },
    atMs,
    // V3: invocation summary + ownership disposition, so the enriched
    // dead-letter card (ticket Solution direction §3d) has something to
    // draw under `npm run dev`. `OwnershipViolation` gets the "frozen"
    // exclusive-payload disposition — the case the field exists for —
    // everything else gets a plain one.
    invocation: {
      port: INVOCATION_PORTS[deadLetterTick % INVOCATION_PORTS.length],
      type: 'PORT_API',
      method: 'accept',
      parameterTypes: ['civictech.cell.data.SetOps$Add'],
      argCount: 1,
      hop: deadLetterTick % 3,
    },
    disposition:
      cause === 'OwnershipViolation'
        ? [{ index: 0, ownership: 'frozen', reason: 'Owned payload frozen for dead-letter capture' }]
        : [{ index: 0, ownership: 'plain', reason: null }],
  };
  errorsState.deadLetters.push(entry);
  errorsState.counters.deadLetters += 1;
  broadcast('error.deadLetter', entry);
  lastDeadLetterCause = cause;
  lastDeadLetterAtMs = atMs;
}

function bumpRestart() {
  const row = errorsState.restarts[0];
  if (!row) return;
  row.generation += 1;
  row.atMs = Date.now();
  // V3: fill the supervision-timeline fields (ticket Solution direction
  // §3c) from whatever dead letter this mock last emitted — a plausible
  // "time-window correlation" stand-in, same reasoning as the comment above.
  row.cause = lastDeadLetterCause;
  row.causeAtMs = lastDeadLetterCause ? lastDeadLetterAtMs : null;
  row.reBaselineAtMs = row.atMs + 500; // "observed shortly after" — mock-only
  errorsState.counters.restarts += 1;
  broadcast('error.restart', row);
}

// V3: wave-health heuristic simulation (ticket Solution direction §5) — a
// row opens, then a few seconds later the same `id` clears, on a loop, so a
// short manual `npm run dev` session sees both the header's fourth counter
// and the per-cell Errors group appear and then disappear, matching the
// acceptance criterion. Riding the same "mutate the working copy, then
// broadcast" order every other errors mutator here uses, so GET /errors
// never runs ahead of what the SSE stream already announced.
const WAVE_HEALTH_EDGE =
  errorsState.parked[0] && snapshot.edges.find((e) => e.to.ref === errorsState.parked[0].ref && e.to.port === errorsState.parked[0].port);
let waveHealthSeq = 0;
let openWaveHealthId = null;

function emitWaveHealthOpen() {
  if (!WAVE_HEALTH_EDGE) return;
  waveHealthSeq += 1;
  const id = `mock-wh-${waveHealthSeq}`;
  const kind = waveHealthSeq % 2 === 0 ? 'stalledWave' : 'frontierLag';
  const lag = 3 + (waveHealthSeq % 5);
  const entry = {
    id,
    kind,
    state: 'open',
    ref: WAVE_HEALTH_EDGE.to.ref,
    edge: WAVE_HEALTH_EDGE.id,
    wave: { source: 'mock0000ab', counter: 100 + waveHealthSeq },
    frontier: { source: 'mock0000ab', counter: 100 + waveHealthSeq - lag },
    lagWaves: lag,
    heldMs: 0,
    atMs: Date.now(),
    heuristic: true,
    description: `Heuristic: ${kind === 'stalledWave' ? 'stalled wave' : 'frontier lag'} detected on this cell (mock tick ${waveHealthSeq}) — may be a legitimate absorbed delta, not necessarily a defect.`,
  };
  errorsState.waveHealth.push(entry);
  errorsState.counters.waveHealth = errorsState.waveHealth.length;
  broadcast('error.waveHealth', entry);
  openWaveHealthId = id;
}

function emitWaveHealthClear() {
  if (!openWaveHealthId) return;
  const openRow = errorsState.waveHealth.find((w) => w.id === openWaveHealthId);
  if (openRow) {
    const cleared = {
      ...openRow,
      state: 'cleared',
      lagWaves: 0,
      frontier: openRow.wave,
      heldMs: Date.now() - openRow.atMs,
      atMs: Date.now(),
      description: 'Heuristic: frontier has caught up with the tapped edge — condition cleared.',
    };
    errorsState.waveHealth = errorsState.waveHealth.filter((w) => w.id !== openWaveHealthId);
    errorsState.counters.waveHealth = errorsState.waveHealth.length;
    broadcast('error.waveHealth', cleared);
  }
  openWaveHealthId = null;
}

function tickWaveHealth() {
  if (openWaveHealthId) emitWaveHealthClear();
  else emitWaveHealthOpen();
}

// Staggered so a short manual-verification session (well under a minute)
// still sees every kind fire at least once, including a wave-health open
// AND its clear.
setInterval(bumpParked, 5000);
setInterval(emitDeadLetter, 12000);
setInterval(bumpRestart, 20000);
setInterval(tickWaveHealth, 4000);

// --- V2: activity state --------------------------------------------------
// Simulates the V2 activity feed (V2-FE ticket §16 "optional" mock
// extension) so the activity log panel — filter, bounded rendering, empty
// state, badges — can be checked by hand against `npm run dev` ahead of
// V2-BE, which runs in parallel and does not exist in this worktree. A
// mutable working copy of fixtures/activity.json, capped at the same 200
// the real server's ring and this client's own ActivityStore both enforce.
const ACTIVITY_CAP = 200;
const activityState = JSON.parse(JSON.stringify(activityFixture));

const ACTIVITY_CYCLE = ['passivated', 'activated', 'drained', 'woken', 'restarted'];
let activityTick = 0;
let activityGeneration = 3;

function emitActivity() {
  const ref = snapshot.nodes[activityTick % snapshot.nodes.length]?.ref;
  if (!ref) return;
  const kind = ACTIVITY_CYCLE[activityTick % ACTIVITY_CYCLE.length];
  activityTick += 1;
  const entry = kind === 'restarted' ? { ref, kind, atMs: Date.now(), generation: ++activityGeneration } : { ref, kind, atMs: Date.now() };
  activityState.entries.push(entry);
  if (activityState.entries.length > ACTIVITY_CAP) activityState.entries.shift();
  broadcast('activity', entry);
}

setInterval(emitActivity, 8000);

// --- flow state --------------------------------------------------------
// Simulates the M3 tap-based flow feed (M3-FE ticket) so the Flow toggle,
// pulses, rate labels, fused rendering, and the per-port Flow subsection can
// be checked manually against `npm run dev` — M3-BE (the real feed) runs in
// parallel and does not exist in this worktree yet. One edge is marked
// `fused: true` purely for this mock's own demo purposes: only the
// in-memory served copy of the snapshot gains the flag, never the checked-in
// `fixtures/topology.json` (still `fused: null` throughout, per its own
// "verbatim capture" contract — see test/fixture.test.ts).
const FUSED_EDGE_ID = snapshot.edges[0]?.id;
if (snapshot.edges[0]) snapshot.edges[0].fused = true;

// Three edges cycle through bands 1/2/3 over time; a fourth goes silent
// every few windows so the store's grace-then-decay behavior
// (sync/flowStore.ts: "absent from a batch decays to zero after 2 missed
// windows") is visible in the UI, not just in the unit tests.
const flowEdges = snapshot.edges.filter((e) => e.id !== FUSED_EDGE_ID).slice(0, 4);
const BAND_RATES = [2, 12, 40];
let flowTick = 0;

function emitFlowRates() {
  flowTick += 1;
  const edges = [];
  flowEdges.forEach((e, i) => {
    // The 4th edge goes silent for two *consecutive* windows every 6-tick
    // cycle — long enough to actually cross DECAY_AFTER_MISSED_WINDOWS (2)
    // and disappear from the canvas/Flow subsection, not just the 1-window
    // grace period (a single miss deliberately stays visible — see
    // sync/flowStore.ts).
    if (i === 3 && flowTick % 6 >= 4) return;
    const rate = BAND_RATES[(flowTick + i) % BAND_RATES.length] + Math.random();
    edges.push({
      id: e.id,
      rate: Math.round(rate * 10) / 10,
      lastWave: { source: 'mock0000ab', counter: flowTick },
      hop: (i % 3) + 1,
    });
  });
  broadcast('flow.rates', { window: 1000, edges });
}

setInterval(emitFlowRates, 1000);

function findNode(ref) {
  return snapshot.nodes.find((n) => n.ref === ref);
}

function detailFor(ref) {
  if (ref === detailFixture.ref) return detailFixture; // the richer, ticket-authored fixture
  const node = findNode(ref);
  if (!node) return undefined;
  const inbound = snapshot.edges.filter((e) => e.to.ref === ref).length;
  const outbound = snapshot.edges.filter((e) => e.from.ref === ref).length;
  return { ...node, attention: null, links: { inbound, outbound, taps: 0 } };
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  const path = url.pathname;

  if (path === '/api/inspect/topology') {
    // M4-BE ticket §5: "GET /topology gains an optional ?graph=g-… filter
    // (unfiltered remains valid)".
    const graphFilter = url.searchParams.get('graph');
    const nodes = graphFilter ? snapshot.nodes.filter((n) => n.graph === graphFilter) : snapshot.nodes;
    const refs = new Set(nodes.map((n) => n.ref));
    const edges = graphFilter ? snapshot.edges.filter((e) => refs.has(e.from.ref) && refs.has(e.to.ref)) : snapshot.edges;
    sendJson(res, 200, { seq, nodes, edges });
    return;
  }

  if (path === '/api/inspect/graphs') {
    sendJson(res, 200, graphsList());
    return;
  }

  if (path === '/api/inspect/search') {
    const mode = url.searchParams.get('mode');
    const q = url.searchParams.get('q') ?? '';
    if (mode === 'name') { sendJson(res, 200, { mode, hits: searchName(q), cost: null }); return; }
    if (mode === 'problems') { sendJson(res, 200, { mode, hits: searchProblems(), cost: null }); return; }
    if (mode === 'data') { sendJson(res, 501, { error: 'data search arrives in M5' }); return; }
    res.writeHead(400, { 'Content-Type': 'text/plain' }).end('unknown search mode');
    return;
  }

  if (path === '/api/inspect/errors') {
    sendJson(res, 200, errorsState);
    return;
  }

  if (path === '/api/inspect/activity') {
    sendJson(res, 200, activityState);
    return;
  }

  if (path === '/api/inspect/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    clients.add(res);
    const beat = setInterval(() => {
      // A heartbeat RE-STATES the current seq without consuming one
      // (20-api-contract.md; sync/client.ts's gap detection depends on this
      // holding server-side too, or every heartbeat looks like a lost delta).
      res.write(`data: ${JSON.stringify({ seq, kind: 'heartbeat', payload: {} })}\n\n`);
    }, 15000);
    req.on('close', () => {
      clearInterval(beat);
      clients.delete(res);
    });
    return;
  }

  // /cell/{ref}, /cell/{ref}/state, /cell/{ref}/observe — {ref} itself
  // contains a ':' but never a '/', so a non-greedy capture unambiguously
  // separates it from an optional /state or /observe suffix.
  const cellMatch = path.match(/^\/api\/inspect\/cell\/(.+?)(?:\/(state|observe))?$/);
  if (cellMatch) {
    const ref = cellMatch[1];
    const sub = cellMatch[2];

    if (!sub && req.method === 'GET') {
      const detail = detailFor(ref);
      if (!detail) { res.writeHead(404).end(); return; }
      sendJson(res, 200, detail);
      return;
    }
    if (sub === 'state' && req.method === 'GET') {
      const v1c = v1cStateFor(ref, url.searchParams.get('cursor'), url.searchParams.get('limit'));
      if (v1c) {
        sendJson(res, v1c.status, v1c.body);
        return;
      }
      sendJson(res, 200, stateFor(ref));
      return;
    }
    if (sub === 'observe' && req.method === 'POST') {
      // V1C-FE demo cells have no fold to observe — that absence is exactly
      // why the real server would answer them `kind: 'page'`/`'snapshot'`
      // rather than `'view'`. Answering 409 here (the real "refused" case,
      // `20-api-contract.md:25`) keeps the mock's generic `state.summary`
      // simulation from firing for them and stomping the paged walk with an
      // unrelated refetch every tick.
      if (v1cStateFor(ref, null, null)) {
        res.writeHead(409).end();
        return;
      }
      startObserving(ref);
      res.writeHead(204).end();
      return;
    }
    if (sub === 'observe' && req.method === 'DELETE') {
      stopObserving(ref);
      res.writeHead(204).end();
      return;
    }
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found — this is the M0/M1/M2/M3/M4-FE fixture mock, not the real :inspect server');
});

server.listen(port, () => {
  console.log(`inspect/ui fixture mock: http://localhost:${port}`);
});
