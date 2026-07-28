// Fixture server for manual/offline UI development (M0-FE ticket Implement
// §1: "add a tiny `npm run mock` static server for the fixture"; extended by
// M1-FE for the cell-detail/state/observe endpoints so the detail panel,
// state live-update, and observe-lifecycle can be checked manually against
// `npm run dev` without a real `:inspect` server).
//
// No deps — Node's built-in http module only. NOT a stand-in for the real
// server's semantics (single global observation slot, one fake "live"
// dataset) — just enough for a human to see the M0+M1 UI work end to end.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const fixturePath = fileURLToPath(new URL('../fixtures/topology.json', import.meta.url));
const detailFixturePath = fileURLToPath(new URL('../fixtures/cell-detail.json', import.meta.url));
const port = Number(process.env.PORT ?? 7071);

const snapshot = JSON.parse(readFileSync(fixturePath, 'utf8'));
const detailFixture = JSON.parse(readFileSync(detailFixturePath, 'utf8'));

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
    sendJson(res, 200, { ...snapshot, seq });
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
  res.end('not found — this is the M0/M1-FE fixture mock, not the real :inspect server');
});

server.listen(port, () => {
  console.log(`inspect/ui fixture mock: http://localhost:${port}`);
});
