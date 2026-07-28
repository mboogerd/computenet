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
// this worktree either.
//
// No deps — Node's built-in http module only. NOT a stand-in for the real
// server's semantics (single global observation slot, one fake "live"
// dataset) — just enough for a human to see the M0+M1+M2+M4 UI work end to
// end.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const fixturePath = fileURLToPath(new URL('../fixtures/topology.json', import.meta.url));
const detailFixturePath = fileURLToPath(new URL('../fixtures/cell-detail.json', import.meta.url));
const errorsFixturePath = fileURLToPath(new URL('../fixtures/errors.json', import.meta.url));
const port = Number(process.env.PORT ?? 7071);

const snapshot = JSON.parse(readFileSync(fixturePath, 'utf8'));
const detailFixture = JSON.parse(readFileSync(detailFixturePath, 'utf8'));
const errorsSnapshot = JSON.parse(readFileSync(errorsFixturePath, 'utf8'));

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
    staleMs: 0,
  };
}

function startObserving(ref) {
  observedRef = ref;
  stopTimer();
  // Simulates "the demo mutates" (M1-FE ticket's manual acceptance item):
  // grows the fake table every 2s and announces it via state.summary, so a
  // selected cell's State subsection can be watched live-updating.
  observeTimer = setInterval(() => {
    liveRows += 1;
    broadcast('state.summary', {
      ref: observedRef,
      cardinality: `${liveRows} rows`,
      frontier: { source: 'mock0000ab', counter: liveRows },
      staleMs: 0,
    });
  }, 2000);
}

function stopTimer() {
  if (observeTimer) clearInterval(observeTimer);
  observeTimer = null;
}

function stopObserving(ref) {
  if (observedRef !== ref) return; // DELETE for a ref that isn't the active one — no-op
  observedRef = null;
  stopTimer();
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
let deadLetterTick = 0;

function emitDeadLetter() {
  const parkedRef = errorsState.parked[0]?.ref;
  const ref = snapshot.nodes.find((n) => n.ref !== parkedRef)?.ref;
  if (!ref) return;
  deadLetterTick += 1;
  const cause = DEAD_LETTER_CAUSES[deadLetterTick % DEAD_LETTER_CAUSES.length];
  const entry = {
    ref,
    cause,
    description: `mock ${cause} at tick ${deadLetterTick}`,
    wave: { source: 'mock0000ab', counter: deadLetterTick },
    atMs: Date.now(),
  };
  errorsState.deadLetters.push(entry);
  errorsState.counters.deadLetters += 1;
  broadcast('error.deadLetter', entry);
}

function bumpRestart() {
  const row = errorsState.restarts[0];
  if (!row) return;
  row.generation += 1;
  row.atMs = Date.now();
  errorsState.counters.restarts += 1;
  broadcast('error.restart', row);
}

// Staggered so a short manual-verification session (well under a minute)
// still sees all three kinds fire at least once.
setInterval(bumpParked, 5000);
setInterval(emitDeadLetter, 12000);
setInterval(bumpRestart, 20000);

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
      sendJson(res, 200, stateFor(ref));
      return;
    }
    if (sub === 'observe' && req.method === 'POST') {
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
